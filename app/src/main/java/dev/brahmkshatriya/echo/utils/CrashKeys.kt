package dev.brahmkshatriya.echo.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Crashlytics custom keys set at natural checkpoints during NORMAL operation (never in a crash handler), so an
 * UNCAUGHT fatal — e.g. OutOfMemoryError, which never reaches the throwFlow recorder in App — still carries
 * them on the Crashlytics singleton when the default uncaught handler reports it.
 *
 * Constraints honored:
 * - primitives only (Int/String/Boolean), no allocation beyond the key value itself;
 * - every write is HAS_FIREBASE-guarded (compile-time const → the branch is dead-stripped and
 *   FirebaseCrashlytics is never referenced in the no-Firebase/F-Droid variant) and runCatching-wrapped
 *   (a not-yet-initialized Crashlytics can never throw).
 *
 * ── Key semantics, learned the hard way on the build-1033 OOM ────────────────────────────────────────────
 * Crashlytics keys are LAST-WRITE-WINS. Three corrections came out of that report, plus one from the
 * 2026-08-20 HealthMonitor attribution bug:
 *
 * 1. COUNTS ARE MONOTONIC, GAUGES ARE NOT. The old `remote_controller_count` incremented on connect and
 *    DECREMENTED on disconnect, i.e. it was a gauge, not the monotonic counter this doc used to claim. That
 *    made it unreadable: Media3 calls onConnect unconditionally from
 *    MediaSessionServiceLegacyStub.onGetRoot (no existing-controller check) while ConnectedControllersManager
 *    .addController de-dupes on RemoteUserInfo and never removes the earlier record — so a client that
 *    reconnects in a loop produces N increments and ZERO decrements. The gauge drifts upward by construction
 *    and cannot be read as "how many are connected". Connects and disconnects are now separate monotonic
 *    counters, so churn and residency are distinguishable.
 *
 * 2. ONE SHARED AGE KEY CANNOT ATTRIBUTE. `process_age_s` used to be stamped by every checkpoint, so with
 *    ~1120 controller connects its final value was the age at the last CONNECT — not, as it read, the age at
 *    service create. Every checkpoint now stamps its OWN age key. `process_age_s` is kept, but is explicitly
 *    "age at the most recent checkpoint of any kind" and must not be read as belonging to any one of them.
 *
 * 3. LAST-WRITE HEAP SAMPLES CANNOT SHOW A TRAJECTORY. Every heap key read 255 used / 0 headroom (the
 *    headroom half is heap_limit_mb since build 1114 - see sampleHeap), which is near-tautological
 *    once the heap is full and events keep firing — it could not distinguish "born high" from "climbed to
 *    full and stayed". A first-ever sample and a running max are now recorded alongside, giving three points
 *    (first → peak → last) instead of one.
 *
 * 4. WHETHER OMITTING A WRITE IS SAFE DEPENDS ON THE KEY'S KIND. Because keys are last-write-wins, skipping a
 *    write leaves the previous value in place. That is CORRECT for a monotonic ACCUMULATOR (heap_peak_mb is
 *    only written when the peak advances — not writing means "no new peak", and the retained value is still
 *    true). It is a LIE for a SNAPSHOT (extension_id, player_state, throwing_extension_id describe one report;
 *    an unwritten one silently describes a different, earlier one — this is exactly how HealthMonitor reports
 *    carried another exception's attribution until 2026-08-20). Test before adding a key: if this write is
 *    skipped, is the value left behind still true? Accumulator -> yes, omit freely. Snapshot -> no, ALWAYS
 *    write, with an explicit "none" where there is nothing to say.
 *
 * 5. LAST-WRITE CANNOT DISTINGUISH ONE LATE EVENT FROM A STORM. Every checkpoint key is last-write, so a
 *    checkpoint that fires 1000 times reports occurrence 1000 and nothing else. Build 1039 (2026-08-23)
 *    had service_create_count ~1050 in ~60s, and age_s_svc / age_s_conn / age_s_build / process_age_s all
 *    read equal to the process age — not because they fired together, but because each fired LAST at the
 *    moment of death. Every heap sample was likewise a dying-end reading. Each checkpoint now also writes
 *    a CAS-guarded <key>_first (and <heapKey>_first), so first + existing count + last reads as a rate.
 *    READ _first WHEN ATTRIBUTING; read the bare key only as "state at the end".
 *
 * Note on hotness: onControllerConnected is NOT rate-limited and, under the connect storm this instrumentation
 * exists to diagnose, can fire several times a second. Its writes are a handful of map puts with no allocation
 * beyond the values, so this is acceptable — but do not add anything expensive to that path.
 *
 * ⚠⚠ EVERY HEAP SAMPLE IN EVERY REPORT IS FROM THE DYING END - EXCEPT heap_first_mb. This is the
 * general statement that notes 2 and 3 above are instances of, and it is the one to carry: stampAge
 * re-stamps on EVERY iteration, so a checkpoint that fires in a loop reports its LAST occurrence. That
 * is why age_s_build == age_s_conn == age_s_svc == process_age_s does NOT mean they fired together - it
 * means each fired last, at the dying end. heap_used_mb_* is sampled at those same checkpoints and
 * inherits it. ONLY heap_first_mb is CAS-guarded (heapFirstRecorded), which is what makes a low value
 * there - 18-22MB in the August reports - genuinely mean "the process started small".
 * heap_limit_mb is exempt by NATURE rather than by a guard: maxMemory does not move, so its dying-end
 * value and its first value are the same number.
 *
 * ⚠️ THE PRACTICAL COROLLARY: READ heap_peak_mb AGAINST heap_limit_mb BEFORE TREATING IT AS A
 * MEASUREMENT. peak 255 on a device whose heap_limit_mb IS 256 means "the heap was full" -
 * near-tautological, NOT a quantity to account for. A 2026-09-22 ANR showed heap_peak_mb 255 on a
 * device whose limit was >= ~339MB, and there 255 is a genuine high-water mark. SAME NUMBER, OPPOSITE
 * EVIDENTIAL VALUE. The check works precisely BECAUSE of the dying-end property above: a saturated
 * sample tells you the ceiling, not the allocation.
 * ⚠⚠ [AMENDED 2026-10-03] THIS TEST USED TO READ "READ headroom", AND THE heap_headroom_mb_*
 * KEYS NO LONGER EXIST - see the key-budget note at sampleHeap. THE TEST IS UNCHANGED IN SUBSTANCE:
 * headroom was only ever maxMemory - used at one checkpoint, and the ceiling is a PROCESS CONSTANT, so
 * comparing the peak to heap_limit_mb asks the identical question AND no longer depends on which
 * checkpoint happened to fire. A report written before build 1114 carries headroom and no limit: add
 * heap_used_mb_<suffix> + heap_headroom_mb_<suffix> to recover it.
 *
 * ⚠⚠ AND THE AUGUST 255/0 CLUSTER IS NOT OPEN - IT WAS ROOT-CAUSED IN A LATER SESSION, WHICH THE
 * SESSION THAT RAN THE HUNT COULD NOT KNOW. Anyone landing on that elimination table will read it as
 * an unsolved allocation mystery. IT WAS NOT AN ALLOCATION PROBLEM AT ALL: ~1050 service creations in
 * 42-73 seconds (14-25 per second), each rebuilding the 81-item queue from disk - roughly 85,000
 * MediaItem constructions in a minute. THE HEAP DID NOT LEAK; GC COULD NOT KEEP UP. The OOM was the
 * CONSEQUENCE and the restart loop was the BUG. So the table's per-candidate MB figures were answering
 * the wrong question - none of them could have summed to a ceiling reading.
 * Do not use that 255 as a target to explain, and do not relate a later 255 to it.
 *
 * Heap note: "used" is totalMemory - freeMemory and is sampled WITHOUT forcing a GC, so it includes garbage
 * not yet collected and can overstate live data under a high allocation rate. heap_limit_mb is maxMemory,
 * i.e. the growth ceiling — NOT Runtime.freeMemory (free-within-committed, which is misleading near OOM).
 * Headroom at any sample is heap_limit_mb minus that sample's used value, derived rather than stored.
 */
object CrashKeys {

    @Volatile private var processStartElapsedMs = 0L
    private val extensionSwitches = AtomicInteger(0)
    private val feedLoads = AtomicInteger(0)

    /**
     * ⚠️ TRACE SUPPORT — REMOVE WITH THE SCROLLER INVESTIGATION. Read-only view of the same counter
     * `feed_load_count` reports, so the GladixScroll line can say whether a FeedData reload happened
     * between two gesture-start samples. If the item count moves while this does NOT, the reload path is
     * not responsible and the movement has another source.
     */
    fun feedLoadCount() = feedLoads.get()
    // Split from the old remote_controller_count gauge (see doc note 1). Both monotonic: their DIFFERENCE is
    // the old gauge, their RATIO is the churn signal the gauge hid.
    private val controllerConnects = AtomicInteger(0)
    private val controllerDisconnects = AtomicInteger(0)
    // Monotonic. >1 means PlayerService was destroyed and recreated within one process — the kill/rebind loop
    // that would explain a late, already-full heap_used_mb_svc sample.
    private val serviceCreates = AtomicInteger(0)

    private val heapFirstRecorded = AtomicBoolean(false)
    // Gates the one-shot heap_limit_mb write. A separate flag rather than reusing heapFirstRecorded,
    // which also gates heap_first_at_age_s: folding them would mean a future change to either write
    // silently changing when the other fires.
    private val heapLimitRecorded = AtomicBoolean(false)
    private val heapPeakMb = AtomicInteger(0)

    private fun set(key: String, value: Int) {
        if (BuildConfig.HAS_FIREBASE) runCatching { FirebaseCrashlytics.getInstance().setCustomKey(key, value) }
    }

    private fun set(key: String, value: String) {
        if (BuildConfig.HAS_FIREBASE) runCatching { FirebaseCrashlytics.getInstance().setCustomKey(key, value) }
    }

    private fun set(key: String, value: Boolean) {
        if (BuildConfig.HAS_FIREBASE) runCatching { FirebaseCrashlytics.getInstance().setCustomKey(key, value) }
    }

    /** Recorded once at process birth (MainApplication.onCreate). elapsedRealtime is monotonic + alloc-free. */
    fun markProcessStart() {
        processStartElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * Install source, recorded ONCE at process birth. It cannot change during a process, so this is
     * set-and-forget rather than a per-report computation.
     *
     * Records PACKAGE NAMES, not a boolean: "com.android.vending" vs a browser package vs "none" is what
     * makes a report triageable, where true/false could not tell a Play install from a sideload.
     *
     * BOTH names are recorded because AppUpdater.isStoreInstall() matches on EITHER, so storing one would
     * leave the gate's own input half-visible. initiatingPackageName is system-verified and is the primary
     * triage value; installingPackageName is reassignable by the installer via setInstallerPackageName, but
     * is the only value available below API 30.
     *
     * Cost: one PackageManager binder call, same class as the getPackageInfo calls made elsewhere. Runs on
     * the main thread at onCreate so it is attached to crashes from startup onward; doing it off-thread
     * would race the very reports it exists to label.
     *
     * Direct Boot caveat: on a pre-unlock spawn Crashlytics may not be ready, in which case the guarded
     * writes are swallowed and the key is simply absent for that (rare) process.
     */
    fun recordInstallSource(context: Context) {
        runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(context.packageName)
                set("install_source", info.initiatingPackageName ?: "none")
                set("install_source_installer", info.installingPackageName ?: "none")
            } else {
                @Suppress("DEPRECATION")
                val installer = pm.getInstallerPackageName(context.packageName)
                set("install_source", "api<30")
                set("install_source_installer", installer ?: "none")
            }
        }
    }

    private fun ageS(): Int {
        val start = processStartElapsedMs
        return if (start == 0L) -1 else ((SystemClock.elapsedRealtime() - start) / 1000L).toInt()
    }

    // Keys whose FIRST value has already been written. A checkpoint that fires N times leaves its
    // last-write key showing occurrence N; the _first companion pins occurrence 1, and the existing
    // counters give N — so first + count + last is readable as a RATE. Without the first-write, a
    // storm is indistinguishable from a single late event (see doc note 5).
    private val firstStamped = ConcurrentHashMap.newKeySet<String>()
    private val processAgeStamped = AtomicBoolean(false)

    // Writes THREE things per checkpoint:
    //  • <key>        — last write, name unchanged so existing Crashlytics issues stay comparable;
    //  • <key>_first  — CAS-guarded first occurrence, the one that attributes;
    //  • process_age_s — ONCE, at the first checkpoint of any kind.
    // process_age_s used to be re-stamped by every checkpoint, so under a storm it reported the age at
    // the LAST one — i.e. the moment of death — while reading like "how old was the process". That
    // silently misled the build-1039 OOM triage. It is now first-write and means what it says.
    private fun stampAge(checkpointKey: String) {
        val age = ageS()
        set(checkpointKey, age)
        if (firstStamped.add(checkpointKey)) set("${checkpointKey}_first", age)
        if (processAgeStamped.compareAndSet(false, true)) set("process_age_s", age)
    }

    /**
     * ⚠️ TWO WAYS THESE KEYS MISLEAD, AND THEY ARE DIFFERENT. Both have cost a round of investigation.
     *
     * 1. WRONG END OF A REPEATED CHECKPOINT. stampAge and sampleHeap re-write on EVERY occurrence, so
     *    age_s_* and heap_used_mb_* are the LAST occurrence, not the one being attributed. Under a storm
     *    the last one is the moment of death. That is what the CAS-guarded *_first companions exist for:
     *    read <key>_first when attributing, and the bare key only as "state at the end". heap_peak_mb
     *    survives as the one order-independent value, because a running max does not care when it was
     *    sampled.
     *
     * 2. ⚠️ THE HEAP IS OBSERVED AT CHECKPOINTS *AND*, SINCE 2026-09-26, ON A ~60s TICK.
     *    [AMENDED] The sampling-artifact warning below still applies to every CHECKPOINT key and to any
     *    peak a checkpoint set — but heap_peak_mb / heap_peak_at_age_s are no longer
     *    checkpoint-only: onHeapTick advances them from a foreground Activity ticker AND from the
     *    PlayerService scope while the service lives. A peak dated between checkpoints is now REAL
     *    rather than absent, and heap_peak_at_age_s has ~minute resolution instead of event resolution.
     *    WHY: a 1111 OOM read 29 MB at 35s then died at 256 MB about seven minutes later with NOTHING
     *    recorded in between, because no checkpoint fired in that window.
     *    ⚠️ onHeapTick WRITES NO CEILING OF ITS OWN, AND THAT IS NOT A GAP: read a tick-set
     *    peak against heap_limit_mb, which sampleHeap writes once per process.
     *    ⚠⚠ [AMENDED 2026-10-03] THIS PARAGRAPH SAID "The heap LIMIT is derivable from any
     *    used+headroom pair ... without spending two more of the 64 keys on a sixth pair. Read the
     *    limit off whichever pair the report carries." THE REASONING WAS RIGHT AND WAS THEN FOLLOWED
     *    THROUGH: if one pair suffices, four of the five were redundant, so the five
     *    heap_headroom_mb_* families became a single heap_limit_mb. There is no pair left to read the
     *    limit off - read the key. Full argument and the key census at sampleHeap.
     *    heapPeakMb is a running max over sampleHeap calls, and sampleHeap is called only from
     *    onServiceCreate / onAutoCachesCleared / onQueueBuild / onFeedLoad / onControllerConnected.
     *    ⚠️ [CORRECTED 2026-10-03] THAT LIST PREVIOUSLY READ "onServiceCreate /
     *    onControllerConnected / onQueueBuild / onQueueSize / onFeedLoad / onExtensionSwitch" - SIX
     *    NAMES, TWO OF THEM WRONG AND ONE MISSING. onQueueSize and onExtensionSwitch call stampAge
     *    ONLY and take no heap sample; onAutoCachesCleared does and was absent. Counted from the five
     *    actual call sites while doing the key census at sampleHeap, which is the first time anything
     *    needed the exact number. The sampling-artifact point below is UNAFFECTED - feed loads are
     *    still by far the most frequent of the five.
     *    So "the peak was at a feed load" means only that the highest CHECKPOINT SAMPLE
     *    happened to be a feed load — and feed loads are by far the most frequent checkpoint. IT IS A
     *    SAMPLING ARTIFACT, NOT ATTRIBUTION.
     *    Worked example, 2026-09-07: five OOM reports appeared to show that the FEWER feed loads a session
     *    had, the FASTER it reached the ceiling (383 MB in 82 s on 13 loads, versus 1062 s on 56). Read as
     *    causation that is nonsense; read as sampling it is obvious — the fast sessions died sooner, so
     *    fewer checkpoints of ANY kind fired first. Feed count was a CLOCK, not a cause. The real
     *    discriminator was player_media_item_count (~5,430 versus 1), which nothing in the heap keys
     *    pointed at directly.
     *
     * Between them: (1) says a repeated checkpoint reports its last firing, (2) says an event you did not
     * checkpoint is invisible and the checkpoint you did fire will get the blame.
     *
     * ⚠⚠ [2026-10-03] THE PER-CHECKPOINT headroom KEYS WERE REMOVED HERE, AND THE REASON IS A
     * HARD LIMIT RATHER THAN TIDINESS: CRASHLYTICS STORES 64 CUSTOM KEYS AND SILENTLY DROPS EVERY KEY
     * WRITTEN AFTER THE 64th. This object had been read as using "~50 of 64" - the stale figure now
     * corrected at onHeapTick - because a census by grepping literal set("name") CANNOT SEE THE NAMES
     * stampAge AND THIS FUNCTION BUILD. Counted properly, before this change:
     *   26  literal names in this object
     *    5  App.kt's throwFlow collector
     *   20  stampAge   - 10 checkpoint call sites x (base + _first)
     *   20  sampleHeap -  5 call sites x (used + headroom + both _first twins)
     *   71  TOTAL NAMES, i.e. ALREADY OVER THE LIMIT BEFORE ANYTHING WAS ADDED.
     * Per-session maxima were 56 non-AA, 63 AA, 71 AA-plus-app-update - so update sessions on AA have
     * been losing keys, invisibly, and WHICH keys is decided by write ORDER rather than importance.
     *
     * ⚠⚠ WHY headroom WAS THE RIGHT TEN TO LOSE - THE ARGUMENT WAS ALREADY IN THIS FILE. The
     * note added at doc item 2 on 2026-09-26 reads: "The heap LIMIT is derivable from any used+headroom
     * pair in the same report (limit = used + headroom), so a tick-set peak can still be read against
     * the ceiling without spending two more of the 64 keys". If ONE pair suffices to recover the
     * ceiling, four of the five were redundant by that same reasoning - and the quantity is maxMemory,
     * a PROCESS CONSTANT, so five pairs each re-measuring it was never the right shape. heap_limit_mb
     * is written once, CAS-guarded, at the first sample of any kind. NET -9 NAMES (10 out, 1 in).
     * ⚠️ NOTHING DIAGNOSTIC WAS LOST, which is the whole case for choosing these ten: headroom
     * at a checkpoint = heap_limit_mb - that checkpoint's heap_used_mb_*, now recoverable for EVERY
     * suffix rather than only the five that stored it.
     * ⚠️ WHAT DID CHANGE, STATED SO IT IS NOT REDISCOVERED AS A DEFECT: reports from before
     * build 1114 carry heap_headroom_mb_* and no heap_limit_mb; reports after carry the reverse. A
     * console filter on a headroom key goes EMPTY rather than erroring, which reads like a regression.
     *
     * ⚠️ AND THE CENSUS IS THE REUSABLE PART, NOT THE SAVING. Measured against the tree AFTER
     * this change: 33 literal names + 10 stampAge sites x2 + 5 sampleHeap sites x2 = 63 OF 64, and
     * per-session maxima of 50 baseline, 51 non-AA with a stuck report, 56 on AA, 63 on AA plus an
     * app-update session. ONE KEY OF HEADROOM IN THE WORST CASE - the next key added has to take one
     * out, and the cheapest candidate is folding install_source_installer into install_source the way
     * app_update_completed already formats "$from->$to".
     * HOW TO RE-RUN IT: count the stampAge call sites x2 and the sampleHeap call sites x2, add the
     * literal set(...) / setCustomKey(...) names, and STRIP COMMENTS FIRST - the census script counted
     * this very note's `set("name")` example as a 34th key on its first run, which is the same
     * blind-spot-in-the-instrument failure the note is about, one level in.
     */
    private fun sampleHeap(usedKey: String) {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val usedMb = (used / (1024 * 1024)).toInt()
        set(usedKey, usedMb)
        // The growth ceiling, once per process. maxMemory() cannot change for the life of the process, so
        // a later write could only repeat the first - hence CAS rather than last-write-wins, and hence no
        // _first companion (doc note 5 is about repeated checkpoints and does not apply to a constant).
        if (heapLimitRecorded.compareAndSet(false, true))
            set("heap_limit_mb", (rt.maxMemory() / (1024 * 1024)).toInt())
        // Per-checkpoint FIRST sample, same reasoning as stampAge: a repeatedly-fired checkpoint's
        // last-write heap value is the dying-end reading, not the reading at the event you are
        // attributing to. heap_used_mb_conn on the build-1036 AA report was the LAST connect of the
        // session, not the first — which is exactly the misreading these companions prevent.
        if (firstStamped.add(usedKey)) set("${usedKey}_first", usedMb)
        // First-ever sample: pins the STARTING point of the trajectory, which no last-write key can. CAS so
        // the first sampler wins even if two checkpoints race.
        if (heapFirstRecorded.compareAndSet(false, true)) {
            set("heap_first_mb", usedMb)
            set("heap_first_at_age_s", ageS())
        }
        // Running max: written only when it actually advances, so a heap that plateaus stops writing. With
        // first + peak + last, "born high" (first ≈ peak ≈ last) is distinguishable from "climbed" (first low,
        // peak late), and heap_peak_at_age_s dates the climb.
        val previousPeak = heapPeakMb.getAndUpdate { if (usedMb > it) usedMb else it }
        if (usedMb > previousPeak) {
            set("heap_peak_mb", usedMb)
            set("heap_peak_at_age_s", ageS())
        }
    }

    /**
     * Advances heap_peak_mb / heap_peak_at_age_s ONLY. Called from a ~60s ticker; see note 2.
     *
     * ⚠⚠ IT DELIBERATELY DOES NOT CALL sampleHeap. Passing an existing checkpoint's key would
     * destroy that key's meaning - heap_used_mb_feed would stop meaning "at a feed load". Passing a NEW
     * one would cost another key. So this touches the two peak keys and nothing else.
     * ⚠️ [CORRECTED 2026-10-03] THIS NOTE READ "Every sampleHeap call also writes a used/headroom
     * PAIR" AND "two more keys against the ~50-of-64 already in use". The first is no longer true -
     * sampleHeap writes no headroom - and the SECOND WAS WRONG THE DAY IT WAS WRITTEN: the real census
     * is 71 names against a 64 limit, not ~50, because the figure came from grepping literal set("name")
     * calls and interpolated names are invisible to that. See the key-budget note at sampleHeap.
     * THE CONCLUSION SURVIVES INTACT: not calling sampleHeap is still right, and the budget argument for
     * it is STRONGER than the one recorded here rather than weaker.
     *
     * ⚠⚠ NOTE 4 IS SATISFIED BY CONSTRUCTION, NOT BY LUCK: both writes sit inside the
     * peak-advanced branch. A tick that finds no new peak writes NOTHING, and the retained value is
     * still true - the accumulator case note 4 licenses. Nothing here is a snapshot, so there is no
     * key that would silently describe an earlier moment.
     *
     * ⚠️ heap_first_* IS DELIBERATELY UNTOUCHED. If a tick fired before any checkpoint,
     * setting it would redefine heap_first_mb from "first CHECKPOINT sample" to "first anything",
     * silently changing how every existing report reads.
     *
     * ⚠️ TWO TICKERS CALL THIS AND THAT IS INTENDED - the Activity's and PlayerService's.
     * heapPeakMb is an AtomicInteger updated with getAndUpdate, so a concurrent tick can at worst
     * lose a redundant write of an equal-or-lower value; the max itself cannot go backwards. The
     * service ticker exists because the OOMs that motivated this happened with NO Activity in the
     * foreground (a restore OOM inside the service, and an OOM loop in background/AA sessions), where
     * an Activity-only ticker would have recorded nothing at all.
     */
    fun onHeapTick() {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val previousPeak = heapPeakMb.getAndUpdate { if (usedMb > it) usedMb else it }
        if (usedMb > previousPeak) {
            set("heap_peak_mb", usedMb)
            set("heap_peak_at_age_s", ageS())
        }
    }

    fun onServiceCreate() {
        stampAge("age_s_svc")
        set("service_create_count", serviceCreates.incrementAndGet())
        sampleHeap("heap_used_mb_svc")
    }

    // Sampled immediately AFTER AndroidAutoCallback.clearCaches(). heap_used_mb_conn is taken in
    // onConnect, which Media3 invokes BEFORE onGetLibraryRoot — so conn measures the retained graph the
    // last session left, and this measures what survives the clear. The difference is the browse caches.
    fun onAutoCachesCleared() {
        stampAge("age_s_auto_clear")
        sampleHeap("heap_used_mb_auto_clear")
    }

    /**
     * The app-update install-source gate LET US PROCEED. Deliberately means exactly that — not "we
     * checked", and not "we made a network call" — so it is set immediately after
     * AppUpdater.isStoreInstall() returns false, before the build-type branch.
     *
     * This is the verification half of an absolute requirement: Play users must never be offered an
     * app update. Enforcement alone was only half-observable — a FAILED app update reported itself
     * (and, since the repo now appears in getGithubUpdateUrl's messages, was attributable to the app
     * path), but a SUCCESSFUL bypass produced nothing at all. With this key, any report carrying
     * install_source = com.android.vending AND app_update_attempted = true is a visible violation.
     *
     * Sticky for the process by design: once the gate passes it rides on every later report.
     */
    fun onAppUpdateGatePassed() {
        stampAge("age_s_app_update_gate")
        // (!) RENAMED 2026-09-05, from "app_update_attempted". Reports before build 1079 carry the OLD
        // name; it means the same thing, which is the problem. The old name was read in triage as "the
        // user tried to update", and it does not say that: this fires at process birth on EVERY non-store
        // install, before any tag check, before any network call, and regardless of whether an update
        // exists. It records ONE fact — the install-source gate let us proceed. See AppUpdater.updateApp.
        // What an actual attempt looks like is app_update_stage below.
        set("app_update_gate_passed", true)
    }

    /**
     * How far an app self-update actually got. Written only when a run reaches each point, so the LAST
     * value is the furthest stage reached in this process:
     *   "offered"    a release exists whose tag differs from the running build; nothing downloaded yet.
     *                Absence of this key with app_update_gate_passed=true means no update was on offer,
     *                which is the normal state.
     *   "install_permission_missing"  an update existed, was offered, and the install was refused for want
     *                of the "install unknown apps" permission — either the user declined the system screen
     *                or no handler for ACTION_MANAGE_UNKNOWN_APP_SOURCES exists on the device.
     *                ⚠️ THIS DOES NOT MEAN THE USER WAS TOLD. It was named "permission_denied" until
     *                2026-09-10, which read as a user-facing outcome; it is not one. The key is written
     *                BEFORE the snackbar emit and the emit can be dropped — see the note at the emit site in
     *                AppUpdater. WHAT IT SUPPORTS: "the update was offered, downloaded, and the install was
     *                refused for want of permission." That is solid and is what the first field event
     *                (build 1084) proved. Whether anything reached the screen is OUTSIDE what this key
     *                observes; do not infer delivery from the stage name.
     *   "downloaded" the APK/zip transferred and passed the length check.
     *   "ready"      the file is a plain APK, or was successfully unwrapped from nightly's artifact zip,
     *                and is being handed to the installer.
     * ⚠⚠ [2026-09-14] "ready" ON A REPORT THAT EXISTS AT ALL MEANS THE SELF-INSTALL FAILED.
     * A SUCCESSFUL self-update kills this process (the package manager tears us down to replace us),
     * so a live process filing a report while carrying stage=ready has necessarily gotten a FAILURE
     * back from the installer. Use it to tell the APP path from the EXTENSION path, which the stack
     * alone cannot do - InstallationUtils.installApp serves both, and ExtensionsViewModel.update
     * takes the app branch and the extension branch as an if/else, never both in one pass.
     * First used this way on a build 1100 report (resultCode=1, status=-22); the comment at
     * installApp that claimed the app path could never reach its throw was falsified by it.
     * ⚠⚠ [2026-09-17] ANOTHER WAY THIS PATH PRODUCES NOTHING WITH NOTHING WRONG: A GITHUB
     * RATE LIMIT. api.github.com allows 60 requests/hour/IP unauthenticated, and one update pass
     * spends 1 (the app check) + 1 per installed extension. A rate-limited user gets NO APP UPDATES
     * either, and it presents as app_update_gate_passed = true with THIS KEY ABSENT - the run never
     * reaches "offered", because getGithubUpdateUrl failed before a release could be resolved.
     * That is the SAME OBSERVABLE as the normal "no update was on offer" state documented above, so
     * the absence of this key does NOT distinguish them. Read it alongside any
     * GithubRateLimitException in the same session.
     * ⚠️ The app check runs FIRST in the pass (ExtensionsViewModel.update calls updateApp
     * before the extension loop), so it usually gets the first draw from whatever quota remains -
     * but that is ORDERING, NOT PROTECTION. A pass that starts already exhausted fails it too.
     * "downloaded" as the last value means the step between download and installer failed — which for
     * builds 1072-1078 was the unzip branch running on a plain release APK (fixed 2026-09-05). Without
     * this key that failure was only distinguishable by hunting for a companion non-fatal.
     */
    fun onAppUpdateStage(stage: String) {
        set("app_update_stage", stage)
    }

    /**
     * The PREVIOUS session's update, observed on the launch after it. `<from>-><to>`, plus whether the
     * replace was ours and whether it went backwards.
     *
     * ⚠⚠ THIS KEY CANNOT BE THE INSTRUMENT, ONLY THE CORROBORATION - A CUSTOM KEY RIDES ON A
     * REPORT. If the self-updater works and the session is clean there IS no report, so a key alone
     * would measure update success only in sessions that ALSO failed at something else - the same
     * blind spot this was built to close, one level up. The instrument is the user-visible line in
     * Settings (SettingsBottomSheet's version view), which needs no report to be seen.
     * ⚠️ A Firebase ANALYTICS event was considered and rejected 2026-09-14: firebase-analytics
     * is on the classpath (libs.bundles.firebase) but NOTHING in the tree calls it, so this would be
     * the project's first custom event - a product decision, not a bug fix. recordException was also
     * rejected: reporting a SUCCESS through the error channel reads as noise later.
     * Written by AppUpdater.recordSelfUpdateOnLaunch, which owns the correctness argument.
     */
    fun onAppUpdateCompleted(from: Long, to: Long, ours: Boolean, downgrade: Boolean) {
        set("app_update_completed", "$from->$to")
        set("app_update_completed_ours", ours)
        if (downgrade) set("app_update_completed_downgrade", true)
    }

    /**
     * Process age at the moment a report is recorded. WITHOUT THIS, NO KEY CARRIES THE REPORT INSTANT:
     * every age_s_* key is the age at ITS checkpoint, and process_age_s is first-write (see stampAge), so
     * reconstructing "when did this happen" meant inferring it from whichever checkpoint happened to fire
     * last — which cost two rounds of arithmetic on the 2026-09-05 StuckPlayerDetector pair and produced
     * one wrong reading before it produced a right one.
     *
     * Deliberately NOT routed through stampAge: this is not a checkpoint. It must not claim a _first slot
     * (its first value is meaningless — it is just the first report) and must not be able to win the
     * process_age_s first-write race away from a real checkpoint.
     */
    fun onReportRecorded() {
        set("report_age_s", ageS())
    }

    /**
     * State at the instant media3's StuckPlayerDetector reported a stall. See
     * PlayerEventListener.stuckDetail for the field list and what each value discriminates.
     *
     * A KEY, NOT A recordSkip DETAIL, and that is deliberate: recordSkip's `detail` string is folded into
     * safeCause and thence into report()'s dedupe SIGNATURE, so every continuous value in it multiplies
     * the number of distinct reports. A custom key feeds no signature, so it can carry raw numbers.
     * Sticky like every key here: always written on this path, never conditionally, or the previous
     * stall's values would ride along on the next unrelated report.
     */
    fun onStuckDetail(detail: String) {
        set("stuck_detail", detail)
    }

    fun onQueueBuild(itemCount: Int) {
        stampAge("age_s_build")
        set("restore_build_count", itemCount)
        sampleHeap("heap_used_mb_build")
    }

    fun onQueueSize(count: Int) {
        stampAge("age_s_queue")
        set("player_media_item_count", count)
    }

    fun onExtensionSwitch(extensionId: String) {
        stampAge("age_s_switch")
        set("extension_switch_count", extensionSwitches.incrementAndGet())
        set("current_extension_id", extensionId)
    }

    fun onFeedLoad() {
        stampAge("age_s_feed")
        set("feed_load_count", feedLoads.incrementAndGet())
        // The only heap sample tied to the aggregate-working-set hypothesis (feed loads accumulate
        // covers/shelves that the svc-create and queue-build samples both miss, being earlier). Caller is
        // debounced 100ms + collectLatest (~once per settled switch/refresh); a few Runtime reads and key
        // writes, no allocation — not hot, no every-Nth gating needed.
        sampleHeap("heap_used_mb_feed")
    }

    fun onPlayingExtension(extensionId: String) {
        stampAge("age_s_playing")
        set("playing_extension_id", extensionId)
    }

    // packageName names the storm source directly — gearhead's browser, system UI, Bluetooth, our own UI or
    // the widget — which no count can. It is the one field that turns "something reconnected 1120 times" into
    // an actionable lead.
    fun onControllerConnected(packageName: String) {
        stampAge("age_s_conn")
        set("controller_connect_count", controllerConnects.incrementAndGet())
        set("last_controller_pkg", packageName)
        // All three known crashes fired at MediaController connect, a few hundred ms after onCreate — so the
        // svc-create sample can already be stale. The svc→conn heap delta shows whether startup is climbing
        // fast or the heap was already high on arrival.
        sampleHeap("heap_used_mb_conn")
    }

    fun onControllerDisconnected(packageName: String) {
        stampAge("age_s_disc")
        set("controller_disconnect_count", controllerDisconnects.incrementAndGet())
        set("last_disconnected_pkg", packageName)
    }

    fun onAndroidAutoState(connected: Boolean) {
        set("aa_connected", connected)
    }
}

