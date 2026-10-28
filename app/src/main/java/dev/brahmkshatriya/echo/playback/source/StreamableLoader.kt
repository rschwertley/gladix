package dev.brahmkshatriya.echo.playback.source

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadStreamableMedia
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.backgroundIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.downloaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.state
import dev.brahmkshatriya.echo.playback.MediaItemUtils.subtitleIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.exceptions.TrackUnavailableException
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.playableString
import dev.brahmkshatriya.echo.utils.HealthMonitor
import dev.brahmkshatriya.echo.utils.scrubbedForCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class StreamableLoader(
    private val app: App,
    private val extensionListFlow: StateFlow<List<MusicExtension>>,
    private val downloadFlow: StateFlow<List<Downloader.Info>>,
    private val healthMonitor: HealthMonitor,
) {
    suspend fun load(mediaItem: MediaItem) = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            withTimeout(RESOLVE_BUDGET_MS) {
                extensionListFlow.first { it.isNotEmpty() }
                val new = if (mediaItem.isLoaded) mediaItem
                else MediaItemUtils.buildLoaded(
                    app, downloadFlow.value, mediaItem, loadTrack(mediaItem)
                )

                val server = async { loadServer(new) }
                val background =
                    async { if (new.backgroundIndex < 0) null else loadBackground(new).getOrNull() }
                val subtitle = async { if (new.subtitleIndex < 0) null else loadSubtitle(new).getOrNull() }

                val first = MediaItemUtils.buildWithBackgroundAndSubtitle(
                    new, background.await(), subtitle.await()
                ) to server.await()
                // Normal path ends here unchanged; only a failed server reaches the safety net.
                if (first.second.isSuccess) {
                    failedNets.set(0)
                    first
                } else safetyNet(mediaItem, first, startMs)
            }
        } catch (e: TimeoutCancellationException) {
            healthMonitor.report(
                HealthMonitor.ExtensionResolutionTimeout(mediaItem.extensionId, System.currentTimeMillis() - startMs),
                HealthMonitor.Scope.PERSISTENT, 60 * 60 * 1000L
            )
            throw e
        }
    }

    /**
     * ⚠⚠ ONE FRESH RELOAD PER RESOLVE, ON FAILURE, ONLINE - THE NET UNDER EVERY STORED COPY.
     * A cache hit in [loadTrack] (preferCache = true) serves whatever the media-state entry was
     * first written with, for ever - the 2026-10-27 Maria / Baby Beluga skips on 1151, which played
     * for good once the long-press sheet's preferCache = false load overwrote that entry. This runs
     * the SAME load the sheet does, which re-resolves through the extension AND overwrites the entry,
     * then retries the server once. Third instance of one flaw (1120 empty sources, 1151 feed tokens,
     * this); the structural fix was proposed and deliberately scaled back to this net for now.
     * ⚠️ SKIPPED ONLY WHEN THE STUB SAYS Playable.No. History and the saved queue RESET the verdict
     * (HistoryEntity.resetPlayable), so a No can only have come from a list load - a verdict this
     * reload could not change, and a marked dead track must stay at zero requests
     * (PlayerEventListener's skip-cost note). RegionLocked / Unreleased still get the reload.
     * ⚠️ OFFLINE IS UNCHANGED: no reload, the first failure is returned as before.
     * ⚠️ BOUNDED BY THE RESOLVE'S OWN 30 s BUDGET, not added to it: it gets what is left minus a
     * second, so running out returns the FIRST failure instead of tripping [load]'s timeout and
     * filing an ExtensionResolutionTimeout that the first attempt did not earn.
     * ⚠️ ONE PER RESOLVE BY CONSTRUCTION: it calls loadServer directly, never [load], so it cannot
     * re-enter itself. A later retry of the item (MediaItemUtils.withRetry) is a new resolve and gets
     * its own; the Unavailable family skips instead of retrying (onPlayerError), so a dead track
     * meets this once.
     * ⚠️ A RESCUED SONG IS NOT A SKIP: the skip budget and the catalogue-dead health check move only
     * when an error reaches PlayerEventListener.onPlayerError -> recordSkip, and a rescue returns a
     * working server, so nothing reaches it; STATE_READY then resets both as for any play.
     */
    private suspend fun safetyNet(
        original: MediaItem,
        first: Pair<MediaItem, Result<Streamable.Media.Server>>,
        startMs: Long,
    ): Pair<MediaItem, Result<Streamable.Media.Server>> {
        val cause = first.second.exceptionOrNull() ?: return first
        if (cause is CancellationException) throw cause
        val id = original.track.id
        if (app.networkFlow.value == NetworkConnection.NotConnected) {
            logSafetyNet(id, cause, "skipped_offline")
            return first
        }
        if (original.track.isPlayable is Track.Playable.No) {
            logSafetyNet(id, cause, "skipped_stub_no")
            return first
        }
        if (failedNets.get() >= MAX_FAILED_NETS) {
            logSafetyNet(id, cause, "skipped_cap")
            return first
        }
        val remainingMs = RESOLVE_BUDGET_MS - (System.currentTimeMillis() - startMs) - 1_000L
        if (remainingMs <= 0) {
            logSafetyNet(id, cause, "no_time")
            return first
        }
        val retried = withTimeoutOrNull(remainingMs) {
            // A failed reload is a failed retry, never a new throw out of load(): the caller gets a
            // Result either way. Cancellation (including this block's own timeout, which loadMedia's
            // runCatching would otherwise capture) is rethrown.
            val state = withClient(original) { Cached.loadMedia(app, it, original.state) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    return@withTimeoutOrNull first.first to Result.failure(it)
                }
            val reloaded = MediaItemUtils.buildLoaded(app, downloadFlow.value, original, state)
            val server = loadServer(reloaded)
            val background = if (reloaded.backgroundIndex < 0) null
            else loadBackground(reloaded).getOrNull()
            val subtitle = if (reloaded.subtitleIndex < 0) null
            else loadSubtitle(reloaded).getOrNull()
            MediaItemUtils.buildWithBackgroundAndSubtitle(reloaded, background, subtitle) to server
        }
        val outcome = when {
            retried == null -> "timeout"
            retried.second.isSuccess -> "ok"
            else -> "fail:${retried.second.exceptionOrNull()?.let { it::class.simpleName }}"
        }
        logSafetyNet(id, cause, outcome)
        if (retried?.second?.isSuccess == true) failedNets.set(0) else failedNets.incrementAndGet()
        return retried ?: first
    }

    /**
     * Consecutive safety nets that did not rescue. After [MAX_FAILED_NETS] the net is skipped until any
     * resolve succeeds - first attempt or rescue - which bounds what a run of dead songs can add.
     * ⚠️ WHY IT EXISTS: each net is one more loadStreamableMedia, and on Deezer that is up to 6 relay
     * calls typically and 12 at worst for a 320 user (3 ladder passes x 2 attempts, x2 when the relay
     * throws). The 320 relay is shared and has a ~1,000/day threshold, so an unmarked dead run - a
     * restored queue, where the verdict is reset - must not double its relay cost without limit.
     * With the cap a dead run adds at most +18..36 relay calls. Atomic: loads run concurrently
     * (preloads, crossfade). The 3 is uncalibrated.
     */
    private val failedNets = AtomicInteger(0)

    // [TEMPORARY 2026-10-27] PERISHABLE-CACHE PROBE - the removal condition is at Cached.logPreferCache.
    // One line on EVERY safety-net evaluation, including the skips, so a missing line means the
    // first resolve succeeded.
    // msg= is the DEEPEST message in the chain (the outer one is usually an AppException wrapper),
    // URL-stripped by the shared scrubbedForCrashlytics, then any run of 40+ token-alphabet characters
    // redacted - a TRACK_TOKEN or ARL can never print, while track ids (digits, <= ~12) and Deezer's own
    // sentences survive. Capped at 120.
    private fun logSafetyNet(id: String, cause: Throwable, outcome: String) {
        val deepest = generateSequence(cause) { it.cause }.mapNotNull { it.message }.lastOrNull()
        val msg = deepest?.replace(OPAQUE_RUN, "<redacted>")?.scrubbedForCrashlytics(120)
        Log.d(
            "GladixPlayback",
            "[TEMPORARY 2026-10-27] safety_net id=$id cause=${cause::class.simpleName} " +
                "msg=\"$msg\" retry=$outcome"
        )
    }

    private suspend fun <T> withClient(
        mediaItem: MediaItem,
        block: suspend (Extension<*>) -> Result<T>
    ): Result<T> {
        val extension = extensionListFlow.getExtensionOrThrow(mediaItem.extensionId)
        return block(extension)
    }

    private suspend fun loadTrack(item: MediaItem): MediaState.Loaded<Track> {
        val stub = item.state.item
        Log.d("GladixPlayback", "loadTrack stub: id=${stub.id} servers=${stub.servers.map { it.id }} extras=${stub.extras}")
        val track = withClient(item) {
            // preferCache = true: the ONLY caller that passes it. See Cached.loadMedia — this keeps a
            // restored queue resolving from cache instantly, which is what makes offline playback work,
            // and which was previously happening only as a side effect of UnifiedExtension.loadTrack
            // throwing on a missing extension_id.
            Cached.loadMedia(app, it, item.state, preferCache = true)
        }
        val result = track.getOrThrow()
        Log.d("GladixPlayback", "loadTrack result: id=${result.item.id} servers=${result.item.servers.map { it.id }}")
        return result
    }

    private suspend fun loadServer(mediaItem: MediaItem): Result<Streamable.Media.Server> {
        val downloaded = mediaItem.downloaded
        val servers = mediaItem.track.servers
        val index = mediaItem.serverIndex
        if (!downloaded.isNullOrEmpty() && servers.size == index) {
            return runCatching {
                Streamable.Media.Server(
                    downloaded.map { Uri.fromFile(File(it)).toString().toSource() },
                    true
                )
            }
        }
        return withClient(mediaItem) {
            runCatching {
                val isPlayable = mediaItem.track.playableString(app.context)
                if (isPlayable != null) throw TrackUnavailableException(isPlayable)
                // serverIndex was chosen at build time against the stub's servers; if the freshly-loaded track
                // exposes fewer servers, that index is out of range. This is a DETERMINISTIC local mismatch —
                // re-resolving can never fix it (the generic-tail retry sets loaded=false and re-hits the
                // network each pass, which is what produced the back-to-back "Server not found" burst and can
                // feed a sub-extension rate-limit spiral). Throw the NON-retryable TrackUnavailableException so
                // onPlayerError skips this track ONCE (the "not available" skip family), with no re-resolution,
                // bounded by consecutiveUnavailableSkips — consistent with the 0-sources case just below.
                // ⚠⚠ TWO DIFFERENT FAULTS, SPLIT 2026-09-16 - THEY SHARED ONE STRING AND ONE OF
                // THEM WAS BEING MISREAD BECAUSE OF IT. The old text was "Server not found", which is
                // both WRONG for local playback (the Offline extension plays files; there is no server)
                // and jargon for everyone else. The word is gone from both branches.
                //   EMPTY         the track arrived carrying no playable sources at all. On a RESTORED
                //                 queue this is the toSlim contract being violated by that extension's
                //                 loadTrack - see the notes at HistoryEntity.toSlim and
                //                 OfflineExtension.loadTrack. Investigate THAT, not this line.
                //   OUT OF RANGE  the deterministic build-time index mismatch described above: the
                //                 freshly loaded track exposes fewer servers than the index chosen at
                //                 build time. Re-resolving can never fix it.
                // Both stay TrackUnavailableException (non-retryable) - the split is the MESSAGE and the
                // diagnosis, NOT the behaviour. Do not make either retryable without re-reading the
                // burst/rate-limit reasoning above.
                // ⚠️ [PARKED 2026-10-15] "RE-RESOLVING CAN NEVER FIX IT" ABOVE IS TRUE
                // ONLY BECAUSE THE RE-RESOLVE IS CACHE-SERVED, and nothing in that sentence says so.
                // The generic-tail retry does set loaded=false and does re-enter load() from the top -
                // but loadTrack above passes preferCache = true, so Cached.loadMedia returns the SAME
                // cached Track and the index mismatch is identical on every pass. With a FRESH resolve
                // the mismatch WOULD be fixable, because MediaItemUtils.buildLoaded recomputes
                // serverIndex from the newly loaded servers. The fix (option C) is parked, with its
                // risks and its build trigger, at MediaItemUtils.withRetry.
                // IF IT IS EVER BUILT, CORRECT THE SENTENCE ABOVE rather than leaving it to be read as
                // unconditional: the non-retryable throw is right today and would stop being right.
                val streamable = servers.getOrNull(index) ?: throw TrackUnavailableException(
                    if (servers.isEmpty()) "No playable source for this track"
                    else "Playable source no longer available"
                )
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Server
            }
        }
    }

    private suspend fun loadBackground(mediaItem: MediaItem): Result<Streamable.Media.Background> {
        val streams = mediaItem.track.backgrounds
        val index = mediaItem.backgroundIndex
        val streamable = streams[index]
        return withClient(mediaItem) {
            runCatching {
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Background
            }
        }
    }

    private suspend fun loadSubtitle(mediaItem: MediaItem): Result<Streamable.Media.Subtitle> {
        val streams = mediaItem.track.subtitles
        val index = mediaItem.subtitleIndex
        val streamable = streams[index]
        return withClient(mediaItem) {
            runCatching {
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Subtitle
            }
        }
    }

    private companion object {
        // The whole resolve's budget, shared by [load]'s timeout and [safetyNet]'s remainder.
        const val RESOLVE_BUDGET_MS = 30_000L

        // See [failedNets].
        const val MAX_FAILED_NETS = 3

        // [TEMPORARY 2026-10-27] logSafetyNet's token guard; leaves with the probe.
        val OPAQUE_RUN = Regex("[A-Za-z0-9_+/=-]{40,}")
    }
}