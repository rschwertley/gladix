package dev.brahmkshatriya.echo.ui.common

import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import com.google.android.material.R as MaterialR
import dev.brahmkshatriya.echo.R
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import dev.brahmkshatriya.echo.MainActivity
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Collections
import java.util.WeakHashMap

class SnackBarHandler(
    val app: App,
) {

    private val messageFlow = app.messageFlow

    // Pending snackbars, drained one at a time: create() only emits when nothing is showing, and remove()
    // emits the next on dismissal. Deliberately on the Koin SINGLETON so queued messages survive activity
    // recreation - do not move it to the Activity or clear it on recreate.
    private val messages = mutableListOf<Queued>()

    // ⚠⚠ THE TIMESTAMP LIVES ON THE QUEUE ENTRY, NEVER ON Message. Message is in :common, i.e.
    // the extension ABI - adding a field there carries the version-skew hazard documented at
    // SearchFeedClient (an extension built against one shape, running against another). A private wrapper
    // costs nothing and keeps the ABI untouched.
    private data class Queued(val at: Long, val message: Message)

    // ⚠⚠ ACCUMULATE UNGATED, RENDER GATED. THIS IS THE WHOLE FIX, AND THE SPLIT IS THE POINT.
    //
    // ⚠⚠ THE RULE, WHICH IS THE DURABLE PART: UNGATE A COLLECTOR THAT UPDATES STATE, NEVER ONE
    // THAT PRESENTS A TRANSIENT VIEW.
    // PlayerFragment's queueFlow collector was correctly made ungated - it calls submit(), which updates a
    // data structure, is idempotent, and renders correctly whenever the view next becomes visible. So the
    // obvious next step is "ungate setupSnackBar's observe() too". THAT IS WRONG AND STRICTLY WORSE THAN
    // TODAY, and someone will propose it again, so here is the mechanism:
    //   an ungated collector runs while STOPPED (lifecycleScope is cancelled only at DESTROYED);
    //   Snackbar.show() would add the view to a hierarchy nobody is looking at;
    //   its LENGTH_LONG timeout elapses invisibly;
    //   onDismissed fires with event != DISMISS_EVENT_MANUAL;
    //   which calls remove(message, dismissed = true) and DELETES IT FROM THIS QUEUE.
    // The message is silently CONSUMED without ever being seen. Today it at least stays queued. Ungating
    // converts a delivery failure into a DESTRUCTIVE one.
    // A snackbar is an event WITH A DURATION; running it while invisible spends the duration and destroys
    // the event. A state update has no duration and cannot be spent.
    //
    // So the accumulate step - which is pure state work - is ungated here on app.scope, and the RENDER step
    // stays lifecycle-gated in setupSnackBar. That also means every emitter is captured with ZERO call-site
    // changes: there are 32 direct app.messageFlow.emit callers across the app (Download, Login, AppUpdater,
    // PlayerViewModel, PlayerCallback, SaveToPlaylist, MediaDetails and more), and only a handful ever
    // reached create(). Routing them by hand would have been the bulk of the work and a standing trap for
    // every emitter added later.
    //
    // ⚠️ SYNCHRONIZED, WHICH THE ORIGINAL DID NOT NEED. create()/remove() were both called from
    // lifecycleScope, i.e. Main only. This collector runs on app.scope (IO), so `messages` is now genuinely
    // touched from two threads.
    // ⚠️ maxQueuedMessages and messageTtlMs are declared BELOW this block and read by enqueue().
    // Safe because launch() only SCHEDULES the collector - the lambda body runs after construction
    // completes, by which point every property is initialized. It would NOT be safe if this were ever
    // changed to collect synchronously here.
    init {
        app.scope.launch { messageFlow.collect { enqueue(it) } }
    }

    private fun enqueue(message: Message) = synchronized(messages) {
        val now = System.currentTimeMillis()
        messages.removeAll { now - it.at > messageTtlMs }
        if (messages.none { it.message.dedupeKey() == message.dedupeKey() }) {
            messages.add(Queued(now, message))
            while (messages.size > maxQueuedMessages) messages.removeAt(0)
        }
    }

    /** The oldest message still worth showing, or null. Prunes expired entries as a side effect. */
    fun pending(): Message? = synchronized(messages) {
        val now = System.currentTimeMillis()
        messages.removeAll { now - it.at > messageTtlMs }
        messages.firstOrNull()?.message
    }

    // Dedupe by VALUE, not by Message identity. `messages.contains(message)` compared whole Messages, and
    // Message.action holds a LAMBDA - lambdas have no structural equality, so two identical actionable
    // messages never compared equal and the dedupe silently did nothing for every one of them, including
    // every LoginRequired snackbar. Comparing the text plus the action's NAME restores it: the name is what
    // the user reads on the button, so two entries sharing both are the same notification as far as anyone
    // can tell, even though their handlers are distinct objects.
    private fun Message.dedupeKey() = message to action?.name

    // The queue was uncapped. That was survivable only because throwFlow was a zero-buffer SharedFlow whose
    // back-pressure meant a burst mostly never reached create() at all - the containment the Aug 2026 note
    // relied on. Buffering throwFlow/messageFlow (App.kt) removes exactly that, so every emission in a burst
    // now arrives here and, without the fix above, every actionable one appended. Hence a hard cap.
    // 16 is past the point of usefulness rather than a guess at a limit: snackbars are LENGTH_LONG, so a
    // full queue is already ~a minute of consecutive snackbars, and nobody reads the tail. Crashlytics keeps
    // the complete record either way - this list is a notification queue, not a log.
    // Drop OLDEST: a queue this deep is stale, and the newest message describes the current state.
    private val maxQueuedMessages = 16

    // ⚠⚠ 60s, AND IT DOES TWO JOBS - THE SECOND ONE IS NOT OBVIOUS AND IS EASY TO BREAK.
    // JOB 1, STALENESS: a message queued while the Activity was stopped and shown on the next launch can be
    // worse than dropping it - "Playing X" an hour later is noise. The driver for the VALUE is the
    // motivating case, returning from the system "install unknown apps" screen (AppUpdater), which is
    // seconds to tens of seconds. 60s covers a realistic detour and discards anything from a previous
    // session. (restoreCache's 90s answers a DIFFERENT question - whether a cached computation is still
    // valid - so it is not a precedent for this number, only for having one.)
    // ⚠️ JOB 2, ROUTING: because the accumulator above is indiscriminate, THIS TTL IS NOW THE
    // ENTIRE MECHANISM by which background-originated messages fail to surface on a later phone launch.
    // Three are AA- or playback-originated and can fire with no Activity at all:
    //     AndroidAutoCallback:1027   some_tracks_couldnt_be_restored
    //     PlayerEventListener:1298   server_error_skipping
    //     PlayerEventListener:1569   removed_extension_playback_stopped
    // "List is empty" surfacing on a phone launch for something that happened in the car is worse than
    // dropping it, and only the TTL prevents that. SO CHANGING THIS VALUE SILENTLY CHANGES ROUTING
    // BEHAVIOUR, not just freshness. If 60s proves too generous, those three are the opt-out candidates -
    // but note an exclusion mechanism would need a marker ON Message, which is ABI-frozen, so the honest
    // alternatives are a shorter TTL or a second flow, not a flag.
    private val messageTtlMs = 60_000L

    // ⚠⚠ [FIXED] THIS USED TO WEDGE, AND THE WEDGE - NOT THE LOST MESSAGE - IS THE STRONGEST
    // REASON THIS WORK EXISTS. It read:
    //     if (messages.isEmpty()) messageFlow.emit(message)
    //     if (messages.none { ... }) { messages.add(message); ... }
    // so it emitted ONLY when the queue was empty. If that one emission was lost - e.g. the Activity was
    // stopped, since messageFlow is replay = 0 with a single lifecycle-gated subscriber - the message was
    // still ADDED, and from then on the queue was non-empty, so NOTHING EMITTED AGAIN. The only other
    // emitter is remove(), which needs a snackbar to be dismissed, which needs one to have been shown.
    // Net: the queue could hold up to 16 undelivered messages INDEFINITELY with no trigger to show them.
    // That is a wedge, not a dropped message, and it is independent of the lifecycle gate.
    // Now: create() only emits. The accumulator above queues every emission, and setupSnackBar renders the
    // head on STARTED, so a lost emission is recovered on the next visible frame rather than stranding the
    // queue behind it.
    suspend fun create(message: Message) = messageFlow.emit(message)

    suspend fun remove(message: Message, dismissed: Boolean) {
        val next = synchronized(messages) {
            if (dismissed) messages.removeAll { it.message === message }
            messages.firstOrNull()?.message
        }
        // Re-entering the accumulator is harmless: dedupeKey already matches the entry still in the queue,
        // so it is not added twice.
        if (next != null) messageFlow.emit(next)
    }

    companion object {
        /**
         * ⚠️ A LONGER DISPLAY TIME FOR ONE MESSAGE, KEPT HERE BECAUSE Message IS IN :common AND ABI-FROZEN
         * (2026-10-30). Every message is Snackbar.LENGTH_LONG, which Material 1.14.0 makes 2750 ms from show()
         * including the 250 ms slide in and out (SnackbarManager.LONG_DURATION_MS) - too short to reach an
         * Undo. Keyed weakly by the Message object itself (its action lambda makes equality identity in
         * practice), so an entry lives exactly as long as the message is queued or shown.
         * ⚠️ THE ACCESSIBILITY TIMEOUT IS ALREADY MATERIAL'S: Snackbar.getDuration (1.14.0, decoded) passes the
         * set duration through AccessibilityManager.getRecommendedTimeoutMillis on API 29+, with the controls
         * flag when there is an action, and below 29 shows an actionable snackbar indefinitely under touch
         * exploration. So a duration set here only raises the floor.
         */
        private val durations = Collections.synchronizedMap(WeakHashMap<Message, Int>())
        fun Message.showFor(durationMs: Int): Message = also { durations[it] = durationMs }

        fun MainActivity.setupSnackBar(
            uiViewModel: UiViewModel, root: View
        ): SnackBarHandler {
            val handler by inject<SnackBarHandler>()
            val padding = 8.dpToPx(this@setupSnackBar)
            val isTv = isTv()
            @Suppress("IDENTITY_SENSITIVE_OPERATIONS_WITH_VALUE_TYPE")
            val snackBars = WeakHashMap<Int, Snackbar>()
            fun updateInsets(snackBar: Snackbar) {
                snackBar.view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val insets = uiViewModel.systemInsets.value
                    val snackbarInsets = uiViewModel.getSnackbarInsets()
                    marginStart = insets.start + snackbarInsets.start + padding
                    marginEnd = insets.end + snackbarInsets.end + padding
                    bottomMargin = snackbarInsets.bottom + padding
                }
            }
            fun createSnackBar(message: Message) {
                val snackBar = Snackbar.make(root, message.message, Snackbar.LENGTH_LONG)
                durations[message]?.let { snackBar.duration = it }
                snackBar.animationMode = Snackbar.ANIMATION_MODE_SLIDE
                updateInsets(snackBar)
                message.action?.run { snackBar.setAction(name) { handler() } }
                snackBars[message.hashCode()] = snackBar
                // ⚠️ TV: THE ACTION (Undo, Sign in, Retry) IS A D-PAD STOP WHILE THE SNACKBAR SHOWS (2026-10-31). Nothing
                // could reach it: the mini bar's Down points at itself (the May focus-containment fix) and a feed's
                // last row goes to the mini bar or nowhere. While an ACTIONABLE snackbar is up, the mini bar's Down
                // goes to its action (TvAwareRecyclerView's bottom-of-feed route covers the no-mini-bar case);
                // on dismissal the self-loop is restored, and focus left on the vanished action goes back to the
                // bar. Durations are unchanged. Phone: isTv is false and none of this runs.
                if (isTv && message.action != null) snackBar.addCallback(object : Snackbar.Callback() {
                    override fun onShown(sb: Snackbar?) {
                        findViewById<View>(R.id.tvMiniPlayer)?.nextFocusDownId = MaterialR.id.snackbar_action
                    }

                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        val bar = findViewById<View>(R.id.tvMiniPlayer) ?: return
                        bar.nextFocusDownId = R.id.tvMiniPlayer
                        if (snackBar.view.findFocus() != null && bar.isShown) bar.requestFocus()
                    }
                })
                snackBar.addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        snackBars.remove(message.hashCode())
                        lifecycleScope.launch {
                            handler.remove(message, event != DISMISS_EVENT_MANUAL)
                        }
                    }
                })
                snackBar.show()
            }

            observe(handler.messageFlow) { message ->
                createSnackBar(message)
            }
            // ⚠⚠ RENDER DIRECTLY, DO NOT RE-EMIT. Re-emitting into messageFlow to "replay" the
            // pending head would reintroduce the EXACT race being fixed: if the replay beat
            // flowWithLifecycle's resubscription, it would be dropped the same way the original was.
            // Calling createSnackBar straight has no subscriber to lose and therefore no ordering hazard -
            // which matters, because call order has decided six separate designs this week.
            // Guarded on snackBars being empty so this cannot double-show a message the observer above is
            // already displaying when a new one arrives as we reach STARTED.
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    if (snackBars.isEmpty()) handler.pending()?.let { createSnackBar(it) }
                }
            }
            observe(uiViewModel.combined) { _ ->
                snackBars.values.forEach { updateInsets(it) }
            }
            return handler
        }

        fun Fragment.createSnack(message: Message) {
            val handler by inject<SnackBarHandler>()
            lifecycleScope.launch { handler.create(message) }
        }

        fun Fragment.createSnack(message: String) {
            createSnack(Message(message))
        }

        fun Fragment.createSnack(message: Int) {
            createSnack(getString(message))
        }

        fun FragmentActivity.createSnack(message: Message) {
            val handler by inject<SnackBarHandler>()
            lifecycleScope.launch { handler.create(message) }
        }

        fun FragmentActivity.createSnack(message: String) {
            createSnack(Message(message))
        }
    }
}