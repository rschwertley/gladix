package dev.brahmkshatriya.echo.playback

import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

data class PlayerState(
    val current: MutableStateFlow<Current?> = MutableStateFlow(null),
    val radio: MutableStateFlow<Radio> = MutableStateFlow(Radio.Empty),
    val session: MutableStateFlow<Int> = MutableStateFlow(0)
) {

    val servers: MutableMap<String, Result<Streamable.Media.Server>> =
        Collections.synchronizedMap(LinkedHashMap())
    val serverChanged = MutableSharedFlow<Unit>(replay = 1)
    val activeLoadCount = AtomicInteger(0)

    // Single cold-start restore: the queue is read from disk ONCE at service creation
    // (PlayerService.onCreate) into this Deferred, and shared by every consumer — the app-open apply
    // (applyRestoreIfCold), resume(), and onPlaybackResumption — so no path runs its own recoverPlaylist
    // and races another. A null payload means the disk was empty.
    var restoreDeferred: Deferred<RestoreData?>? = null

    // Set true SYNCHRONOUSLY when onPlaybackResumption is invoked (media-button / system resume) so the
    // app-open applyRestoreIfCold defers while the framework is about to apply the same queue — a second
    // setMediaItems tears the timeline down and re-prepares. Plain var, NOT AtomicBoolean: read and
    // written ONLY on the player's application looper — which is Main, because the player is built on Main
    // in PlayerService.onCreate (ExoPlayer.Builder defaults its looper to the current thread). It is set
    // in onPlaybackResumption's synchronous body (Media3 invokes that callback on that looper), read in
    // applyRestoreIfCold's withContext(Main), and cleared on Main by the timeline listener (success) and
    // a withContext(Main) in the non-return paths (failure). If the player is ever built off-Main this
    // invariant breaks and this must become atomic.
    var resumptionApplying = false

    data class Current(
        val index: Int,
        val mediaItem: MediaItem,
        val isLoaded: Boolean,
        val isPlaying: Boolean,
        val isPlaceholder: Boolean = false,
    ) {

        val context by lazy { mediaItem.context }
        val track by lazy { mediaItem.track }
        fun isPlaying(id: String?): Boolean {
            val same = mediaItem.mediaId == id
                    || context?.id == id
                    || track.album?.id == id
                    || track.artists.any { it.id == id }
            return isPlaying && same
        }

        companion object {
            fun Current?.isPlaying(id: String?): Boolean = this?.isPlaying(id) ?: false
        }
    }

    sealed class Radio {
        data object Empty : Radio()
        data object Loading : Radio()
        data class Loaded(
            val clientId: String,
            val context: EchoMediaItem,
            val cont: String?,
            val tracks: suspend (String?) -> Page<Track>?
        ) : Radio()
    }
}

// The shared cold-start restore snapshot (see PlayerState.restoreDeferred). One IO read fills it; the
// app-open apply uses items raw, onPlaybackResumption maps them through withUnloaded for the framework.
data class RestoreData(
    val items: List<MediaItem>,
    val index: Int,
    val pos: Long,
    val shuffle: Boolean,
    val repeat: Int,
)
