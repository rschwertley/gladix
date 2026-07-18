package dev.brahmkshatriya.echo.extensions

import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.LyricsExtension
import dev.brahmkshatriya.echo.common.MiscExtension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.TrackerExtension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.helpers.Injectable
import dev.brahmkshatriya.echo.common.helpers.WebViewClient
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.providers.GlobalSettingsProvider
import dev.brahmkshatriya.echo.common.providers.LyricsExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.MessageFlowProvider
import dev.brahmkshatriya.echo.common.providers.MetadataProvider
import dev.brahmkshatriya.echo.common.providers.MiscExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.NetworkConnectionProvider
import dev.brahmkshatriya.echo.common.providers.TrackerExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.WebViewClientProvider
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getOrThrow
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.inject
import dev.brahmkshatriya.echo.extensions.builtin.offline.OfflineExtension
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extensions.db.ExtensionDatabase
import dev.brahmkshatriya.echo.extensions.db.models.CurrentUser
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import dev.brahmkshatriya.echo.extensions.exceptions.RequiredExtensionsMissingException
import dev.brahmkshatriya.echo.extensions.repo.CombinedRepository
import dev.brahmkshatriya.echo.extensions.repo.ExtensionParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class ExtensionLoader(
    val app: App,
    val cache: SimpleCache,
) {
    val parser = ExtensionParser(app.context)

    // Same background safety net as App.scope: route UNCAUGHT failures of coroutines launched on this scope
    // (eager injection, extension selection, network-triggered token work, etc.) to app.throwFlow so they
    // degrade to the standard non-fatal instead of crashing. CancellationException never reaches the handler
    // (guarded anyway); scope.launch bridges to the suspend emit (SupervisorJob keeps the scope alive after a
    // child fails); runCatching keeps a recording failure from re-crashing. See App.exceptionHandler.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        runCatching { scope.launch { app.throwFlow.emit(throwable) } }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    val db = ExtensionDatabase.create(app.context)

    private var permGrantedFlow = false
    fun setPermGranted() {
        if (permGrantedFlow) return
        permGrantedFlow = true
        val id = current.value?.id
        if (id == OfflineExtension.metadata.id || id == UnifiedExtension.metadata.id) {
            current.value = null
            scope.launch {
                delay(1)
                setCurrentExtension()
            }
        }
    }

    val unified = lazy { UnifiedExtension(app, cache) }
    val fileIgnoreFlow = MutableSharedFlow<File?>()
    private val repository = CombinedRepository(
        scope, app.context, fileIgnoreFlow, parser,
        Metadata(
            className = "dev.brahmkshatriya.echo.extension.DeezerExtension",
            path = "",
            importType = ImportType.BuiltIn,
            type = ExtensionType.MUSIC,
            id = "deezer",
            name = "Deezer",
            version = "v1.0.0",
            description = "Deezer Extension for Echo.",
            author = "Luftnos",
            authorUrl = null,
            icon = "https://cdn-files.dzcdn.net/img/common/og-deezer-logo.png".toImageHolder(),
            repoUrl = null,
            updateUrl = null,
            preservedPackages = emptyList(),
            isEnabled = true,
        ) to lazy { DeezerExtension() },
        UnifiedExtension.metadata to unified,
        OfflineExtension.metadata to lazy { OfflineExtension(app.context) },
//        TestExtension.metadata to lazy { TestExtension() },
    )

    private val settings = app.settings
    val priorityMap = ExtensionType.entries.associateWith {
        val key = it.priorityKey()
        val list = settings.getString(key, null).orEmpty().split(',')
        MutableStateFlow(list)
    }

    val current = MutableStateFlow<MusicExtension?>(null)
    private fun setCurrentExtension() {
        val last = settings.getString(LAST_EXTENSION_KEY, null)
        val list = music.value
        val extension = list.find { it.id == last && it.isEnabled }
            ?: list.firstOrNull { it.isEnabled }
            ?: return
        setupMusicExtension(extension, false)
    }

    fun setupMusicExtension(extension: MusicExtension, manual: Boolean) {
        if (manual) settings.edit { putString(LAST_EXTENSION_KEY, extension.id) }
        current.value = extension
        scope.launch {
            extension.get { onExtensionSelected() }.getOrThrow(app.throwFlow)
        }
    }

    private val injected = repository.flow.map { list ->
        list?.groupBy { it.getOrNull()?.first?.run { type to id } }?.map { entry ->
            entry.value.minBy { it.getOrNull()?.first?.importType?.ordinal ?: Int.MAX_VALUE }
        }.orEmpty()
    }.combine(db.extensionEnabledFlow) { list, enabledList ->
        val enabledMap = enabledList.associate { (it.type to it.id) to it.enabled }
        list.map { result ->
            result.mapCatching { (metadata, injectable) ->
                val key = metadata.run { type to id }
                val isEnabled = enabledMap[key] ?: metadata.isEnabled
                metadata.copy(isEnabled = isEnabled) to injectable
            }
        }
    }.map { list ->
        list.map { result ->
            result.map {
                it.first to it.second.injected(it.first)
            }
        }
    }.combine(db.currentUsersFlow) { list, users ->
        list.onEach { result ->
            scope.launch(Dispatchers.IO) {
                val (metadata, injectable) = result.getOrNull() ?: return@launch
                runCatching {
                    injectable.injectOrRun("user") {
                        if (this !is LoginClient) return@injectOrRun
                        val newCurr = users.getUser(metadata)
                        val user = newCurr?.let { db.getUser(it) }
                        setLoginUser(user)
                    }
                }.onFailure {
                    app.throwFlow.emit(it.toAppException(metadata))
                }
            }
        }
    }

    val webViewClientFactory = WebViewClientFactory(app.context)
    private fun createWebClient(metadata: Metadata): WebViewClient {
        if (metadata.type != ExtensionType.MUSIC)
            throw Exception("Webview client is not available for ${metadata.type} Extensions")
        return webViewClientFactory.createFor(metadata)
    }

    private fun Lazy<ExtensionClient>.injected(
        metadata: Metadata,
    ) = Injectable(::value, mutableListOf({
        if (this is MetadataProvider) setMetadata(metadata)
        if (this is MessageFlowProvider) setMessageFlow(app.messageFlow)
        if (this is GlobalSettingsProvider)
            setGlobalSettings(ExtensionUtils.getGlobalSettings(app.context))
        setSettings(ExtensionUtils.getSettings(app.context, metadata))
        if (this is WebViewClientProvider) setWebViewClient(createWebClient(metadata))
        onInitialize()
        onExtensionSelected()
    }))

    private fun <T : Extension<*>> mapped(
        type: ExtensionType, transform: (Metadata, Injectable<ExtensionClient>) -> T,
    ) = injected.map { list ->
        list.mapNotNull {
            val (meta, injectable) = it.getOrNull() ?: return@mapNotNull null
            if (meta.type != type) return@mapNotNull null
            transform(meta, injectable)
        }
    }.combine(priorityMap[type]!!) { list, _ ->
        list.sorted(type) { it.id }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    val music = mapped(ExtensionType.MUSIC) { m, i -> MusicExtension(m, i) }
    val tracker = mapped(ExtensionType.TRACKER) { m, i -> TrackerExtension(m, i.casted()) }
    val lyrics = mapped(ExtensionType.LYRICS) { m, i -> LyricsExtension(m, i.casted()) }
    val misc = mapped(ExtensionType.MISC) { m, i -> MiscExtension(m, i) }
    val all = combine(music, tracker, lyrics, misc) { music, tracker, lyrics, misc ->
        music + tracker + lyrics + misc
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    val isLoaded = repository.flow
        .map { it != null }
        .stateIn(scope, SharingStarted.Lazily, false)

    init {
        scope.launch {
            all.collect { list ->
                list.forEach {
                    if (!it.isEnabled) return@forEach
                    it.inject("providers", app.throwFlow) { injectProviders(this) }
                }
            }
        }
        scope.launch {
            music.collectLatest { setCurrentExtension() }
        }
        scope.launch {
            app.networkFlow.combine(all) { a, b -> a to b }.collect { (conn, all) ->
                all.forEach {
                    if (!it.isEnabled) return@forEach
                    it.inject("network", app.throwFlow) {
                        if (this !is NetworkConnectionProvider) return@inject
                        setNetworkConnection(conn)
                    }
                }
            }
        }
    }

    private fun <T> List<T>.sorted(type: ExtensionType, id: (T) -> String): List<T> {
        val priority = priorityMap[type]!!.value
        return sortedBy { priority.indexOf(id(it)) }
    }

    fun getFlow(type: ExtensionType) = when (type) {
        ExtensionType.MUSIC -> music
        ExtensionType.TRACKER -> tracker
        ExtensionType.LYRICS -> lyrics
        ExtensionType.MISC -> misc
    }

    private fun injectProviders(client: ExtensionClient) {
        (client as? MusicExtensionsProvider)?.run {
            inject(requiredMusicExtensions, music.value) { setMusicExtensions(it) }
        }
        (client as? TrackerExtensionsProvider)?.run {
            inject(requiredTrackerExtensions, tracker.value) { setTrackerExtensions(it) }
        }
        (client as? LyricsExtensionsProvider)?.run {
            inject(requiredLyricsExtensions, lyrics.value) { setLyricsExtensions(it) }
        }
        (client as? MiscExtensionsProvider)?.run {
            inject(requiredMiscExtensions, misc.value) { setMiscExtensions(it) }
        }
    }

    companion object {
        private fun <T, R : Extension<*>> T.inject(
            required: List<String>,
            extensions: List<R>,
            set: T.(List<R>) -> Unit,
        ) {
            if (required.isEmpty()) set(extensions)
            else {
                val filtered = extensions.filter { it.metadata.id in required }
                if (filtered.size == required.size) set(filtered)
                else throw RequiredExtensionsMissingException(required)
            }
        }

        fun List<CurrentUser>.getUser(ext: Metadata): CurrentUser? {
            val curr = find { it.type == ext.type && it.extId == ext.id }
            return curr
        }

        fun ExtensionType.priorityKey() = "priority_${this.feature}"

        const val LAST_EXTENSION_KEY = "last_extension"
    }

}