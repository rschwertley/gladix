package dev.brahmkshatriya.echo.ui.extensions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toFile
import dev.brahmkshatriya.echo.MainActivity.Companion.getMainActivity
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.InstallationUtils.getTempFile
import dev.brahmkshatriya.echo.utils.ContextUtils.getTempFile
import org.koin.android.ext.android.inject
import java.io.File

class ExtensionOpenerActivity : Activity() {

    // Koin's inject extension takes android.content.ComponentCallbacks (verified from
    // ComponentCallbackExtKt in koin-android 4.2.2), which android.app.Activity implements - so it works
    // here with no AppCompat or ViewModel scaffolding. Lazy by construction: resolved at first access in
    // the catch below, never on the success path.
    private val app by inject<App>()

    override fun onStart() {
        super.onStart()
        val uri = intent.data

        val file = runCatching {
            when (uri?.scheme) {
                "content" -> getTempFile(uri)
                "file" -> getTempFile(uri.toFile())
                else -> null
            }
        }.getOrNull()

        if (file == null) Toast.makeText(
            this, getString(R.string.could_not_find_the_file), Toast.LENGTH_SHORT
        ).show()

        finish()
        val startIntent = Intent(this, getMainActivity())
        startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startIntent.data = file?.let { Uri.fromFile(it) }
        // ⚠⚠ SECOND-LINE DEFENCE ONLY - THIS DOES NOT MAKE getMainActivity's FIX OPTIONAL, AND
        // MUST NOT BE READ AS HAVING ADDRESSED THE 1114 FATAL. That crash was
        // ActivityNotFoundException for the EXPLICIT component {<appId>/dev.brahmkshatriya.echo.MainActivity}
        // - a component PackageManager would not resolve. Exactly two mechanisms produce that text and only
        // one of them is ours:
        //   DISABLED COMPONENT - the launcher-pair divergence. Fixed at MainActivity.getMainActivity; read
        //     the [CORRECTED] note there before touching either side.
        //   PACKAGE BEING REPLACED mid-start - nothing app-side can prevent it, and it is one-shot.
        // This catch exists for the second. It would also mask the first, which is why the first was fixed
        // in the same change rather than left to it.
        // ⚠️ NARROW ON PURPOSE - BY TYPE, AROUND ONE CALL. A SecurityException, or a
        // RuntimeException thrown by the started activity's own creation, still crashes: those are bugs we
        // want reports for. Widening to Exception, or wrapping onStart, silences them - and note the
        // runCatching higher up already swallows every file-copy failure, so this method would then have no
        // path left that can report anything.
        // ⚠⚠ RECORDED, NOT SWALLOWED - A SILENT CATCH HERE WOULD HIDE THE VERY BUG THAT
        // PRODUCED IT. This failure has only ever been visible as a Crashlytics non-fatal: it came to light
        // because it REGRESSED there. A catch that showed a toast and nothing else would close the one
        // channel that has ever reported it - and the replace-window mechanism above cannot be fixed, only
        // observed, so that channel is the whole value of knowing about it.
        // ⚠️ SO IT GOES TO App.silentThrowFlow, WHICH EXISTS FOR EXACTLY THIS - "report it but
        // do not interrupt". Read its declaration note before changing either side; two properties of it
        // are load-bearing here:
        //   App.init's collector is UNGATED (runs on App.scope, always collecting), so every emission
        //     becomes a Crashlytics non-fatal. That is the half that makes this a report.
        //   MainActivity.setupExceptionHandler's collector is LIFECYCLE-GATED (flowWithLifecycle, STARTED)
        //     and will not fire for an Activity that has already called finish(). That is the half that
        //     makes it silent, without any suppression of our own - the toast above is deliberately the
        //     entire user-visible story.
        // ⚠️ AND NO recordException CALL HERE, WHICH IS THE REASON FOR THE FLOW RATHER THAN A
        // DETOUR AROUND IT. App.kt and HealthMonitor are the only two recordException sites; the App.init
        // collector sets six CrashKeys plus onReportRecorded() around its call, and a third site would have
        // to duplicate all of that and would drift. Emitting routes INTO that collector, so this report
        // arrives with the same keys as every other one, for free.
        try {
            startActivity(startIntent)
        } catch (e: ActivityNotFoundException) {
            // applicationContext, not `this`: finish() has already run above, so this Activity has no
            // window for a Toast to inherit. Reuses could_not_load_x with app_name instead of adding a
            // string, so there is no new resource to leave untranslated.
            Toast.makeText(
                applicationContext,
                getString(R.string.could_not_load_x, getString(R.string.app_name)),
                Toast.LENGTH_SHORT
            ).show()
            // tryEmit rather than emit-inside-a-launch, for two reasons both read from source: the flow is
            // built with onBufferOverflow = DROP_OLDEST, which its own note states "makes emit
            // non-suspending unconditionally", so tryEmit is the same operation without needing a
            // coroutine; and onStart has already called finish() above, so an async launch would be racing
            // the end of this Activity where tryEmit lands in the buffer synchronously. This is the app's
            // first tryEmit - everywhere else emits from an existing coroutine, and there is none here.
            // ⚠⚠ runCatching IS NOT PADDING: IT STOPS A THROW FROM INSIDE A CATCH BLOCK.
            // `app` is a Koin inject resolved on FIRST ACCESS, i.e. on this line, and
            // MainApplication.onCreate documents a state where Koin is NOT started - Firebase's
            // directBootAware providers can spawn this process pre-unlock, skipping androidx.startup's
            // InitializationProvider (that is what ensureKoin() exists to repair). Resolving App then
            // throws, and an exception raised while handling ActivityNotFoundException would turn the
            // fatal we just handled back into a fatal, with a stack that points at the wrong thing.
            // ⚠️ ONE RESIDUAL, STATED RATHER THAN CLAIMED AWAY: replay = 0, so an emission
            // with no live collector is simply dropped - the declaration's note is explicit that the
            // buffer "does NOT hold values for an ABSENT one". The collector starts with App itself, and
            // App is constructed via ExtensionLoader during MainApplication's init, which runs before any
            // activity in this process, so in every realistic case it is already collecting. On the
            // pre-unlock path above it may not be, and then the toast is all there is. That is worth
            // knowing when a report fails to arrive; it is not worth machinery to close.
            runCatching { app.silentThrowFlow.tryEmit(e) }
        }
    }

    private fun getTempFile(file: File): File {
        val tempFile = getTempFile()
        file.copyTo(tempFile)
        return tempFile
    }
}
