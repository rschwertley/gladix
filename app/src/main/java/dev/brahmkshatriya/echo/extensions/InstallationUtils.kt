package dev.brahmkshatriya.echo.extensions

import android.app.Activity
import android.util.Log
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.brahmkshatriya.echo.extensions.repo.ExtensionParser.Companion.PACKAGE_FLAGS
import dev.brahmkshatriya.echo.extensions.repo.FileRepository.Companion.getExtensionsFileDir
import dev.brahmkshatriya.echo.utils.ContextUtils.getTempFile
import dev.brahmkshatriya.echo.utils.PermsUtils.registerActivityResultLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object InstallationUtils {

    /** The picker closed with nothing chosen - a back-out, or a result lost to a recreation. */
    class NoFileSelected : Exception("No file selected")

    // Legacy install result codes, carried in EXTRA_INSTALL_RESULT on the deprecated
    // ACTION_VIEW / ACTION_INSTALL_PACKAGE path that installApp uses.
    // ⚠⚠ THESE ARE DECLARED HERE BECAUSE THEY CANNOT BE REFERENCED. They exist on
    // android.content.pm.PackageManager but are @hide, so they are ABSENT FROM THE PUBLIC
    // android.jar - confirmed 2026-09-14 by `javap -constants` on
    // platforms/android-37.0/android.jar, which exports only INSTALL_REASON_* and
    // INSTALL_SCENARIO_*. Values verified against AOSP
    // frameworks/base/core/java/android/content/pm/PackageManager.java. Restating them is safe
    // precisely because they are a frozen compatibility contract: the numbers are what old
    // installers return and cannot be renumbered.
    private const val INSTALL_FAILED_INSUFFICIENT_STORAGE = -4
    private const val INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7
    private const val INSTALL_FAILED_VERIFICATION_FAILURE = -22
    private const val INSTALL_FAILED_VERSION_DOWNGRADE = -25

    suspend fun installApp(activity: FragmentActivity, file: File) {
        val contentUri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.provider", file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            data = contentUri
        }
        val result = activity.waitForResult(installIntent)
        if (result.resultCode == Activity.RESULT_OK) return

        // resultCode / EXTRA_RETURN_RESULT are only contractual for the deprecated
        // ACTION_INSTALL_PACKAGE. On ACTION_VIEW many installers return RESULT_CANCELED even after a
        // SUCCESSFUL install, so a non-OK code must not be treated as failure. Ask the PackageManager
        // what is actually installed instead of branching on a value we know we cannot trust.
        // (On an app SELF-update we never reach this line at all — installing over ourselves kills
        // the process — so everything below is effectively the extension path.)
        // ⚠⚠ [CORRECTED 2026-09-14] THAT PARENTHESIS IS FALSE, AND EVERYTHING BELOW IS REACHED
        // BY THE APP PATH TOO. The process is killed only when the replace SUCCEEDS. A FAILED
        // self-update kills nothing: the installer returns a result and execution continues straight
        // to the throw. Kept rather than deleted because the reasoning is right for the success case
        // and will be re-derived by whoever reads only that half.
        // FIELD EVIDENCE, build 1100, Xiaomi 24069PC21G / Android 16:
        //     Install failed - you may need to uninstall the existing version first
        //     (resultCode=1, status=-22)   with app_update_stage=ready, app_update_gate_passed=true
        // ⚠️ AND THAT COMBINATION IS A TRIAGE SIGNAL IN ITS OWN RIGHT: a process still alive to
        // file a report while carrying app_update_stage=ready HAS ALREADY FAILED ITS SELF-INSTALL,
        // precisely because a successful one would have killed it. See CrashKeys.onAppUpdateStage.
        val apk = activity.packageManager.getPackageArchiveInfo(file.path, 0)
        val pkg = apk?.packageName
        val installed = pkg?.let {
            runCatching { activity.packageManager.getPackageInfo(it, 0) }.getOrNull()
        }
        if (apk != null && installed != null &&
            PackageInfoCompat.getLongVersionCode(installed) >=
            PackageInfoCompat.getLongVersionCode(apk)
        ) return

        // Backing out of the system dialog is not an error. Signal it exactly as uninstallApp below
        // already does, so a decline stops producing a snackbar and a Crashlytics non-fatal.
        if (result.resultCode == Activity.RESULT_CANCELED)
            throw CancellationException("Install cancelled by user")

        val status = result.data?.extras
            ?.getInt("android.intent.extra.INSTALL_RESULT", Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        // The old text ("Please uninstall the existing extension first") was a decent hint for the
        // common signature-mismatch case but wrong for every other one, and wrong for the app. Key
        // it on the CONDITION rather than on the caller: the hint only applies when a copy is
        // already installed and did not advance, which is caller-agnostic and needs no flag.
        // ⚠⚠ [SUPERSEDED 2026-09-14 - `installed != null` WAS TOO COARSE AND IS NO LONGER THE
        // KEY.] It picked the signature-mismatch hint for every failure with a copy installed, and
        // MOST OF THEM ARE NOT ONE. `status` was already in hand one line up and distinguishes them.
        // WHAT FORCED THIS, build 1100 on a Xiaomi 24069PC21G / Android 16:
        //     Install failed - you may need to uninstall the existing version first
        //     (resultCode=1, status=-22)
        // status=-22 IS NOT A SIGNATURE MISMATCH. It is INSTALL_FAILED_VERIFICATION_FAILURE; the
        // signature case is -7. Two separate rounds of triage read -22 as -7 before the constant was
        // checked, so it is written out here: VERIFY THE NUMBER, DO NOT RECALL IT.
        // ⚠️ AND THE WRONG ADVICE WAS THE EXPENSIVE KIND. The report carried
        // app_update_stage=ready, i.e. an APP SELF-UPDATE (see the corrected note above and
        // CrashKeys.onAppUpdateStage) - so "uninstall the existing version first" was telling a user
        // to DESTROY THEIR OWN APP DATA to work around a Play-Protect-style block that uninstalling
        // would not have lifted. It reaches the screen, not just Crashlytics: ExtensionsViewModel's
        // `awaitInstallation(appApk).getOrThrow()` sits in a runCatching whose getOrElse emits to
        // app.throwFlow, i.e. a snackbar.
        // ⚠️ NONE OF THIS WAS A REGRESSION ON THE EXTRA_RETURN_RESULT FIX - THAT FIX HELD. The
        // version-code comparison above ran and correctly declined to return early (installed 1100
        // was NOT >= the downloaded build), so the failure was REAL and only its EXPLANATION was
        // wrong. Behaviour was never the defect here; do not go looking for one.
        //
        // ⚠️ `isSelf` IS DERIVED, NOT PASSED, AND THAT IS DELIBERATE - it keeps the
        // caller-agnostic principle above rather than breaking it. installApp has ONE call site
        // (ExtensionsViewModel's installFileFlow collector) serving BOTH the app self-updater and
        // ImportType.App extensions, and the File it receives carries no hint of which. Comparing the
        // archive's own packageName to ours answers it from the artifact itself, so no flag can be
        // forgotten at a future second call site. It needs no null check: `installed != null` already
        // implies `apk != null`, because installed is derived from apk.packageName.
        // ⚠️ THE APP/EXTENSION SPLIT MATTERS ONLY ON THE -7 BRANCH, and it matters a lot there:
        // "uninstall it first" is a shrug for an extension and DATA LOSS for the app. Say the cost.
        // ⚠️ THE -22 TEXT NAMES TWO PLACES TO LOOK AND COMMITS TO NEITHER. The result code says
        // a verifier refused; it does NOT say WHICH, and on an OEM build there may be two (Play
        // Protect plus the vendor's own installer scan). Guessing one would send half the users who
        // hit this to a setting that is not the one blocking them, so the message says both and says
        // that it does not know. Do not "tighten" it to a single culprit without evidence that the
        // code can distinguish them - it cannot.
        // The codes go LAST in every branch so that snackbar truncation eats the diagnostics rather
        // than the advice; the full text is always reachable through the snackbar's View action.
        val codes = "(resultCode=${result.resultCode}" +
            (status?.let { ", status=$it" } ?: "") + ")"
        val isSelf = apk?.packageName == activity.packageName
        throw Exception(
            when {
                status == INSTALL_FAILED_UPDATE_INCOMPATIBLE && isSelf ->
                    "Update failed — this build is signed with a different key than the " +
                        "installed app. Installing it means uninstalling first, which erases " +
                        "the app's data. $codes"

                status == INSTALL_FAILED_UPDATE_INCOMPATIBLE ->
                    "Install failed — signed with a different key than the installed " +
                        "version. Uninstall the existing extension first, then install again. $codes"

                status == INSTALL_FAILED_VERIFICATION_FAILURE ->
                    "Install blocked by a security check — usually Play Protect " +
                        "(Play Store → profile → Play Protect), though some devices add " +
                        "their own app scan in Settings. $codes"

                status == INSTALL_FAILED_VERSION_DOWNGRADE ->
                    "Install failed — the build offered is older than the one already " +
                        "installed. $codes"

                status == INSTALL_FAILED_INSUFFICIENT_STORAGE ->
                    "Install failed — not enough free storage on the device. $codes"

                else -> "Install failed $codes"
            }
        )
    }

    // True when this app is allowed to install APKs. Since API 26 the grant is per-source, and
    // nothing here requested it — so the user met the system's cold "not allowed to install unknown
    // apps" dialog with no explanation from us. Ask first instead. Below API 26 the setting is
    // global and there is nothing to request.
    // Used for the APP update only; extension installs keep their existing behaviour.
    suspend fun FragmentActivity.ensureCanInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()
        )
        // Not every device ships a handler for this screen; a missing one must degrade to "no app
        // update", never crash the update pass.
        runCatching { waitForResult(intent) }.getOrElse { return false }
        // The settings screen returns RESULT_CANCELED whether or not the toggle was flipped, so
        // re-query rather than reading its resultCode.
        return packageManager.canRequestPackageInstalls()
    }

    suspend fun installFile(
        context: Context, fileIgnoreFlow: MutableSharedFlow<File?>, id: String, tempFile: File
    ) {
        val dir = context.getExtensionsFileDir()
        val newFile = File(dir, "$id.apk")
        dir.setWritable(true)
        newFile.setWritable(true)
        if (newFile.exists() && !newFile.delete())
            Log.d("InstallUtils", "Failed to delete existing file: $newFile")
        tempFile.renameTo(newFile)
        newFile.setWritable(false)
        dir.setReadOnly()
        fileIgnoreFlow.emit(null)
    }

    suspend fun FragmentActivity.openFileSelector(
        fileType: String = "application/octet-stream"
    ): File {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = fileType
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val result = waitForResult(intent)
        // ⚠️ A TYPE, NOT IllegalStateException, SO A BACK-OUT CAN BE TOLD FROM A
        // REAL FAILURE. Both a plain cancel and an interrupted result (see [waitForResult]) land
        // here with no URI, and neither is an error worth a snackbar or a Crashlytics non-fatal -
        // the rule the uninstall path already follows. A copy or permission failure below still
        // throws its own exception and still reports. Callers that cannot tell the difference keep
        // working: ExtensionsAddBottomSheet already discards every failure with getOrNull().
        val uri = result.data?.data ?: throw NoFileSelected()
        return getTempFile(uri)
    }

    fun Context.getTempFile(uri: Uri): File {
        val stream = contentResolver.openInputStream(uri)!!
        val tempFile = getTempFile("dat")
        tempFile.outputStream().use { outputStream ->
            stream.copyTo(outputStream)
        }
        return tempFile
    }

    suspend fun uninstallApp(activity: FragmentActivity, path: String) {
        val packageName =
            activity.packageManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)?.packageName
                ?: throw IllegalStateException("Invalid APK path or package name not found")
        // ⚠⚠ ALREADY GONE MEANS DONE, NOT A CRASH. This line used to be a bare
        // getPackageInfo call kept for its throw, so a SECOND attempt on an extension that had
        // already been removed - a double tap, or a first attempt whose result we never saw -
        // reported NameNotFoundException to the user for work that had completed. The caller
        // (ExtensionsViewModel.uninstall) turns a null result into "uninstalled successfully",
        // which is the honest answer when the package is not there.
        if (!activity.isInstalled(packageName)) return
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = "package:$packageName".toUri()
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        val result = activity.waitForResult(intent)
        // ⚠⚠ THE PACKAGE MANAGER IS THE ARBITER ON ANY NON-OK CODE, NOT THE
        // CODE ITSELF - THE SAME RULE installApp ALREADY APPLIES TO ITS OWN RESULT. Two ways a
        // completed uninstall arrives here as non-OK: the system dialog returns RESULT_CANCELED on
        // some installers even after the user confirmed, and [waitForResult] now delivers a
        // synthetic RESULT_CANCELED when the Activity was destroyed while the dialog was up. In
        // both cases the extension IS gone, and the branches below would report a cancellation or
        // a failure for work that succeeded.
        if (result.resultCode != Activity.RESULT_OK && !activity.isInstalled(packageName)) return
        when (result.resultCode) {
            Activity.RESULT_OK -> {} // uninstalled
            Activity.RESULT_CANCELED ->
                // User backed out of the system uninstall dialog (or it aborted with no detail) — NOT a
                // failure. Signal it the way the caller (ExtensionsViewModel) already handles cancellation:
                // a CancellationException is rethrown there without a "uninstalled" message and without
                // emitting to the error flow. That's what stops the "Failed to uninstall extension: null"
                // snackbar and the crash-reporter non-fatal on a plain cancel.
                throw CancellationException("Uninstall cancelled by user")

            else -> {
                // Genuine failure (e.g. RESULT_FIRST_USER). The legacy ACTION_DELETE result carries an int
                // status in EXTRA_INSTALL_RESULT (there is no PackageInstaller EXTRA_STATUS_MESSAGE on this
                // path); surface the resultCode and that status so a real failure is diagnosable instead of
                // the old bare "null".
                val status = result.data?.extras
                    ?.getInt("android.intent.extra.INSTALL_RESULT", Int.MIN_VALUE)
                    ?.takeIf { it != Int.MIN_VALUE }
                throw Exception(
                    "Failed to uninstall extension (resultCode=${result.resultCode}" +
                        (status?.let { ", status=$it" } ?: "") + ")"
                )
            }
        }
    }

    suspend fun uninstallFile(
        fileIgnoreFlow: MutableSharedFlow<File?>, path: String
    ) = withContext(Dispatchers.IO) {
        val file = File(path)
        fileIgnoreFlow.emit(file)
        file.parentFile!!.setWritable(true)
        file.setWritable(true)
        if (file.exists() && !file.delete())
            Log.d("InstallUtils", "Failed to delete file: $file")
        fileIgnoreFlow.emit(null)
    }

    /** Whether [packageName] is currently installed. Absent package -> false, never a throw. */
    private fun FragmentActivity.isInstalled(packageName: String) =
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /**
     * ⚠⚠ COMPLETES ON ACTIVITY DESTROY, BECAUSE A RESULT THAT ARRIVES AFTER A
     * RECREATION IS GONE AND THE ASKER WOULD OTHERWISE WAIT FOREVER.
     * PermsUtils.registerActivityResultLauncher mints a RANDOM UUID key per call, and
     * ActivityResultRegistry hands a restored pending result only to a callback registered under
     * THAT SAME key (activity 1.13.0, register(key, contract, callback), :161-180); nothing
     * re-registers that UUID, so a rotation or a theme change while the installer/uninstaller
     * dialog is up drops the result. Three of the four callers suspend on a ViewModel scope,
     * which SURVIVES the recreation - so the continuation was never resumed and never cancelled.
     * ExtensionsViewModel.uninstall and the update pass through ensureCanInstallPackages both
     * stranded that way, silently, for the rest of the session.
     * ⚠️ SAME DECISION AS THE CAST BROKER, AND FOR THE SAME REASON: fail fast
     * rather than make the result survive. Surviving needs a stable key registered on every
     * Activity create plus a re-deliverable request; see PermsUtils.setupPermissionRequests,
     * which completes its deferred with an `activityDestroyed` outcome from this same observer
     * shape.
     * ⚠️ RESULT_CANCELED, NOT AN EXCEPTION, AND THAT IS WHAT MAKES THE CALLERS
     * A NO-OP. Every package caller already treats a non-OK code as "ask the PackageManager what
     * actually happened" - installApp because installers lie on ACTION_VIEW,
     * ensureCanInstallPackages because the settings screen always returns CANCELED,
     * [uninstallApp] as of 2026-10-23. An interrupted result wants exactly that answer, so it is
     * delivered in exactly that form.
     * ⚠️ THE isActive GUARDS ARE REQUIRED BY THIS CHANGE, not belt and braces:
     * resuming an already-resumed continuation throws IllegalStateException, and before the
     * observer existed only one resume was reachable. After a normal result the observer sees an
     * inactive continuation and does nothing, so a later destroy costs nothing.
     */
    private suspend fun FragmentActivity.waitForResult(
        intent: Intent
    ) = suspendCancellableCoroutine { cont ->
        val contract = ActivityResultContracts.StartActivityForResult()
        val launcher = registerActivityResultLauncher(contract) {
            if (cont.isActive) cont.resume(it)
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (cont.isActive) cont.resume(ActivityResult(Activity.RESULT_CANCELED, null))
            }
        }
        lifecycle.addObserver(observer)
        cont.invokeOnCancellation {
            launcher.unregister()
            lifecycle.removeObserver(observer)
        }
        launcher.launch(intent)
    }
}