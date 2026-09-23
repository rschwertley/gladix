package dev.brahmkshatriya.echo.common.helpers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Injectable<T>(
    private val getter: () -> T,
    private var injections: List<suspend T.() -> Unit>
) {

    val data = lazy { runCatching { getter() } }
    private val mutex = Mutex()
    val value: T?
        get() = data.value.getOrNull()


    /**
     * ⚠⚠ A THROW FROM [injections] STRANDS EVERYTHING IN [injectionsMap] FOREVER, AND THAT
     * COST A FIELD LOCKOUT (build 1108, 2026-09-23). The two collections drain in order and the
     * first one gates the second: a throw inside `injections.forEach` skips the clear, skips the
     * map drain entirely, and - because `injections` was never emptied - reproduces itself on every
     * later call. The map's entries can then NEVER run.
     * THAT IS NOT SYMMETRICAL WITH WHAT THE LIST ITSELF GETS: the list retries (it is still
     * pending, so a later value() re-runs it and it can self-heal), while the map is simply
     * unreachable behind it. So a failing list entry silently revokes unrelated work that some
     * other subsystem queued through [injectOrRun].
     * WHAT IT COST: ExtensionLoader delivers `setLoginUser` - the only path that hydrates a
     * Deezer session's stored credentials - through injectOrRun("user"), i.e. into the MAP. Deezer's
     * onExtensionSelected briefly threw on the un-hydrated credentials that setLoginUser was about
     * to supply, so the hydration was stranded by the very failure it would have fixed. Fixed in
     * the extension (see DeezerExtension.onExtensionSelected); THIS ORDERING IS UNCHANGED and is
     * still the trap - any injection that can throw has the same reach.
     * ⚠⚠ SCOPED AND SEQUENCED 2026-09-23 - THIS IS THE FIRST OF THE TWO FOLLOW-UPS, AND
     * IT SHIPS ON ITS OWN. The shape: drain injectionsMap even when injections threw, since the two
     * have no dependency on each other. Deliberately NOT bundled with the login-surface split
     * described at LoginViewModel.init - they close different halves and one is much cheaper.
     *   WHY THIS ONE GOES FIRST
     *     - It ALONE would have prevented the 1108 lockout: setLoginUser runs, credentials
     *       hydrate, and the next value() succeeds on its own.
     *     - No call sites change. It is one function here, and it closes the trap for EVERY
     *       extension rather than for the three login-gating reads.
     *     - It needs no decision about what a partially-injected client may be used for, which is
     *       the part of the split that carries real risk.
     *   WHAT IT DOES NOT CLOSE: a failing injection still fails value(), so every capability check
     *     against that extension still reports false. That is the SPLIT's half, not this one.
     * ⚠️ THE ONE THING TO GET RIGHT WHEN BUILDING IT: the list must stay PENDING on
     * failure (that is what lets it self-heal on a later value()) while the map must still be
     * drained and cleared. Draining the map must not be read as "the injection succeeded" - those
     * two pieces of bookkeeping are independent and the current code conflates them by ordering.
     * ⚠️ AND THIS IS `common`: every extension runs through it, so it wants its own build
     * and its own cold start (see the launch rule in CLAUDE.md), not a ride along with something else.
     */
    suspend fun value() = runCatching {
        mutex.withLock {
            val t = data.value.getOrThrow()
            injections.forEach { it(t) }
            injections = emptyList()
            injectionsMap.values.forEach { it(t) }
            injectionsMap.clear()
            t
        }
    }

    private val injectionsMap = mutableMapOf<String, suspend T.() -> Unit>()
    suspend fun injectOrRun(id: String, block: suspend T.() -> Unit) {
        if (data.isInitialized()) data.value.getOrThrow().block()
        else mutex.withLock {
            injectionsMap[id] = block
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <R> casted() = run {
        injections = injections + listOf { this as R }
        this as Injectable<R>
    }
}