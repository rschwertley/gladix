package dev.brahmkshatriya.echo.utils

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.di.App
import java.util.concurrent.ConcurrentHashMap

class HealthMonitor(private val app: App) {

    enum class Scope { PERSISTENT, MEMORY_ONLY }

    /**
     * Base for every HealthMonitor report.
     *
     * Fields are `val`s, not merely interpolated into [message]: the message is a human-readable
     * rendering, and string-parsing it back was previously the ONLY way to recover this data — which
     * breaks silently the first time somebody reformats a message.
     *
     * [extensionId] is the extension the report is ABOUT. report() writes it straight into the
     * `throwing_extension_id` crash key, so attribution comes from the report itself rather than from
     * whatever the last throwFlow-routed error happened to leave on the Crashlytics singleton. null
     * where a report genuinely has no extension (the restore-time integrity checks).
     */
    sealed class HealthException(message: String, val extensionId: String?) : Exception(message)

    class ExtensionResolutionTimeout(extensionId: String, val durationMs: Long) :
        HealthException("extensionId=$extensionId durationMs=$durationMs", extensionId)

    /**
     * Split into concrete families ON PURPOSE — do not collapse it back.
     *
     * Crashlytics groups a non-fatal on the exception CLASS plus the top stack frames, and this had
     * exactly one construction site (PlayerEventListener.reportAndResetConsecutiveSkips), so every trip
     * produced an identical class and byte-identical frames. A 403 storm from one extension and a
     * buffering stall from another landed in ONE issue, and there was no way to mute the expected family
     * without also hiding the one being watched for. The differing MESSAGE does not help: it is the issue
     * subtitle, and subtitles are not part of the grouping key. Neither is a custom key — keys are
     * per-event metadata, while mute/close act on the issue.
     *
     * ⚠️ NAMES ARE DELIBERATELY FLAT, NOT NESTED. `report()` derives both the Crashlytics issue identity
     * and the `health_report_type` key from `javaClass.simpleName`, and for a nested class that returns
     * ONLY the inner name — declaring these as `ConsecutiveSkipException.Stall` would title the issue
     * "Stall" with no context. Keep any future family a sibling with a fully-qualifying name.
     *
     * The family comes from PlayerEventListener's `recentSkipFamily`, computed per skip from the cause
     * and PlaybackException in hand, NOT from string-matching [lastCauses] — see the note on this file's
     * [HealthException] about why parsing the message back is the thing these `val`s exist to prevent.
     *
     * The MESSAGE FORMAT IS UNCHANGED, and must stay that way: [lastCauses] carries the probe fields, and
     * `report()`'s dedupe signature is simpleName + message. The two families already produced different
     * messages (a stall run reads `StuckBuffering loaded=… …`, an error run reads `CODE ext:… Class …`),
     * so prefixing a different class name changes the signature STRING without changing the PARTITIONING —
     * the 10-minute cooldown was already per-family and still is. Scope is MEMORY_ONLY, so the only cost of
     * the renamed signatures is that the first trip after an update reports instead of being suppressed,
     * once. (A PERSISTENT report renamed this way would instead strand dead keys in the prefs forever and
     * restart every cooldown — relevant if ResumptionUtils' or StreamableLoader's reports are ever split.)
     *
     * NOT split on extension-attributability. That sounds like the natural axis and is the wrong one: the
     * `ext:` field in a cause comes from an AppException in the chain, and an HTTP 403 on a STREAM URL is a
     * Media3 `InvalidResponseCodeException` from the datasource carrying no AppException — so an
     * extension-attributable split would not separate a 403 storm from a stall at all. The error family
     * was subdivided on 2026-09-03 — on Media3 error codes and types we own, for the reason below.
     */
    sealed class ConsecutiveSkipException(
        val skipCount: Int, val lastExtensionId: String, val lastCauses: String
    ) : HealthException(
        "skipCount=$skipCount lastExtensionId=$lastExtensionId lastCauses=$lastCauses", lastExtensionId
    )

