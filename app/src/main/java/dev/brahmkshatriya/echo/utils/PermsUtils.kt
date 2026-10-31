package dev.brahmkshatriya.echo.utils

import android.Manifest
import android.Manifest.permission.READ_MEDIA_AUDIO
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.di.PermissionResult
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import kotlinx.coroutines.launch
import java.util.UUID

object PermsUtils {

    fun ComponentActivity.checkAppPermissions(onGranted: suspend () -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOfNotNull(
            Triple(
                Manifest.permission.POST_NOTIFICATIONS,
                getString(R.string.notifications),
                getString(R.string.notifications_permission_summary)
            ),
            Triple(
                READ_MEDIA_AUDIO,
                getString(R.string.audio),
                getString(R.string.audio_permission_summary)
            ),
//            Triple(
//                Manifest.permission.READ_MEDIA_VIDEO,
//                getString(R.string.video),
//                getString(R.string.video_permission_summary)
//            ),
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Triple(
//                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
//                getString(R.string.video),
//                getString(R.string.video_permission_summary)
//            ) else null
        ) else listOfNotNull(
            Triple(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                getString(R.string.read_storage),
                getString(R.string.audio_permission_summary)
            ),
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) Triple(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                getString(R.string.write_storage),
                getString(R.string.write_storage_permission_summary)
            ) else null
        )
        getPermissionsLauncher(perms, onGranted = {
            if (it == READ_MEDIA_AUDIO) lifecycleScope.launch {
                onGranted()
            }
        })
    }