/**
 * The shared guard for any third-party string that reaches Crashlytics: strip URLs (signed CDN tokens
 * live in them) and hard-cap.
 *
 * ⚠⚠ MOVED HERE FROM PlayerEventListener ON 2026-10-03 FOR THE REASON THAT FUNCTION'S OWN
 * COMMENT GAVE FOR EXTRACTING IT: it was pulled out of its two call sites so the extension name would
 * get "exactly the same treatment as the message rather than a second, drifting copy of the rule".
 * App.kt's deezer_gateway key is a third caller in a third package, so the same argument applies one
 * level up. The BODY IS UNCHANGED, which is what keeps lastCauses byte-identical - HealthMonitor
 * requires that format to be fixed.
 *
 * ⚠️ THE REGEX IS HOISTED AND THE BEHAVIOUR IS NOT CHANGED. It was compiled per call at the old
 * site; a file-level val compiles it once. Same pattern, same replacement, same trim-then-take order.
 *
 * ⚠️ IT STRIPS URLs AND CAPS LENGTH. IT DOES NOT STRIP IDENTIFIERS, and no caller should read
 * it as a PII filter. A caller passing a string that might carry an account id has to say so at its
 * own site - deezer_gateway's note in App.kt does.
 *
 * Top-level rather than a member of [CrashKeys] because a member extension function cannot be
 * imported, and both callers live in other packages.
 */
internal fun String.scrubbedForCrashlytics(max: Int) =
    replace(URL_PATTERN, "<url>").trim().take(max)

private val URL_PATTERN = Regex("https?://\\S+")
