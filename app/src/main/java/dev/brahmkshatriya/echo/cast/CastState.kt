package dev.brahmkshatriya.echo.cast

import dev.brahmkshatriya.echo.common.models.Streamable

/**
 * The one flag that says whether a Cast session is live, plus the two derived rules that have to agree
 * across the app.
 *
 * ⚠⚠ A PROCESS-WIDE OBJECT RATHER THAN A FIELD ON PlayerState, AND THAT IS NOT LAZINESS.
 * PlayerState is the established home for exactly this kind of flag (isPostDisconnect, destroying,
 * resumptionApplying) and would be the first choice - but it is INJECTED, and three of the five places
 * that must agree about casting cannot see it: ShufflePlayer takes only an ExoPlayer,
 * PlayerService.selectServerIndex is a companion function whose widest argument is App, and
 * CrossfadeController takes neither. Threading PlayerState into all three to carry one boolean is a
 * larger change than this object, in the files with the least headroom for one.
 *
 * ⚠️ WRITTEN ONLY BY CastSessionController, ON THE MAIN THREAD, AT TWO POINTS: session start and
 * session end. Read from loader threads (selectServerIndex runs inside MediaItemUtils.toMetaData on
 * whatever thread built the queue) and from the app looper, hence @Volatile. A stale read is bounded
 * and benign in both directions - one track picked at the wrong quality tier, or one crossfade armed
 * that the abort then tears down.
 *
 * ⚠️ IT SURVIVES NOTHING. Process death clears it, which is correct: a Cast session cannot
 * outlive the process that owns the RemoteCastPlayer, so there is no state here worth persisting and
 * no migration to get wrong.
 */
object CastState {

    @Volatile
    var isCasting: Boolean = false
        private set

    /** Set by CastSessionController only. Main thread, session start/end. */
    fun setCasting(casting: Boolean) {
        isCasting = casting
    }

    /**
     * ⚠⚠ [DECIDED 2026-10-17] THERE IS NO QUALITY CAP WHILE CASTING, AND THIS LIST IS
     * ONLY FOR CHOOSING THE MIME. An earlier version of this file capped casting at 320 on the
     * premise that older Chromecasts cannot decode FLAC. That was dropped: Echo Expressive casts
     * FLAC successfully by declaring it as audio/flac, so the receiver handles lossless when it is
     * told what it is being given. Casting therefore uses whatever PlayerService.selectServerIndex
     * picks for local playback, and that function is deliberately UNTOUCHED by Cast - there is no
     * cast-aware branch in it to go wrong when nobody is casting.
     * ⚠️ AND THERE IS NO FALLBACK. If a receiver refuses the format it fails the load,
     * which surfaces through RemoteCastPlayer's onPlayerError and stops the session with a message
     * (CastSessionController.remoteListener). Stepping the quality down and retrying is a deliberate
     * non-goal for now; if a device turns up that needs it, this is where the decision changes.
     */
    private val LOSSLESS_WORDS = listOf("flac", "alac", "lossless", "hi-res", "hires", "wav", "aiff")

    /**
     * Content type for the receiver. The MIME has to be declared up front -
     * DefaultMediaItemConverter reads it off `localConfiguration.mimeType` (1.11.0, :180) and the
     * receiver decides whether it can play the item from that alone - but nothing in [Streamable]
     * carries a container type, so this is derived from the same title/id text as the cap.
     *
     * ⚠️ audio/mpeg IS THE DEFAULT, AND GETTING IT WRONG FAILS THE LOAD RATHER THAN
     * PLAYING THE WRONG THING - which is the right direction for a heuristic. FLAC must be named
     * correctly or lossless casting breaks (see the note above on why there is no cap), so the
     * lossless words are checked first; Opus and AAC follow; anything unrecognised is MP3, which is
     * what every tier of every extension observed so far actually is.
     */
    fun mimeFor(streamable: Streamable): String {
        val haystack = (streamable.title.orEmpty() + " " + streamable.id).lowercase()
        return when {
            LOSSLESS_WORDS.any { haystack.contains(it) } -> "audio/flac"
            haystack.contains("opus") || haystack.contains("ogg") -> "audio/ogg"
            haystack.contains("aac") || haystack.contains("m4a") || haystack.contains("mp4") ->
                "audio/mp4"
            else -> "audio/mpeg"
        }
    }
}
