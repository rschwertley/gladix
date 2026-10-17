package dev.brahmkshatriya.echo.playback.source

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.backgroundIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.retries
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.sourceIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.subtitleIndex
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.select
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.exceptions.TrackUnavailableException
import dev.brahmkshatriya.echo.utils.HealthMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

@UnstableApi
class StreamableMediaSource(
    private var mediaItem: MediaItem,
    private val app: App,
    private val scope: CoroutineScope,
    private val state: PlayerState,
    private val loader: StreamableLoader,
    private val cacheFactories: Factories,
    private val factories: Factories,
    private val changeFlow: MutableSharedFlow<Pair<MediaItem, MediaItem>>,
) : CompositeMediaSource<Nothing>() {

    private var error: Throwable? = null
    override fun maybeThrowSourceInfoRefreshError() {
        error?.let { throw IOException(it) }
        super.maybeThrowSourceInfoRefreshError()
    }

    fun getFactory(source: Streamable.Source) = if (source.isLive) factories else cacheFactories

    @Volatile private var released = false
    private var loadJob: Job? = null
    private lateinit var actualSource: MediaSource

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        released = false
        error = null
        super.prepareSourceInternal(mediaTransferListener)
        Log.d("GladixPlayback", "prepareSourceInternal: ${mediaItem.mediaId} \"${mediaItem.mediaMetadata.title}\"")
        val handler = Util.createHandlerForCurrentLooper()
        loadJob = scope.launch {
            // 0 -> 1 edge only - see PlayerState.loadEpisodeStartMs for why not every increment.
            if (state.activeLoadCount.incrementAndGet() == 1)
                state.loadEpisodeStartMs.set(System.currentTimeMillis())
            try {
                var (new, serv) = runCatching { loader.load(mediaItem) }.getOrElse {
                    error = it
                    return@launch
                }
                val server = serv.getOrNull()
                state.servers[new.mediaId] = serv
                state.serverChanged.emit(Unit)
                val sources = server?.sources
                Log.d("GladixPlayback", "stream loaded: ${new.mediaId} server=${server != null} sources=${sources?.size ?: "null"}")
                actualSource = when (sources?.size) {
                    0, null -> {
                        Log.d("GladixPlayback", "null/empty sources for ${new.mediaId}")
                        error = serv.exceptionOrNull()
                            ?: TrackUnavailableException("No sources available for ${new.mediaId}")
                        return@launch
                    }
                    1 -> {
                        val source = sources.first()
                        getFactory(source).create(new, 0, source)
                    }

                    else -> {
                        if (server.merged) MergingMediaSource(
                            *sources.mapIndexed { index, source ->
                                getFactory(source).create(new, index, source)
                            }.toTypedArray()
                        ) else {
                            val index = mediaItem.sourceIndex
                            val source = sources.getOrNull(index)
                                ?: sources.select(app, new.extensionId) { it.quality }
                            val newIndex = sources.indexOf(source)
                            new = MediaItemUtils.buildSource(new, newIndex)
                            getFactory(source).create(new, newIndex, source)
                        }
                    }
                }

                changeFlow.emit(mediaItem to new)
                mediaItem = new

                handler.post {
                    if (released) {
                        // TRACER - see PlayerState.releasedPrepareSkips. Counted here rather than logged
                        // only, because this branch is silent on a user's device and the logcat line below
                        // cannot be captured in the field.
                        state.releasedPrepareSkips.incrementAndGet()
                        Log.d("GladixPlayback", "handler.post: released, skipping prepareChildSource for ${mediaItem.mediaId}")
                        return@post
                    }
                    Log.d("GladixPlayback", "handler.post: prepareChildSource firing for ${mediaItem.mediaId}")
                    runCatching {
                        prepareChildSource(null, actualSource)
                    }.getOrElse {
                        Log.e("GladixPlayback", "prepareChildSource threw for ${mediaItem.mediaId}", it)
                    }
                }
            } finally {
                if (state.activeLoadCount.decrementAndGet() == 0)
                    state.loadEpisodeStartMs.set(0L)
            }
        }
    }

    override fun releaseSourceInternal() {
        released = true
        loadJob?.cancel()
        loadJob = null
        super.releaseSourceInternal()
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Nothing?, mediaSource: MediaSource, newTimeline: Timeline,
    ) = refreshSourceInfo(newTimeline)

    override fun getMediaItem() = mediaItem

    override fun createPeriod(
        id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long,
    ): MediaPeriod {
        Log.d("GladixPlayback", "createPeriod: ${mediaItem.mediaId} \"${mediaItem.mediaMetadata.title}\"")
        check(::actualSource.isInitialized) { "createPeriod called before source was prepared" }
        return actualSource.createPeriod(id, allocator, startPositionUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        if (::actualSource.isInitialized) actualSource.releasePeriod(mediaPeriod)
    }

    /**
     * ⚠⚠ A serverIndex DIFFERENCE MUST RETURN false, AND THAT IS THE WHOLE CONTRACT.
     * Returning true tells media3 to keep this source and swap the MediaItem on it, with no fresh
     * prepare and therefore NO RE-RESOLVE - so a true here on a serverIndex change absorbs a
     * quality-sheet choice silently, with the chip showing the new server and the old bytes still
     * playing. Every field below is in the list for the same reason: it changes what would be
     * resolved or demuxed, so it cannot be applied in place.
     */
    override fun canUpdateMediaItem(mediaItem: MediaItem) = run {
        this.mediaItem.apply {
            if (retries != mediaItem.retries) return@run false
            if (serverIndex != mediaItem.serverIndex) return@run false
            if (this.sourceIndex != mediaItem.sourceIndex) return@run false
            if (backgroundIndex != mediaItem.backgroundIndex) return@run false
            if (subtitleIndex != mediaItem.subtitleIndex) return@run false
        }
        if (::actualSource.isInitialized) actualSource.canUpdateMediaItem(mediaItem)
        else false
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
        actualSource.updateMediaItem(mediaItem)
    }

    /**
     * [proxy] and [proxyUrl] are BOTH null unless LocalMediaServer.PLAY_THROUGH_LOCAL_SERVER is
     * on, which is the whole of the Cast serving layer's reach into normal playback.
     */
    data class Factories(
        val dash: Lazy<MediaSource.Factory>,
        val hls: Lazy<MediaSource.Factory>,
        val default: Lazy<MediaSource.Factory>,
        val proxy: Lazy<MediaSource.Factory>? = null,
        val proxyUrl: ((String) -> String?)? = null,
    ) {
        fun create(mediaItem: MediaItem, index: Int, source: Streamable.Source?): MediaSource {
            val new = MediaItemUtils.buildForSource(mediaItem, index, source)
            // ⚠⚠ THE TEST HOOK, AND IT IS A DIVERSION RATHER THAN AN ADDITION: with the
            // flag on, the local player reads the SAME bytes over http from LocalMediaServer
            // instead of through the resolving/caching chain, so the thing being verified is the
            // server and not a parallel path that merely coexists with the real one.
            // ⚠️ THREE CONSEQUENCES OF THE DIVERSION, STATED BECAUSE NONE IS A BUG:
            // (1) nothing writes the SimpleCache while it is on - the caching chain is no longer
            // in the local player's path and LocalMediaServer has a null write sink;
            // (2) the uri now carries an `http` scheme, so DefaultLoadControl.isLocalPlayback
            // flips to false for the first time ever on this codebase - harmless only because
            // PlayerService.createExoplayer's explicit LoadControl gives BOTH classifications the
            // same four values, which is the reason that block exists;
            // (3) dash/hls are bypassed. A manifest source would be served as one progressive
            // resource and fail, which is correct for a test hook and is stage 3's problem.
            val diverted = proxyUrl?.let { lookup ->
                new.localConfiguration?.uri?.toString()?.let(lookup)
            }
            if (diverted != null && proxy != null) return proxy.value.createMediaSource(
                new.buildUpon().setUri(diverted).build()
            )
            val type = (source as? Streamable.Source.Http)?.type
            val factory = when (type) {
                Streamable.SourceType.DASH -> dash
                Streamable.SourceType.HLS -> hls
                Streamable.SourceType.Progressive, null -> default
            }
            return factory.value.createMediaSource(new)
        }
    }

    class Factory(
        private val app: App,
        private val scope: CoroutineScope,
        private val state: PlayerState,
        extensions: ExtensionLoader,
        cache: SimpleCache,
        downloadFlow: StateFlow<List<Downloader.Info>>,
        private val changeFlow: MutableSharedFlow<Pair<MediaItem, MediaItem>>,
        healthMonitor: HealthMonitor,
        /**
         * Key uri -> LocalMediaServer URL, or null for "use the ordinary chain". Null by default
         * and non-null only from PlayerService.createExoplayer with the test flag on, so every
         * other construction of this Factory is untouched.
         */
        private val proxyUrl: ((String) -> String?)? = null,
    ) : MediaSource.Factory {

        private val loader = StreamableLoader(app, extensions.music, downloadFlow, healthMonitor)

        val dataSourceFactory = StreamableDataSource.Factory(app.context)
        val streamableResolver = StreamableResolver(app.context, state.servers)

        private val cacheDataSource = ResolvingDataSource.Factory(
            CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(dataSourceFactory),
            streamableResolver
        )

        private val dataSource = ResolvingDataSource.Factory(
            dataSourceFactory, streamableResolver
        )

        /**
         * ⚠⚠ A PLAIN DataSource, DELIBERATELY NOT [dataSource] OR [cacheDataSource].
         * Pointing a RESOLVING factory at the proxy URL would send it through
         * StreamableResolver (whose toKey() fails on an http uri, so it would pass through) and
         * then CacheDataSource, which would cache the proxy's response under a SECOND key - a
         * duplicate copy on disk, and a replay that never touches the server being tested.
         * DefaultDataSource covers http/https; the proxy is the only thing it is ever given.
         * Declared ABOVE [cacheFactories] because Kotlin initialises properties in source order
         * and both createFactories calls read it.
         */
        private val proxyFactory = if (proxyUrl == null) null else lazily {
            DefaultMediaSourceFactory(DefaultDataSource.Factory(app.context))
        }

        private val cacheFactories = createFactories(cacheDataSource)

        private val factories = createFactories(dataSource)

        // Both sets get the hook: which one a given item uses is StreamableMediaSource.getFactory's
        // call, and the diversion has to be independent of that choice.
        private fun createFactories(dataSource: ResolvingDataSource.Factory) = Factories(
            lazily { DashMediaSource.Factory(dataSource) },
            lazily { HlsMediaSource.Factory(dataSource) },
            lazily { DefaultMediaSourceFactory(dataSource) },
            proxyFactory,
            proxyUrl
        )

        private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null
        private fun lazily(factory: () -> MediaSource.Factory) = lazy {
            factory().apply {
                drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) }
                loadErrorHandlingPolicy?.let { setLoadErrorHandlingPolicy(it) }
            }
        }

        override fun getSupportedTypes() = intArrayOf(
            C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH
        )

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider,
        ): MediaSource.Factory {
            this.drmSessionManagerProvider = drmSessionManagerProvider
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
        ): MediaSource.Factory {
            this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
            return this
        }

        override fun createMediaSource(mediaItem: MediaItem) = StreamableMediaSource(
            mediaItem, app, scope, state, loader, cacheFactories, factories, changeFlow
        )
    }
}