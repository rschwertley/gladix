package dev.brahmkshatriya.echo.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.brahmkshatriya.echo.common.clients.DownloadClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.DownloadContext
import dev.brahmkshatriya.echo.common.models.Progress
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.db.DownloadDatabase
import dev.brahmkshatriya.echo.download.db.models.ContextEntity
import dev.brahmkshatriya.echo.download.db.models.DownloadEntity
import dev.brahmkshatriya.echo.download.db.models.DownloadSummary
import dev.brahmkshatriya.echo.download.db.models.TaskType
import dev.brahmkshatriya.echo.download.exceptions.DownloaderExtensionNotFoundException
import dev.brahmkshatriya.echo.download.tasks.TaskManager
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.Companion.EXTENSION_ID
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.Companion.withExtensionId
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class Downloader(
    val app: App,
    val extensionLoader: ExtensionLoader,
    database: DownloadDatabase,
) {
    val unified = extensionLoader.unified.value

    suspend fun downloadExtension() = extensionLoader.misc.value
        .find { it.isClient<DownloadClient>() && it.isEnabled }
        ?: throw DownloaderExtensionNotFoundException()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("Downloader"))

    val dao = database.downloadDao()
    val downloadFlow = dao.getDownloadsFlow()
    private val contextFlow = dao.getContextFlow()
    private val downloadInfoFlow = downloadFlow.combine(contextFlow) { downloads, contexts ->
        downloads.map { download ->
            val context = contexts.find { download.contextId == it.id }
            Info(download, context, listOf())
        }
    }

    val taskManager = TaskManager(this)

    fun add(
        downloads: List<DownloadContext>
    ) = scope.launch {
        val concurrentDownloads = downloadExtension()
            .getAs<DownloadClient, Int> { concurrentDownloads }
            .getOrNull()?.takeIf { it > 0 } ?: 2
        taskManager.setConcurrency(concurrentDownloads)
        val contexts = downloads.mapNotNull { it.context }.distinctBy { it.id }.associate {
            it.id to dao.insertContextEntity(ContextEntity(0, it.id, it.toJson()))
        }
        downloads.forEach {
            dao.insertDownloadEntity(
                DownloadEntity(
                    0,
                    it.track.extras[EXTENSION_ID] ?: it.extensionId,
                    it.track.id,
                    contexts[it.context?.id],
                    it.sortOrder,
                    it.track.toJson(),
                    TaskType.Loading,
                )
            )
        }
        ensureWorker()
    }

    private val workManager by lazy { WorkManager.getInstance(app.context) }
    private fun ensureWorker() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints(NetworkType.CONNECTED, requiresStorageNotLow = true))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
    }

    private val trackCache = ConcurrentHashMap<Long, Track>()
    fun getCachedTrack(id: Long): Track? = trackCache[id]
    suspend fun getTrackData(id: Long): Track? = trackCache[id] ?: dao.getDownloadData(id)
        ?.toData<Track>()?.getOrNull()?.also { trackCache[id] = it }

    private val servers = ConcurrentHashMap<String, Streamable.Media.Server>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun getServer(
        trackId: Long, download: DownloadEntity
    ): Streamable.Media.Server {
        val key = trackId.toString()
        return mutexes.computeIfAbsent(key) { Mutex() }.withLock {
            servers[key] ?: run {
                val extensionId = download.extensionId
                val extension = extensionLoader.music.getExtensionOrThrow(extensionId)
                val streamable = download.track.getOrThrow()
                    .streamables.find { it.id == download.streamableId }!!
                extension.getAs<TrackClient, Streamable.Media.Server> {
                    val media =
                        loadStreamableMedia(streamable, true) as Streamable.Media.Server
                    media.sources.ifEmpty {
                        throw Exception("${trackId}: No sources found")
                    }
                    media
                }.getOrThrow().also { servers[key] = it }
            }
        }
    }

    fun cancel(trackId: Long) {
        taskManager.remove(trackId)
        val key = trackId.toString()
        scope.launch {
            val entity = dao.getDownloadEntity(trackId) ?: return@launch
            dao.deleteDownloadEntity(entity)
            entity.exceptionFile?.let {
                val file = File(it)
                if (file.exists()) file.delete()
            }
            servers.remove(key)
            mutexes.remove(key)
        }
    }

    fun restart(trackId: Long) {
        taskManager.remove(trackId)
        scope.launch {
            val download = dao.getDownloadEntity(trackId) ?: return@launch
            dao.insertDownloadEntity(
                download.copy(exceptionFile = null, finalFile = null, fullyDownloaded = false)
            )
            download.exceptionFile?.let {
                val file = File(it)
                if (file.exists()) file.delete()
            }
            servers.remove(trackId.toString())
            mutexes.remove(trackId.toString())
            ensureWorker()
        }
    }

    fun cancelAll() {
        taskManager.removeAll()
        scope.launch {
            val downloads = downloadFlow.first().filter { it.finalFile == null }
            downloads.forEach { download ->
                dao.deleteDownloadEntityById(download.id)
                servers.remove(download.id.toString())
                mutexes.remove(download.id.toString())
            }
        }
    }

    fun deleteDownload(id: String) {
        scope.launch {
            val downloads = downloadFlow.first().filter { it.trackId == id }
            downloads.forEach { download ->
                dao.deleteDownloadEntityById(download.id)
            }
        }
    }

    fun deleteContext(id: String) {
        scope.launch {
            val contexts = contextFlow.first().filter { it.itemId == id }
            contexts.forEach { context ->
                dao.deleteContextEntity(context)
                val downloads = downloadFlow.first().filter {
                    it.contextId == context.id
                }
                downloads.forEach { download ->
                    dao.deleteDownloadEntityById(download.id)
                }
            }
        }
    }

    data class Info(
        val download: DownloadSummary,
        val context: ContextEntity?,
        val workers: List<Pair<TaskType, Progress>>
    )

    val flow = downloadInfoFlow.combine(taskManager.progressFlow) { downloads, info ->
        downloads.map { (dl, context) ->
            val workers = info.filter { it.first.trackId == dl.id }.map { (a, b) -> a.type to b }
            Info(dl, context, workers)
        }.sortedByDescending { it.workers.size }
    }.stateIn(scope, SharingStarted.Eagerly, listOf())

    init {
        scope.launch {
            downloadInfoFlow.map { info ->
                info.filter { it.download.fullyDownloaded }.groupBy {
                    it.context?.id
                }.flatMap { (id, infos) ->
                    if (id == null) infos.mapNotNull {
                        getTrackData(it.download.id)
                            ?.withExtensionId(it.download.extensionId, false)
                    }
                    else listOfNotNull(infos.first().runCatching {
                        unified.db.getPlaylist(context?.mediaItem!!.getOrThrow())
                    }.getOrNull())
                }
            }.collect(unified.downloadFeed)
        }
    }

    companion object {
        private const val TAG = "Downloader"
    }
}