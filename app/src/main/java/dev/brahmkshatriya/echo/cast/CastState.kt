package dev.brahmkshatriya.echo.cast

/**
 * The one flag that says whether a Cast session is live.
 *
 * ⚠⚠ A PROCESS-WIDE OBJECT RATHER THAN A FIELD ON PlayerState, AND THAT IS NOT LAZINESS.
 * PlayerState is the established home for exactly this kind of flag (isPostDisconnect, destroying,
 * resumptionApplying) and would be the first choice - but it is INJECTED, and the places that must
 * agree about casting cannot see it: ShufflePlayer takes only an ExoPlayer, CrossfadeController takes
 * neither, and PlayerEventListener's gates sit in a class whose constructor is already at its limit.
 * Threading PlayerState into all three to carry one boolean is a larger change than this object, in
 * the files with the least headroom for one.
 *
 * ⚠️ WRITTEN ONLY BY CastSessionController, ON THE MAIN THREAD, AT TWO POINTS: session start and
 * session end. Read from the app looper and from loader threads, hence @Volatile. A stale read is
 * bounded and benign in both directions - one crossfade armed that the abort then tears down, or one
 * refusal that the next tick reverses.
 *
 * ⚠️ IT SURVIVES NOTHING. Process death clears it, which is correct: a Cast session cannot
 * outlive the process that owns the RemoteCastPlayer, so there is no state here worth persisting and
 * no migration to get wrong.
 *
 * ⚠️ THE MIME DERIVATION USED TO LIVE HERE AND NOW LIVES IN [CastPlan]. It belongs beside the
 * routing decision because both answer the same question about the same source, and because the
 * ladder needs the source itself - not just its label - to be correct for anything but Deezer.
 */
object CastState {

    @Volatile
    var isCasting: Boolean = false
        private set

    /** Set by CastSessionController only. Main thread, session start/end. */
    fun setCasting(casting: Boolean) {
        isCasting = casting
    }
}
