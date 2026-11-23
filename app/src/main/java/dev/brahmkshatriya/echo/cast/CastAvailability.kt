package dev.brahmkshatriya.echo.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Guarded entry point to the Cast framework. BUILD 1 SCOPE: discovery and logging only - nothing here
 * plays, queues or resolves anything.
 *
 * ⚠⚠ EVERY CALLER MUST TOLERATE null, AND THE THREE QUESTIONS ARE ANSWERED HERE SO NOBODY HAS
 * TO RE-ASK THEM. (1) WHEN does this fire in a healthy run? Only when the quality sheet is opened, and
 * it returns a CastContext. (2) What does the caller do with a failure? Leaves the route button hidden;
 * the rest of the sheet is untouched. (3) What can the user do next? Everything except cast. So the
 * failure mode is a missing button, never a crash and never a lost feature elsewhere.
 *
 * ⚠⚠ INITIALISED LAZILY FROM THE SHEET, NOT FROM App.onCreate, AND THAT IS THE WHOLE REASON
 * BUILD 1 IS SAFE. getSharedInstance is what starts the Cast framework and its mDNS discovery, and it
 * THROWS when Play Services is missing, stale, or when the OptionsProvider cannot be loaded. Putting
 * that on an every-launch path is precisely the mistake this project has already paid for once (the
 * Deezer onExtensionSelected rethrow, which fired on every cold start of a healthy account and locked
 * users out). Behind a user action, the worst case is one tap that produces no button.
 * [CORRECTED 2026-11-23] Not lazy from the sheet any more, and (1) above is wrong too: since 85577220 (2026-10-17) PlayerService's
 * creation calls CastSessionController.register(), which calls this, so getSharedInstance runs on every playback start on any
 * device whose Play Services report SUCCESS. The only reason recorded is PlayerService's note there, "A SESSION CAN START FROM
 * THE TV". Crashlytics 8d782ca9 (1187, FiiO JM21, Android 13, fatal) is the kind of failure this note predicted for an
 * every-launch path: "Failed to find provider com.google.android.gsf.gservices" thrown inside play-services-basement on the main
 * thread, which no runCatching here can reach. Whether Cast or Firebase Analytics made that call is not known.
 *
 * ⚠️ THE LOG LINE IS THE VERIFICATION FOR THE R8 KEEP RULE. A missing or renamed
 * [CastOptionsProvider] surfaces here as a ClassNotFoundException wrapped by the framework - caught,
 * so the app degrades rather than crashing, which also means it would be SILENT without the throwable
 * name in the message. If `cast init failed` ever appears with a ClassNotFound-shaped cause, the keep
 * rule or its anchor is what to look at, not the Cast code.
 */
object CastAvailability {

    const val TAG = "GladixCast"

    // [2026-11-23] Declared in AndroidManifest.xml's <queries>; see castContext.
    private const val GSF_PROVIDER = "com.google.android.gsf.gservices"

    fun castContext(context: Context): CastContext? {
        val play = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (play != ConnectionResult.SUCCESS) {
            Log.d(TAG, "cast unavailable: play services code=$play")
            return null
        }
        // [2026-11-23] AND THE GOOGLE SERVICES FRAMEWORK'S PROVIDER MUST RESOLVE (the user's decision, Crashlytics 8d782ca9: 1187,
        // FiiO JM21, Android 13, fatal "Failed to find provider com.google.android.gsf.gservices" inside play-services-basement on
        // the main thread, about 2 h into playback, no Gladix frames). isGooglePlayServicesAvailable checks the Play Services app
        // only, so a device with it installed and GSF missing passes, starts Cast from PlayerService, and the failure later
        // arrives where no runCatching can reach it. Null here is the same no-Cast path as a failed check: every caller
        // tolerates it. Needs the provider in the manifest's <queries>, or Android 11+ hides it and Cast is off on every device.
        // Not logged, by decision. Unverified that Cast made the failing call: Firebase Analytics (kept on) could also have.
        val gsf = runCatching { context.packageManager.resolveContentProvider(GSF_PROVIDER, 0) }.getOrNull()
        if (gsf == null) return null
        return runCatching { CastContext.getSharedInstance(context) }
            .onFailure {
                Log.d(TAG, "cast init failed: ${it.javaClass.name}: ${it.message}")
            }
            .getOrNull()
    }

    /** Closed set, so a capture can never read a bare integer. */
    fun stateName(state: Int) = when (state) {
        CastState.NO_DEVICES_AVAILABLE -> "noDevices"
        CastState.NOT_CONNECTED -> "notConnected"
        CastState.CONNECTING -> "connecting"
        CastState.CONNECTED -> "connected"
        else -> "unknown($state)"
    }
}