    /**
     * ⚠️ THE ERROR FAMILIES BELOW EXIST BECAUSE CRASHLYTICS MUTE IS PER-ISSUE. One class per DECISION,
     * never one per cause: if two families would always be triaged the same way they must be merged. The
     * six real causes seen so far collapse to three outcomes — watch the network ones (they neighbour the
     * buffering-stall investigation), watch our own guards, mute everything a third party refused.
     *
     * ⚠️ CLASSIFIED FROM TYPES WE OWN OR THAT ANDROID OWNS — NEVER FROM A THIRD-PARTY CLASS NAME OR
     * MESSAGE STRING. We know the built-in extensions; we do not know the third-party universe, and a
     * rule keyed on "TrackUnavailableException" or on message text works until somebody installs an
     * extension nobody here has read. See PlayerEventListener.skipFamilyOf for what that leaves
     * classifiable and what deliberately falls to the residual bucket.
     */
    /** Every skip in the run came from the buffering watchdog — `recordSkip(null)`, no error object. */
    class ConsecutiveSkipStallException(
        skipCount: Int, lastExtensionId: String, lastCauses: String
    ) : ConsecutiveSkipException(skipCount, lastExtensionId, lastCauses)

    /**
     * A timeout or connection failure. WATCHED: this neighbours the buffering-stall park, and a run that
     * times out is the closest thing to it that carries a throwable.
     */
    class ConsecutiveSkipNetworkException(
        skipCount: Int, lastExtensionId: String, lastCauses: String
    ) : ConsecutiveSkipException(skipCount, lastExtensionId, lastCauses)

    /**
     * A third party refused, or the content or its extension is gone. MUTABLE: nothing here is
     * actionable from this side — a broken scraper, an expired token, a queue restored against an
     * extension the user has since uninstalled.
     */
    class ConsecutiveSkipUnavailableException(
        skipCount: Int, lastExtensionId: String, lastCauses: String
    ) : ConsecutiveSkipException(skipCount, lastExtensionId, lastCauses)

    /**
     * One of OUR OWN named guards fired. WATCHED.
     *
     * ⚠️ NAMED TYPES ONLY — NEVER A BASE CLASS. Routing `is IllegalStateException` here would fill the one
     * bucket that is actually read with every unrelated app-side throw, which is the state this split
     * exists to escape. A guard earns a place here by being given a name (see WrongItemException);
     * anything anonymous belongs in the residual family, where an unexpected cause is visible as such.
     */
    class ConsecutiveSkipInternalException(
        skipCount: Int, lastExtensionId: String, lastCauses: String
    ) : ConsecutiveSkipException(skipCount, lastExtensionId, lastCauses)

    /**
     * RESIDUAL. A throwable that none of the families above recognised — which is signal, not noise: it
     * means a cause the split did not anticipate, most likely from an extension nobody here has read.
     * Deliberately keeps its original name so the existing Crashlytics issue is not orphaned.
     */
    class ConsecutiveSkipErrorException(
        skipCount: Int, lastExtensionId: String, lastCauses: String
    ) : ConsecutiveSkipException(skipCount, lastExtensionId, lastCauses)

    class OrphanedSessionException(val savedTrackCount: Int, val firstTrackId: String) :
        HealthException("savedTrackCount=$savedTrackCount firstTrackId=$firstTrackId", null)

