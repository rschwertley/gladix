package dev.brahmkshatriya.echo.extensions

import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import kotlinx.serialization.Serializable

@Serializable
sealed interface MediaState<T : EchoMediaItem> {
    val extensionId: String
    val item: T
    val loaded: Boolean

    @Serializable
    // No copy(): a new field must be mirrored in Cached.setTrackLiked and Cached.withCallerVerdict,
    // which both rebuild this by hand.
    class Loaded<T : EchoMediaItem>(
        override val extensionId: String,
        override val item: T,
        val isFollowed: Boolean?,
        val followers: Long?,
        val isSaved: Boolean?,
        val isLiked: Boolean?,
        val isHidden: Boolean?,
        val showRadio: Boolean,
        val showShare: Boolean,
        /**
         * ⚠⚠ "AT LEAST ONE OF THE FIVE NULLS ABOVE MEANS 'WE COULD NOT FIND
         * OUT', NOT 'THE ITEM HAS NO SUCH CAPABILITY'." Set by Cached.loadMedia when a probe
         * failed and was degraded (see Cached.probeOrNull), and read by loadMedia's preferCache
         * arm, which treats such an entry as a MISS so the next load probes again.
         * ⚠️ WITHOUT IT, ONE TRANSIENT REFUSAL WAS PERMANENT. The degraded
         * state is cached like any other, and on the playback path a cache hit SKIPS all five
         * probes - so a single failed isItemLiked left that track's heart unfilled for good,
         * which is the 2026-10-23 stale-heart class arriving from the other direction.
         * ⚠️ DEFAULTED, PER THE RULE AT THE TOP OF Cached.kt: entries written
         * by earlier builds decode with false, which is what they meant - those states were only
         * ever written when every probe answered, because a failure failed the whole load.
         */
        val probesDegraded: Boolean = false,
    ) : MediaState<T> {
        override val loaded = true
    }

    @Serializable
    data class Unloaded<T : EchoMediaItem>(
        override val extensionId: String,
        override val item: T
    ) : MediaState<T> {
        override val loaded = false
    }
}