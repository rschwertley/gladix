package dev.brahmkshatriya.echo.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build.SUPPORTED_ABIS
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutionException

object ContextUtils {
    fun appVersion() = BuildConfig.VERSION_NAME + " " + BuildConfig.BUILD_TYPE
    fun getArch(): String {
        SUPPORTED_ABIS.firstOrNull()?.let { return it }
        return System.getProperty("os.arch")
            ?: System.getProperty("os.product.cpu.abi")
            ?: "Unknown"
    }

    fun Context.copyToClipboard(label: String?, string: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, string))
            return
        } catch (e: RuntimeException) {
            // OEM/OS clipboard restriction — an INTERACT_ACROSS_USERS check misfiring (seen on some Moto,
            // MIUI, and Android 14-15). We can't hold that permission and can't fix the OS; a clipboard
            // failure must never crash. Record it, then fall through to tell the user.
            Log.w("ContextUtils", "copyToClipboard: setPrimaryClip failed", e)
        }
        // Independent, best-effort guard: surface the failure. Its own try so a Toast failure can neither
        // crash nor hide the copy failure already logged above.
        try {
            Toast.makeText(this, R.string.couldnt_copy_to_clipboard, Toast.LENGTH_SHORT).show()
        } catch (_: RuntimeException) {
            // best effort — nothing more we can do
        }
    }

    fun <T> LifecycleOwner.observe(flow: Flow<T>, block: suspend (T) -> Unit) =
        lifecycleScope.launch {
            flow.flowWithLifecycle(lifecycle).collectLatest(block)
        }

    fun <T> LifecycleOwner.collect(flow: Flow<T>, block: suspend (T) -> Unit) =
        lifecycleScope.launch {
            flow.collect {
                runCatching { block(it) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }

    /**
     * A CANCELLED future is not an error, and does not reach [block] at all.
     *
     * `future.get()` on a cancelled future throws CancellationException, which runCatching turns into a
     * failed Result — and every caller treats a failed Result as something to report. That is how normal
     * teardown was arriving as a non-fatal: PlayerViewModel.onCleared calls controllerFutureRelease(),
     * which is MediaController.releaseFuture(playerFuture), which CANCELS a still-pending connection. On a
     * slow device the ViewModel can clear about a second into process life, before the controller has
     * finished connecting, so the app reported its own teardown as a crash (seen on a Redmi Go, Android 10,
     * player_state=1). ControllerHelper holds the same release lambda for the widget.
     *
     * Returning without calling [block] is safe for all three call sites, checked one by one:
     *  - PlayerService.getController — [block] only ever runs on success; the sole other branch is the
     *    error report we are removing. There is no "cancelled" consumer.
     *  - PlayerViewModel.likeCurrent — discards the result apart from reporting failures.
     *  - PlayerViewModel.likeById — this one DOES pass a resultCode on to a caller
     *    (MediaMoreBottomSheet.likeFromSheet, which routes RESULT_ERROR_BAD_VALUE to the extension-only
     *    path and otherwise refreshes). Not calling it is still correct: a cancelled setRating means the
     *    command was abandoned with the session, so the like did not happen and there is nothing to
     *    reconcile. The sheet's like button is rebuilt from state.isLiked via itemResultFlow — it is not
     *    gated on the callback, has no loading or disabled state waiting on it, and so cannot be stranded.
     *
     * Checked with isCancelled rather than by catching CancellationException: for a ListenableFuture the
     * two are equivalent (get() throws it only when the future itself was cancelled), and the explicit
     * query cannot be confused with a CancellationException surfacing from inside the computation. The
     * future is terminal by the time a listener runs, so the flag cannot flip under us.
     *
     * NOT a coroutine cancellation — this is a plain listener callback, so there is no structured
     * concurrency to propagate to and nothing to rethrow. (Different situation from CoroutineUtils'
     * future handling, where a naive rethrow would hang Media3.)
     *
     * ⚠️ CANCELLED IS NOT THE SAME AS A DEAD SERVICE, and this guard does NOT re-blind the
     * service-restart-loop diagnostic that 172f1edb added here (the loop that was invisible until it
     * OOM'd, build 1039). Verified in the media3-session 1.11.0 sources, not assumed:
     *  - `MediaController.release()` ends with `if (connectionNotified) … else
     *    connectionCallback.onRejected()`, and `MediaControllerHolder.maybeSetException()` turns that into
     *    `setException(SecurityException("Session rejected the connection request."))`.
     *  - `onServiceDisconnected` and `onBindingDied` both route to `getInstance()::release`.
     * So a service dying or crashing mid-connect FAILS the future — it does not cancel it, and it is still
     * reported. The only way to reach a cancelled future is `MediaController.releaseFuture`, whose first
     * act is `controllerFuture.cancel(false)`; in this app that is called only from our own teardown
     * (PlayerViewModel.onCleared, ControllerHelper). Cancelled == we tore it down. Failed == it broke.
     *
     * Genuine failures are still reported, now UNWRAPPED. `get()` wraps everything in ExecutionException,
     * whose stack is generated at the `get()` call site — identical for every failure through this helper —
     * so Crashlytics grouped unrelated faults together under one ExecutionException issue. Unwrapping
     * SPLITS that by real cause; it cannot merge anything that is currently distinct. Expect existing
     * mutes/closes on the old ExecutionException issue to stop applying and pre-existing faults to surface
     * as new issues. `isLoginRequired()` and `throwingExtensionId()` both walk the cause chain, so
     * LoginRequired suppression and extension attribution are unaffected.
     */
    fun <T> Context.listenFuture(future: ListenableFuture<T>, block: (Result<T>) -> Unit) {
        future.addListener({
            if (future.isCancelled) return@addListener
            val result = try {
                Result.success(future.get())
            } catch (e: ExecutionException) {
                Result.failure(e.cause ?: e)
            } catch (e: Throwable) {
                Result.failure(e)
            }
            block(result)
        }, ContextCompat.getMainExecutor(this))
    }

    fun <T> LifecycleOwner.emit(flow: MutableSharedFlow<T>, value: T) {
        lifecycleScope.launch {
            flow.emit(value)
        }
    }

    const val SETTINGS_NAME = "settings"
    fun Context.getSettings() = getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)!!

    private fun Context.getTempDir() = cacheDir.resolve("apks").apply { mkdirs() }
    fun Context.getTempFile(ext: String = "apk"): File =
        File.createTempFile("temp", ".$ext", getTempDir())

    /** Anything older than this in the temp dir is residue; anything newer may be in use. */
    private const val TEMP_APK_MAX_AGE_MS = 60L * 60 * 1000

    /**
     * ⚠⚠ AGE-GATED, NEVER RECURSIVE - IT USED TO BE deleteRecursively() AND THAT RACED A
     * DOWNLOAD IN FLIGHT. THREE INDEPENDENT FLOWS write into this one directory via [getTempFile]:
     * AppUpdater.downloadUpdate (the app's own APK), ExtensionsViewModel's extension update, and
     * AddViewModel's add-extension download. Only the extension updater calls this, from
     * ExtensionsViewModel.update - which runs at STARTUP as well as from the three user entry
     * points, so it fires in the same first seconds as an offered app update.
     * ⚠️ WHAT THE WIPE DID, AND WHY IT LOOKED LIKE A NETWORK FAULT (Crashlytics 1122,
     * sideloaded, Android 14, process_age_s=0, app_update_stage=offered): "Incomplete download: got
     * 0 of 9804420 bytes". Unlinking an open file on Android leaves the fd valid, so copyTo kept
     * writing to the orphaned inode and FINISHED NORMALLY - okhttp saw a complete fixed-length body
     * and raised nothing. Only the follow-up File.length() noticed, because it stats the PATH, and
     * File.length() returns 0 for a missing file instead of throwing. The 9.8 MB download had
     * almost certainly succeeded and we threw it away.
     * ⚠️ SO THE AGE GATE IS THE FIX, not the length check it exposed. One hour is far
     * longer than any download plus the user's trip through the installer, so a finished APK waiting
     * to be installed is never removed either - which was the second way a recursive wipe could
     * bite, with no download in flight at all.
     * ⚠️ FILES ONLY, AND NON-RECURSIVE BY CONSTRUCTION: listFiles() on this directory,
     * isFile-gated. Nothing here creates subdirectories (getTempFile is createTempFile), so there is
     * nothing to recurse into; if that ever changes, do NOT reach for deleteRecursively again.
     * ⚠️ cacheDir CAN STILL BE WIPED BY ANDROID under storage pressure, which produces the
     * identical symptom and is not preventable from here. That is why downloadUpdate also checks the
     * file still exists before handing it to the installer.
     */
    fun Context.cleanupTempApks() {
        val cutoff = System.currentTimeMillis() - TEMP_APK_MAX_AGE_MS
        getTempDir().listFiles()?.forEach {
            if (it.isFile && it.lastModified() < cutoff) it.delete()
        }
    }
}