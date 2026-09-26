package dev.brahmkshatriya.echo.di

import android.app.Application
import android.content.Context.CONNECTIVITY_SERVICE
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.mayakapps.kache.FileKache
import com.mayakapps.kache.KacheStrategy
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import dev.brahmkshatriya.echo.utils.CrashKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class App(
    val context: Application,
    val settings: SharedPreferences,
) {
    // ⚠️ THE BUFFER IS LOAD-BEARING. With the default zero buffer these are SUSPEND-on-emit flows, so
    // `emit` blocks until EVERY current subscriber has accepted the value — which makes error REPORTING
    // able to stall the code that is reporting. That is not theoretical: ExtensionUtils.getOrThrow awaits
    // `throwableFlow.emit(it)` inline in the caller's coroutine before returning null, so any failing
    // extension call on the browse/settings/feed paths waits on these subscribers. And the throwFlow
    // collector below has NO suspension point (printStackTrace + six setCustomKey + recordException are
    // all blocking), so collectLatest cannot cancel it — a stuck Crashlytics call wedges the collector and
    // every emitter behind it, with no way out short of a process restart.
    // DROP_OLDEST makes emit non-suspending unconditionally. 64 slots means normal operation loses
    // nothing; only a pathological burst drops, and dropping an error REPORT is strictly better than
    // hanging the caller that produced it. Nothing depends on the back-pressure: both subscribers are
    // fire-and-forget (Crashlytics record, snackbar) and getOrThrow discards the result either way.
    // Do not remove the buffer to "not lose reports" — losing a report is the trade being made.
    // ⚠⚠ TWO SUBSCRIBERS, AND THEY DO NOT BEHAVE THE SAME - NEITHER CALL SITE SHOWS THIS, and
    // it is why a Crashlytics count and what a user actually saw can differ:
    //   App.init (just below)                 UNGATED - runs on App.scope, always collecting, so
    //                                         EVERY emission becomes a Crashlytics non-fatal.
    //   MainActivity.setupExceptionHandler    LIFECYCLE-GATED - ContextUtils.observe is
    //                                         flowWithLifecycle(lifecycle), i.e. STARTED, so the
    //                                         snackbar only appears while the Activity is started.
    // ⚠️ AND THE BUFFER DOES NOT CLOSE THAT GAP. replay = 0: extraBufferCapacity stops emit
    // SUSPENDING for a SLOW collector; it does NOT hold values for an ABSENT one. An emission while the
    // Activity is stopped is recorded and never seen.
    // ⚠️ SO A COUNT ON A CRASHLYTICS ISSUE IS AN UPPER BOUND ON WHAT THE USER SAW, NOT A MATCH.
    // Do not read "8 events" as "8 snackbars" when reasoning about someone's experience.
    val throwFlow = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Record-only sibling of [throwFlow]: reaches Crashlytics, NEVER the screen.
     *
     * ⚠⚠ IT EXISTS BECAUSE "REPORT IT BUT DO NOT INTERRUPT" IS UNEXPRESSIBLE ON ONE FLOW. Both
     * of throwFlow's subscribers read the same emissions, so suppressing the snackbar by skipping the
     * emit ALSO suppresses the report - which is how a rising signal becomes invisible. (That is not
     * hypothetical: the extension-update MissingFieldException was muted in May precisely because it
     * was too noisy, and the noise and the signal were the same emission.)
     *
     * ⚠️ IT IS COLLECTED BY MERGING INTO App.init's EXISTING COLLECTOR, not by a second
     * recordException call site, DELIBERATELY. That collector sets six CrashKeys plus
     * onReportRecorded(); a parallel call site would have to duplicate all of them and would drift -
     * see the note at HealthMonitor's recordException, which is the OTHER site and already has to be
     * kept in step by hand.
     *
     * USE IT FOR: a failure the user did not ask about and cannot act on (a background update check).
     * DO NOT use it for anything the user is waiting on, or for anything that follows a progress
     * message already shown - silence there reads as a hang.
     */
    val silentThrowFlow = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Same shape, same fix. messageFlow is worse than throwFlow in one respect: its only subscriber is
    // SnackBarHandler's lifecycle-gated observe(), with no ungated sibling, so a message emitted while the
    // Activity is stopped was already lost with no record anywhere.
    val messageFlow = MutableSharedFlow<Message>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Safety net for UNCAUGHT exceptions in background coroutines launched on `scope` — e.g. an extension's
    // background token refresh throwing a raw IllegalStateException. Without a handler these hit the default
    // uncaught handler and CRASH the app; here they route to throwFlow and degrade to the exact same non-fatal
    // path as every other extension error (the throwFlow collector below does printStackTrace + Crashlytics
    // recordException with isLoginRequired() suppression; the ExceptionUtils collector shows the snackbar).
    // Notes: CoroutineExceptionHandler is never invoked for CancellationException (normal cancellation) — the
    // guard is defensive so it can never be reported. We bridge the non-suspend handler to the suspending emit via
    // scope.launch (safe: SupervisorJob keeps `scope` alive after a child fails), wrapped in runCatching so a
    // failure to record can never re-crash or loop. We only emit — the existing collectors do the handling.
    // The `: CoroutineExceptionHandler` annotation is LOAD-BEARING, not style. Without it the property's
    // type must be inferred from the initializer, whose lambda body references `scope`, whose type is
    // inferred from an expression containing this handler — a cycle the compiler reports as "Type checking
    // has run into a recursive problem", pointing at the SCOPE line rather than the annotation that was
    // removed. All four handlers (App, ExtensionLoader, PlayerService, Downloader) carry this note.
    // ⚠⚠ CALLED vs LAUNCHED — THIS IS WHY SOME EXTENSION FAILURES ARE NON-FATALS AND ONE WAS A FATAL.
    // It is the single most useful distinction for triaging an extension crash, so it lives here rather
    // than in a summary:
    //   CALLED — anything we invoke through ExtensionUtils.get/getAs/getIf is wrapped in nested runCatching
    //     and rethrown as toAppException(this). That is where BOTH the catching and the ATTRIBUTION come
    //     from (App.throwingExtensionId walks the chain for the first AppException). Every extension
    //     failure that surfaced correctly took this path: Tidal serving HTML instead of JSON, Combine
    //     failing to fetch Spotify secrets, YTM's ytmkt MissingFieldException.
    //   LAUNCHED — a coroutine started on a SCOPE is not called by us and returns no Result to anyone. It
    //     is only as safe as the scope it runs on: a throw goes to that scope's CoroutineExceptionHandler,
    //     and if there is none, to the DEFAULT UNCAUGHT HANDLER, which kills the process.
    // BUILD 974 WAS THE SECOND KIND: a Spotify 500 (api-partner.spotify.com/pathfinder/v2/query) died with
    // NO APP FRAMES between the extension and the dispatcher — SpotifyApi.call -> invokeSuspend ->
    // DispatchedTask.run — which is the signature of a launch nobody was catching. This handler, and its
    // three siblings (ExtensionLoader, PlayerService, Downloader), are the fix for that class; they landed
    // in 92ab2e08 = build 997, 23 builds after that crash.
    //
    // ⚠️ AND THE LIMIT OF THAT FIX, STATED RATHER THAN ROUNDED UP: a handler protects THE SCOPE IT IS
    // INSTALLED ON. An extension that creates its OWN CoroutineScope and launches on it is outside every
    // handler we own, and nothing in this app can reach it. The 974 stack shows LimitedDispatcher$Worker,
    // and no scope of ours uses limitedParallelism (the only such dispatcher in this tree is
    // EffectsListener's broadcast one), which SUGGESTS the coroutine was on a scope the Spotify extension
    // created internally. INFERENCE, NOT PROOF — LimitedDispatcher can also appear via withContext on a
    // limited dispatcher inside an ordinary scope, and the extension is a third-party APK nobody here can
    // read. So: THE FIX COVERS THE CLASS; WHETHER IT COVERS THAT EXACT CRASH IS UNKNOWABLE. Do not record
    // it as closed-by-verification; it is closed by the class being guarded and 94 builds of silence.
    //
    // ⚠️ THE GENERAL RULE UNDERNEATH THIS, cross-referenced so it does not read as a local
    // judgement made twice: THE CODE THAT KNOWS ABOUT THE FAILURE REPORTS IT. That is exactly why CALLED
    // works and LAUNCHED does not - the call site holds the Result, the scope holds nothing.
    // The same rule decides a question one subsystem over, in the service-to-UI direction: a custom
    // command's SessionResult is discarded by its caller (see PlayerViewModel.withBrowser), and the fix
    // belongs in the PlayerCallback HANDLER - which knows what failed and can emit to app.messageFlow or
    // throwFlow, as playItem already does - NOT in the dispatch helper, which could only ever learn an
    // error code with no message. That note was nearly aimed at the helper before this rule was applied.
    private val exceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        runCatching { scope.launch { throwFlow.emit(throwable) } }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Updated by PlayerService whenever player state changes; read at crash-record time.
    @Volatile var crashExtensionId: String = "none"
    @Volatile var crashPlayerState: Int = 1  // Player.STATE_IDLE
    @Volatile var crashIsPlaying: Boolean = false

    private suspend fun getCache() = FileKache(
        context.cacheDir.resolve("kache").toString(),
        50 * 1024 * 1024
    ) {
        strategy = KacheStrategy.LRU
    }

    // CancellationException MUST propagate: runCatching caught it, so a CANCELLED first attempt fell into
    // the recovery path and ran deleteRecursively() on a cache another coroutine may still be using, then
    // rebuilt over it. That is the wipe-underneath-a-running-instance shape already documented at
    // PlayerService.getCache, and cancellation is not a corrupt cache - it is this coroutine being told to
    // stop. Only a real failure should trigger the wipe.
    private val fileCache = scope.async(Dispatchers.IO, CoroutineStart.LAZY) {
        try {
            getCache()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Corrupt/locked kache -> wipe and rebuild. Deliberate best-effort recovery; if the rebuild
            // also fails the exception completes this Deferred and every awaiter sees it, which is the
            // correct outcome (a failure they can report, not a hang).
            context.cacheDir.resolve("kache").deleteRecursively()
            getCache()
        }
    }

    // 60s, and deliberately generous. The job of this bound is to convert INFINITY into something finite,
    // not to enforce responsiveness - so it should sit far above any plausible normal duration, because a
    // value tuned too tight converts a slow-but-working cache into a failed load, on a path with known I/O
    // and OOM history. It is nearly free: the Deferred is LAZY and single-instance, so construction happens
    // ONCE PER PROCESS and every await after the first returns immediately on a completed Deferred. The
    // bound can therefore only bite on the first track load of a session.
    // NOT measured. Nobody has established what FileKache construction normally costs; if that number is
    // ever wanted it needs a deliberate timing pass, not a guess dressed up as one.
    private val fileCacheTimeoutMs = 60_000L

    // ⚠️ NO CAUSE, DELIBERATELY. Do not "improve" this by attaching the timeout as a cause.
    // PlayerEventListener classifies errors off `rootCause`, the DEEPEST node in the chain, and its
    // silent-skip family matches `rootCause is TimeoutCancellationException`. Attaching a
    // TimeoutCancellationException here would make every cache timeout resolve to it, and a slow cache
    // would silently skip every track in the queue - the same shape as the May 2026 bug where each track
    // skipped until one had a fresh token. Causeless, this resolves to itself and reaches the explicit
    // branch in onPlayerError that pauses and surfaces the message instead.
    class FileCacheTimeoutException(ms: Long) :
        Exception("Timed out waiting for the file cache after ${ms}ms")

    // The raw Deferred is PRIVATE so this is the only way in - the rule is structural, not advisory. That Deferred is a single
    // lazily-started instance shared process-wide and sits on the critical path of every track load
    // (Cached.loadMedia -> StreamableLoader.loadTrack -> StreamableMediaSource), so an unbounded await
    // there means one stalled cache init hangs ALL playback, silently, until the process is killed.
    // Bounding it does NOT poison the Deferred: withTimeoutOrNull cancels the AWAITING coroutine, not the
    // async, which keeps running on `scope` independently. So a transient stall self-heals - the next
    // caller's await returns immediately once it completes - while a permanent one degrades to a bounded
    // failure per load instead of a permanent hang. Throwing rather than returning null keeps every call
    // site unchanged: they already run inside runCatching, so this surfaces as an ordinary failed load.
    suspend fun awaitFileCache(): FileKache =
        withTimeoutOrNull(fileCacheTimeoutMs) { fileCache.await() } ?: throw FileCacheTimeoutException(fileCacheTimeoutMs)

    private val _networkFlow = MutableStateFlow(NetworkConnection.NotConnected)
    val networkFlow = _networkFlow.asStateFlow()
    val isUnmetered get() = networkFlow.value == NetworkConnection.Unmetered

    init {
        scope.launch {
            // merge, so silentThrowFlow gets the SAME key-setting and recordException as throwFlow.
            merge(throwFlow, silentThrowFlow).collectLatest {
                it.printStackTrace()
                // BuildConfig.HAS_FIREBASE is a compile-time boolean (no Firebase type referenced),
                // so in no-json builds this branch is dead and FirebaseCrashlytics is never loaded.
                // LoginRequired is an EXPECTED "user not signed in" signal (any extension can raise it), not a
                // fault — so skip Crashlytics for it. This ONLY suppresses recordException: the throwFlow emission
                // is untouched, so the "Sign in" snackbar (the separate setupExceptionHandler collector), the feed
                // login shelf, and the player's LoginOrAuth stop() all still fire. isLoginRequired walks the cause
                // chain to catch BOTH forms — the player path's PlayerException→AppException.LoginRequired AND the
                // AA getList path's RAW ClientException.LoginRequired (which classify() would miss).
                // HAS_FIREBASE is a REAL build toggle (true only when google-services.json is present — false
                // in the no-Firebase / F-Droid variant, where FirebaseCrashlytics isn't on the classpath).
                // Lint sees only THIS build's baked-true value ("condition always true" / "can be simplified"),
                // but the guard MUST stay or the no-Firebase build won't compile. Do NOT simplify. Suppression
                // Two distinct inspections fire here: KotlinConstantConditions ("condition always true", the
                // ID already IDE-generated for the analogous constant-BuildConfig check in AppUpdater) and
                // SimplifyBooleanWithConstants ("boolean expression can be simplified"). The latter's ID is
                // the shortName derived from SimplifyBooleanWithConstantsInspection (no explicit shortName in
                // the Kotlin plugin's registration → class name minus "Inspection"), confirmed against the
                // plugin jar — not guessed.
                // ⚠️ THIS BLOCK MUST STAY runCatching-WRAPPED. Recorded as a KNOWN GAP on
                // 2026-08-20 and CLOSED in build 1057 (f2b661b0); the note is kept rather than deleted
                // because the failure it prevents is completely invisible and the wrap looks removable.
                // FirebaseCrashlytics.getInstance() throws when the default FirebaseApp is absent, and
                // getInstance() itself NPEs if the component is missing - most likely AT STARTUP, before
                // Firebase has initialised, which is exactly when early errors are emitted. This block has
                // no suspension point, so collectLatest cannot cancel it: unwrapped, a throw propagates out
                // of collectLatest, kills this collector for the life of the process, and silences every
                // later non-fatal AND every snackbar with no error anywhere.
                // Before 1057 it was worse: emit then suspended FOREVER on the then-0-buffer
                // MutableSharedFlow, so one early failure took the whole process's reporting down
                // permanently. That is a plausible reason some classes of startup error (a DNS failure on
                // an extension call, say) were under-reported on builds before 1057.
                // Swallowing is correct here: failing to RECORD an error must never escalate into losing
                // all subsequent ones. Pairs with the DROP_OLDEST buffer above - that stops a stuck
                // collector blocking emitters, this stops a throwing one disappearing entirely.
                @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants", "SwallowedException")
                if (BuildConfig.HAS_FIREBASE && !it.isLoginRequired() && !it.isAuthRejection())
                    runCatching {
                    FirebaseCrashlytics.getInstance().apply {
                        // extension_id is the PLAYING extension (crashExtensionId, written by
                        // PlayerService's onMediaItemTransition — grep `crashExtensionId =`) — NOT the
                        // thrower. Most browse/feed
                        // errors come from a non-playing extension, so this systematically mis-attributes
                        // them. Kept unchanged for historical comparability with existing issues, and
                        // duplicated by playing_extension_id. Read throwing_extension_id for attribution.
                        setCustomKey("extension_id", crashExtensionId)
                        // The extension that actually threw, walked off the AppException in the chain
                        // (ExtensionUtils.get:30 wraps every extension call, so one is present for any
                        // error routed through that helper). ALWAYS written — Crashlytics keys are sticky
                        // on the singleton, so omitting it would leave the previous report's value
                        // attached to a host-side error. "none" now means a genuine HOST-side failure —
                        // the two AndroidAutoCallback sites that bypass ExtensionUtils.get (performSearch
                        // and getList) wrap explicitly, so the AA browse/search path attributes correctly.
                        // AndroidAutoCallback:294 stays deliberately unwrapped: Job.join() does not rethrow
                        // the joined job's failure, so the only throwable reaching it is from Media3's
                        // notifySearchResultChanged — host-side, and "none" is the right answer there.
                        setCustomKey("throwing_extension_id", it.throwingExtensionId() ?: "none")
                        // "none" = this report came through throwFlow, NOT HealthMonitor. Written for the
                        // same always-write reason as the keys around it: HealthMonitor.report sets
                        // health_report_type to its exception type, and without this line that value would
                        // stick on the singleton and mislabel the NEXT throwFlow report as a health report.
                        setCustomKey("health_report_type", "none")
                        setCustomKey("player_state", crashPlayerState)
                        setCustomKey("is_playing", crashIsPlaying)
                        // Age at THIS instant, not at any checkpoint. See CrashKeys.onReportRecorded for
                        // why the checkpoint keys cannot answer "when did this happen".
                        // ⚠️ HealthMonitor.kt has the OTHER recordException call site; a report arriving
                        // through it carries no report_age_s unless the same call is added there.
                        CrashKeys.onReportRecorded()
                        recordException(it)
                    }
                }
            }
        }
        // Network-state monitoring is best-effort. Some OEM/framework builds reject the ConnectivityManager
        // binder call from the system server (e.g. OnePlus 7 / GM1913 / Android 11 threw SecurityException/
        // RemoteException "Package android does not belong to <uid>"). Because this runs in the App singleton's
        // CONSTRUCTOR, that used to cascade through Koin (App -> ExtensionLoader) and crash app launch entirely.
        // Guard it so a flaky framework call degrades to "no live network updates" instead of failing to start.
        try {
            val connectivityManager =
                context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val isMetered = connectivityManager.isActiveNetworkMetered
                    _networkFlow.value = if (isMetered) NetworkConnection.Metered
                    else NetworkConnection.Unmetered
                }

                override fun onLost(network: Network) {
                    _networkFlow.value = NetworkConnection.NotConnected
                }
            }
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            _networkFlow.value = when {
                connectivityManager.activeNetwork == null -> NetworkConnection.NotConnected
                connectivityManager.isActiveNetworkMetered -> NetworkConnection.Metered
                else -> NetworkConnection.Unmetered
            }
        } catch (e: Exception) {
            // Degrade gracefully rather than crash construction: assume an online-but-metered connection so
            // extensions still treat the device as connected (NotConnected would make them behave as offline)
            // and playback stays on the conservative metered-quality path. No live updates on this device.
            e.printStackTrace()
            _networkFlow.value = NetworkConnection.Metered
            scope.launch { throwFlow.emit(e) }  // record non-fatally (same Crashlytics path as everywhere else)
        }
    }

    // True if this throwable (or anything in its cause chain) is a login-required signal. Matches BOTH
    // ClientException.LoginRequired (raw form the AA getList path emits) and AppException.LoginRequired (the
    // wrapped form the player/getOrThrow paths emit) — Unauthorized is a subclass of each, so it's covered.
    // Id of the extension that threw: the first AppException in the cause chain. Same walk shape as
    // isLoginRequired below. Uses Metadata.id (not .name) so values line up with extension_id /
    // playing_extension_id and stay filterable. First-found is correct even through Unified —
    // toAppException returns an existing AppException as-is (AppException.kt:60), so the chain holds
    // exactly one, carrying the SUB-extension's metadata rather than "unified".
    private fun Throwable.throwingExtensionId(): String? {
        var t: Throwable? = this
        while (t != null) {
            (t as? AppException)?.let { return it.extension.id }
            t = t.cause
        }
        return null
    }

    private fun Throwable.isLoginRequired(): Boolean {
        var t: Throwable? = this
        while (t != null) {
            if (t is ClientException.LoginRequired || t is AppException.LoginRequired) return true
            t = t.cause
        }
        return false
    }

    /**
     * A credential REFUSAL from an extension's own login screen. Suppressed from Crashlytics for the
     * same reason as [isLoginRequired]: it is a user mistake, not a fault.
     *
     * ⚠⚠ MATCHED ON THE MESSAGE TEXT BECAUSE THE TYPE IS UNREACHABLE FROM HERE, NOT AS A
     * SHORTCUT. DeezerAuthRejectedException lives in the Deezer extension module, so :app has no
     * compile-time reference to it - there is no `is` check available at any level of care. String
     * literals survive R8 (it renames classes, not string contents), which is what makes this stable
     * where a class-name match would not be: the minified name changed tl0 -> ul0 between builds
     * 1109 and 1110, so every release minted a NEW Crashlytics issue for the same user error.
     *
     * ⚠⚠ THE CHAIN WALK IS MANDATORY, NOT STYLISTIC. The throwable that reaches this
     * collector is AppException.Other WRAPPING the refusal (ExtensionUtils.get -> toAppException's
     * `else -> Other(this, extension)`), so a check on the top node can never match. Fourth recorded
     * instance of the wrong-node type check in this repo.
     *
     * ⚠️ IT SUPPRESSES REPORTING ONLY. The throwFlow emission is untouched, so the login
     * screen still shows the message - ExceptionUtils renders AppException.Other as
     * "<ext name>: <cause title>" and getFinalTitle falls through to the cause's message.
     *
     * ⚠️ WHAT STAYS REPORTED, DELIBERATELY: getArlByEmail's two SIBLING throws, which are
     * plain Exceptions with different messages ("Login failed: no access_token in response" /
     * "no ARL in response"). Its own comment calls that split out - an explicit error from Deezer is
     * a refusal, no error at all is a SHAPE SURPRISE and stays retryable and reportable. Do not widen
     * this to cover them.
     *
     * ⚠️ AND IT COVERS MORE THAN "WRONG PASSWORD", CORRECTLY. The exception's own doc records
     * that a suspended account, a forced password reset or a region block can share the same Deezer
     * error, and that the handling deliberately does not try to name which. All are user-actionable.
     */
    private fun Throwable.isAuthRejection(): Boolean {
        var t: Throwable? = this
        while (t != null) {
            if (t.message == DEEZER_AUTH_REJECTED_MESSAGE) return true
            t = t.cause
        }
        return false
    }

    companion object {
        // Byte-identical to DeezerAuthRejectedException's super-constructor message. A comment at that
        // declaration points back here; changing either text silently RE-ENABLES reporting, and the
        // only symptom is a Crashlytics issue reappearing per release.
        private const val DEEZER_AUTH_REJECTED_MESSAGE =
            "Deezer did not accept these credentials."
    }
}