    private fun ComponentActivity.getPermissionsLauncher(
        perms: List<Triple<String, String, String>>,
        onCancel: (String) -> Unit = {},
        onGranted: (String) -> Unit = {},
        onRequest: (String) -> Unit = {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                startActivity(this)
            }
        }
    ) = run {
        val contract = ActivityResultContracts.RequestMultiplePermissions()
        val notGranted = perms.filterNot {
            ContextCompat.checkSelfPermission(this, it.first) == PackageManager.PERMISSION_GRANTED
        }
        val launcher = if (notGranted.isNotEmpty())
            registerActivityResultLauncher(contract) { result ->
                val map = perms.associateBy { it.first }
                result.forEach { (p, granted) ->
                    val perm = map[p]!!
                    if (granted) onGranted(p)
                    else MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.x_permission_required, perm.second))
                        .setMessage(perm.third)
                        .setPositiveButton(getString(R.string.okay)) { _, _ -> onRequest(p) }
                        .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                            Toast.makeText(
                                this,
                                getString(R.string.x_was_not_granted, perm.second),
                                Toast.LENGTH_SHORT
                            ).show()
                            onCancel(p)
                        }.show()
                }
            }
        else {
            perms.forEach { onGranted(it.first) }
            null
        }
        launcher?.launch(perms.map { it.first }.toTypedArray())
    }

    /**
     * ⚠⚠ THE ONE PLACE A SERVICE CAN GET A RUNTIME PERMISSION ASKED FOR. Collects
     * App.permissionRequestFlow and answers each request through this Activity; the asker waits on
     * the deferred. Wired from MainActivity.onCreate beside [checkAppPermissions].
     *
     * ⚠️ EVERY EXIT COMPLETES THE DEFERRED, AND THAT IS THE WHOLE CORRECTNESS
     * ARGUMENT - a path that returns without completing it leaves the asker waiting out its
     * timeout. Already-granted, launcher result, both dialog buttons and the dialog's CANCEL
     * (back press / outside tap) all complete it; the launcher self-unregisters via
     * [registerActivityResultLauncher].
     * ⚠️ RATIONALE BEFORE THE RE-REQUEST, WHICH IS THE OPPOSITE ORDER TO
     * [checkAppPermissions] AND DELIBERATE. That one explains AFTER a refusal and offers Settings,
     * which suits permissions asked for at launch with no immediate user intent. Here the user has
     * just picked a Cast device, so the ask is already in context: a first-time request shows the
     * system dialog with no preamble (shouldShowRequestPermissionRationale is false then), and the
     * explanation appears only on a second ask, where the system itself says one is warranted.
     * ⚠️ PERMANENT DENIAL IS THE ASKER'S TO REPORT, NOT OURS. We return false and
     * log; the asker owns the user-facing message because only it knows what was refused and what
     * that costs - see CastSessionController, which attaches a Settings action in that case.
     *
     * ⚠⚠ A CONFIGURATION CHANGE WHILE THE PROMPT IS UP LOSES THE RESULT, AND THE
     * LIFECYCLE GUARD IS WHAT STOPS THAT BECOMING A TWO-MINUTE WAIT. Rotation, a dark-mode switch
     * or a locale change destroys this Activity while the system dialog is showing, and the
     * result then has nowhere to go: [registerActivityResultLauncher] mints a RANDOM UUID key per
     * call, and ActivityResultRegistry delivers a restored pending result only to a callback that
     * registers under THAT SAME KEY (activity 1.13.0, ActivityResultRegistry.register(key,
     * contract, callback), :161-180 - the pending result is handed over inside register). The new
     * Activity's broker registers nothing until the next REQUEST arrives, so the saved result
     * (onSaveInstanceState, :245-253) is never claimed and the callback never fires.
     * ⚠️ SO THE ANSWER IS TO FAIL FAST, NOT TO SURVIVE IT. Making the result
     * survive would need a STABLE key registered on every Activity create plus a re-deliverable
     * request, and the flow has replay = 0 - the request is consumed. An immediate
     * `activityDestroyed` lets the asker say so and the user retry in one tap, which beats a
     * two-minute silence ending in `timeout`. DECIDED 2026-10-20; revisit only if a real user
     * rotates mid-prompt often enough to notice.
     * ⚠️ THE OBSERVER IS LEFT ATTACHED rather than removed on completion:
     * complete() on an already-completed deferred is a no-op, so a late onDestroy costs nothing,
     * and removing it would need a main-thread hop out of invokeOnCompletion for no gain.
     */
    fun ComponentActivity.setupPermissionRequests(app: App) =
        observe(app.permissionRequestFlow) { request ->
            val perm = request.permission
            val held = ContextCompat.checkSelfPermission(this, perm)
            if (held == PackageManager.PERMISSION_GRANTED) {
                request.result.complete(PermissionResult(true, "alreadyGranted"))
                return@observe
            }
            // See the note above: this is the only thing that answers a request whose result was
            // lost to a configuration change.
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    request.result.complete(PermissionResult(false, "activityDestroyed"))
                }
            })
            // `prefix` carries the rationale step into the launcher's own outcome, so one string
            // says both what was shown and what was decided.
            val ask = { prefix: String ->
                registerActivityResultLauncher(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    // deniedPermanently is read AFTER the result: shouldShow only drops to false
                    // once the system has decided it will not ask again.
                    val outcome = when {
                        granted -> "granted"
                        shouldShowRequestPermissionRationale(perm) -> "denied"
                        else -> "deniedPermanently"
                    }
                    request.result.complete(PermissionResult(granted, prefix + outcome))
                }.launch(perm)
            }
            if (!shouldShowRequestPermissionRationale(perm)) {
                ask("")
                return@observe
            }
            // ⚠⚠ DISMISS, NOT CANCEL, AND A `decided` FLAG BECAUSE DISMISS FIRES
            // FOR EVERYTHING. setOnCancelListener covers a back press and an outside tap but NOT
            // the dialog being torn down with the Activity, which is exactly the configuration
            // change above - that path would have left the deferred pending. onDismiss covers all
            // three, at the cost of also firing after a button, so the buttons mark the decision
            // and dismiss only speaks when nothing else did.
            // ⚠️ THE FLAG IS NOT BELT-AND-BRACES FOR complete()'s FIRST-WINS. The
            // positive button LAUNCHES rather than completes, so without the flag the dismiss that
            // follows it would complete `rationaleDismissed` FIRST and the real grant would land
            // on an already-completed deferred and be discarded. Order matters here, not just
            // idempotence.
            var decided = false
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cast_local_network_title)
                .setMessage(R.string.cast_local_network_rationale)
                .setPositiveButton(R.string.okay) { _, _ ->
                    decided = true
                    ask("rationaleShown+")
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    decided = true
                    request.result.complete(PermissionResult(false, "rationaleDeclined"))
                }
                .setOnDismissListener {
                    if (!decided) {
                        request.result.complete(PermissionResult(false, "rationaleDismissed"))
                    }
                }
                .show()
        }

    /**
     * The app's own settings page, for a permission the system will no longer prompt for. Same
     * Intent [getPermissionsLauncher]'s onRequest default uses; shared so the two cannot drift.
     */
    fun Context.openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    private const val BATTERY_OPTIMIZATION_ASKED = "battery_optimization_asked"

    fun ComponentActivity.checkBatteryOptimization() {
        val pm = getSystemService<PowerManager>() ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val settings = getSettings()
        if (settings.getBoolean(BATTERY_OPTIMIZATION_ASKED, false)) return
        settings.edit { putBoolean(BATTERY_OPTIMIZATION_ASKED, true) }
        // ⚠️ GUARDED (2026-10-31): this runs from MainActivity.onCreate, and a device whose settings app has no
        // handler for this action (some TVs) threw ActivityNotFoundException at first launch. Skipping the
        // prompt is the right outcome there - there is nothing to ask the user. The flag above is written
        // first either way, so it is asked at most once.
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    fun <I, O> ComponentActivity.registerActivityResultLauncher(
        contract: ActivityResultContract<I, O>,
        block: (O) -> Unit
    ): ActivityResultLauncher<I> {
        val key = UUID.randomUUID().toString()
        var launcher: ActivityResultLauncher<I>? = null
        val callback = ActivityResultCallback<O> {
            block.invoke(it)
            launcher?.unregister()
        }

        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                launcher?.unregister()
            }
        })

        launcher = activityResultRegistry.register(key, contract, callback)
        return launcher
    }
}