    // Tripwire for the resumption fix: on restore, the track at the saved index must be the track
    // that was current at save time. Fires only if a future change re-poisons the persisted index
    // with a non-full-basis value (e.g. a windowed index). Diagnostic only — restore still proceeds.
    /**
     * The saved INDEX pointed at a track whose id is not the saved CURRENT_ID.
     *
     * ⚠️ `found` IS THE SEVERITY, NOT A DETAIL — read it first. This fires PRE-HEAL (see the report site in
     * ResumptionUtils.recoverQueue for why), so a mismatch that resolveCurrentIndex silently corrected and
     * one that left the user on a different track both arrive here and used to be indistinguishable:
     *   found = true  -> the saved current track IS in the restored list, at resolvedIndex. The stale index
     *                    was corrected by id and the restore is CORRECT. Cosmetic.
     *   found = false -> the saved current track is ABSENT. resolveCurrentIndex fell back to the stale
     *                    index, so the user resumes on a DIFFERENT TRACK.
     * The two are reported at different Scopes for exactly that reason.
     *
     * The message carries both indices and healed=, so the dedupe signature (class + message) separates
     * them. That re-partitions this signature against pre-2026-09-07 reports — deliberately: the counts
     * being merged was the defect.
     */
    class ResumeIndexMismatchException(
        val expectedId: String,
        val actualId: String,
        val index: Int,
        val size: Int,
        val resolvedIndex: Int,
        val found: Boolean,
    ) : HealthException(
        "resume index/id mismatch: index=$index resolved=$resolvedIndex size=$size healed=$found " +
            "expected=$expectedId actual=$actualId",
        null
    )

    // Benign teardown race: a media3 datasource raised a bare IllegalStateException from one of its
    // checkState() lifecycle assertions because the player/cache was torn down while a load was still
    // closing. Suppressed at the player layer (PlayerEventListener.onPlayerError) so it never reaches the
    // user; reported here rate-limited so the FREQUENCY is still visible. The original ISE is attached as
    // the cause, so its retraceable close-cascade stack survives.
    // The message is deliberately CONSTANT: report()'s dedupe signature is simpleName + message, so a
    // varying message (track id, extension) would defeat the cooldown and turn this back into spam.
    class DataSourceTeardownRaceException(cause: Throwable) : HealthException(
        "IllegalStateException during media3 datasource close/teardown", null
    ) {
        init { initCause(cause) }
    }

    private val prefs = app.context.getSharedPreferences("gladix_health_monitor", Context.MODE_PRIVATE)
    private val memoryTimestamps = ConcurrentHashMap<String, Long>()

