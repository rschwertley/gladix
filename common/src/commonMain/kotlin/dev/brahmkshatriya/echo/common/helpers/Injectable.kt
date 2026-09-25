package dev.brahmkshatriya.echo.common.helpers

import kotlin.coroutines.cancellation.CancellationException
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
            try {
                injections.forEach { it(t) }
                // ⚠⚠ STILL AFTER THE forEach, AND STILL NOT IN THE finally. A failing list
                // entry MUST leave `injections` pending so a later value() re-runs it - that
                // self-healing is the behaviour the 2026-09-22 rethrow relied on, and the fix below
                // deliberately does not touch it.
                injections = emptyList()
                t
            } finally {
                // ⚠⚠ DRAINED IN A finally SO THE LIST FAILING CANNOT STRAND IT - THIS IS THE
                // FIX (2026-09-23), and it is the whole of it. Before this, a throw from the list
                // skipped both this drain and the clear, so entries queued through injectOrRun could
                // NEVER run: ExtensionLoader delivers `setLoginUser` here, so a Deezer session's
                // credential hydration was stranded by an unrelated injection failure. See the
                // history above.
                // ⚠️ value() STILL FAILS WHEN THE LIST FAILED. The exception propagates
                // through this finally untouched, so isSuccess is unchanged for that case and
                // ExtensionUtils.isClient keeps FAILING CLOSED on a failed activation. Only the side
                // effect is new. Do not convert this into a catch.
                // ⚠️ PER-ENTRY ISOLATION, AND IT CHANGES ONE CASE DELIBERATELY: a throwing MAP
                // entry no longer fails value() and no longer blocks the clear. Previously it threw
                // on every call forever (the clear was unreachable), which made the extension
                // permanently uninjectable - the exact shape that locked users out of Deezer in
                // build 1108. A login-hydration failure must not be able to remove an extension's
                // capabilities; it should fail visibly at the point of use instead.
                // ⚠️ NOTHING IS REPORTED FROM HERE, BY DESIGN. Reporting would need a flow
                // this class has no reference to, and adding a constructor parameter would change
                // :common's public ABI - which third-party extensions link against without bundling.
                // The report is done AT THE QUEUING SITE instead: see ExtensionLoader's
                // injectOrRun("user") block, which catches its own failure and emits to throwFlow.
                // A new injectOrRun caller that wants reporting must do the same - this catch is a
                // belt, not the reporter.
                // ⚠️ CancellationException IS RETHROWN, so a cancelled drain leaves the map
                // PENDING and unclear - correct, because nothing ran and it must still be able to.
                // That is the one path where "drained regardless" does not hold, and it matches the
                // pre-fix behaviour exactly.
                injectionsMap.values.forEach { injection ->
                    try {
                        injection(t)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Isolated on purpose; reported by the caller that queued it.
                    }
                }
                injectionsMap.clear()
            }
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