    fun report(exception: HealthException, scope: Scope, cooldownMs: Long) {
        val signature = "${exception.javaClass.simpleName}_${exception.message}"
        val now = System.currentTimeMillis()
        val lastReported = when (scope) {
            Scope.PERSISTENT -> prefs.getLong(signature, 0L)
            Scope.MEMORY_ONLY -> memoryTimestamps[signature] ?: 0L
        }
        if (now - lastReported < cooldownMs) return
        when (scope) {
            Scope.PERSISTENT -> prefs.edit { putLong(signature, now) }
            Scope.MEMORY_ONLY -> memoryTimestamps[signature] = now
        }
        // ⚠⚠ DROP THE SUPERTYPES' <init> FRAMES, KEEP THE MOST-DERIVED ONE. THIS IS WHAT MAKES THE
        // FAMILY SPLIT ACTUALLY REACH CRASHLYTICS. The 2026-09 split into ConsecutiveSkipStall/Network/
        // Unavailable/Internal/Error produced five classes and ONE issue anyway: construction runs
        // HealthException.<init> -> ConsecutiveSkipException.<init> -> <Family>Exception.<init>, so the TOP
        // frame was a base <init> shared by all five. Observed as Crashlytics issue 17f8c8,
        // "HealthMonitor$ConsecutiveSkipException.<init>", 21 events / 10 users, holding the subclasses as
        // VARIANTS - so per-family mute, the entire point of the split, was impossible.
        // ⚠️ TRIMMING ALL <init> FRAMES IS THE OBVIOUS FIX AND IT DOES NOT WORK. The next frame is
        // reportAndResetConsecutiveSkips, which is ALSO common to all five families - it swaps one shared
        // top frame for another. Only the most-derived <init> differs per family, so that is the one to
        // keep, and everything above it is what gets dropped.
        // ⚠️ THIS CHANGES THE STACK, NOT THE SIGNATURE, and the distinction is load-bearing: the
        // note above warns that renaming strands PERSISTENT prefs keys and restarts every cooldown.
        // `signature` is simpleName + message and is computed before this line, so dedupe and all
        // cooldowns are untouched. Do not "simplify" this by rebuilding the exception instead.
        // ⚠️ TWO HONEST LIMITS. Crashlytics' grouping is theirs: this changes the INPUT it keys on,
        // the rest is their call. And 17f8c8 does NOT retroactively split - new events group fresh and the
        // old issue stays as history. `health_report_type` already allowed FILTERING by family; it is
        // MUTING that needed this.
        exception.stackTrace.let { trace ->
            var i = 0
            while (i + 1 < trace.size && trace[i + 1].methodName == "<init>") i++
            if (i > 0) exception.stackTrace = trace.copyOfRange(i, trace.size)
        }
        // BuildConfig.HAS_FIREBASE is a compile-time boolean (no Firebase type referenced), so in
        // no-json builds this branch is dead and FirebaseCrashlytics is never loaded.
        //
        // ALWAYS WRITE, NEVER OMIT. Crashlytics keys are sticky on the singleton, so a key this path
        // skips silently keeps whatever the previous report left there — indistinguishable from a value
        // written on purpose. This path deliberately does NOT go through App.throwFlow (that would put a
        // snackbar in front of the user for every breaker trip), so nothing else populates these keys for
        // a HealthMonitor report. `throwing_extension_id` was the worst of them: it named a DIFFERENT
        // exception entirely, and only 1 of the 7 breaker trip sites happened to have a throwFlow emit
        // immediately before it — so it was wrong far more often than right (the 2026-08-19 "deezer"
        // attribution was correct by coincidence, and a triage decision was made on it).
        //
        // extension_id / player_state / is_playing are LIVE @Volatile snapshots maintained by
        // PlayerService, so copying them here is correct, not merely fresher. throwing_extension_id comes
        // from the exception's OWN identity — never a cause-chain walk, which would find nothing: these
        // exceptions carry no cause.
        if (BuildConfig.HAS_FIREBASE) FirebaseCrashlytics.getInstance().apply {
            setCustomKey("health_report_type", exception.javaClass.simpleName)
            setCustomKey("throwing_extension_id", exception.extensionId ?: "none")
            setCustomKey("extension_id", app.crashExtensionId)
            setCustomKey("player_state", app.crashPlayerState)
            setCustomKey("is_playing", app.crashIsPlaying)
            // ⚠⚠ ALWAYS "none" HERE, AND THAT IS THE CORRECT VALUE RATHER THAN A PLACEHOLDER.
            // deezer_gateway is written by App.kt's collector from a cause-chain walk for a
            // DeezerGatewayException. The exceptions THIS path records carry no cause at all - stated a
            // few lines above, and the same fact that makes throwing_extension_id read the exception's
            // own identity instead of walking - so a walk here could only ever return null.
            // WHY IT IS WRITTEN AT ALL: it is a SNAPSHOT key (CrashKeys doc note 4), and this path
            // deliberately does not go through App.throwFlow, so nothing else populates it for a
            // HealthMonitor report. Omitted, it would keep the LAST throwFlow report's gateway refusal
            // and attach a Deezer error to an unrelated breaker trip - exactly the
            // throwing_extension_id defect recorded above, on a new key.
            setCustomKey("deezer_gateway", "none")
            // Twin of the call in App.kt's throwFlow collector, and it must stay a twin. report_age_s is
            // the only key carrying the instant a report was RECORDED (every age_s_* is the age at its own
            // checkpoint; process_age_s is first-write). A key present on one recording path and absent on
            // the other is worse than one that exists nowhere: on a HealthMonitor report the absence would
            // read as a value — "this arrived at an unknown time" is indistinguishable from "this key
            // isn't set on this build" once both kinds of report sit in the same issue list.
            CrashKeys.onReportRecorded()
            recordException(exception)
        }
    }
}
