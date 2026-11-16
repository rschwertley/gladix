package dev.brahmkshatriya.echo

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import kotlin.concurrent.thread

/**
 * [2026-11-15] RE-ENABLES MainActivity WHERE THE RETIRED "Back Animations" SETTING DISABLED IT. The setting swapped the
 * launcher between MainActivity and MainActivity$Back with setComponentEnabledSetting, and that state lives in
 * PackageManager, outside app data, so it survives the update that removed the setting. Without this, a device that had the
 * setting on would keep MainActivity disabled, with no launcher entry, and links that open the app would not resolve,
 * because MainActivity now holds every filter and the $Back alias holds none (AndroidManifest, at the alias).
 *
 * ⚠⚠ ONLY MainActivity IS TOUCHED. The alias is left enabled where it was enabled, on purpose: a widget, media session or
 * posted notification built before the update holds an explicit PendingIntent naming $Back, and that still opens
 * MainActivity through the alias. So no widget refresh is needed, and none is done (the user's decision, Option B).
 * ⚠️ ACTS ONLY ON AN EXPLICIT DISABLED, so on every other install it is one read and nothing else.
 * ⚠️ TWO TRIGGERS, ONE OUTCOME: UpdateReceiver right after the update, and MainApplication.initAfterUnlock as the fallback
 * for an update that ran before the user unlocked. A receiver-started process runs both. Synchronized, and the second
 * call reads DEFAULT and does nothing.
 * ⚠️ OFF THE MAIN THREAD: 1bff9b02 fixed an ANR in MainApplication.onCreate, so no PackageManager call runs there. A plain
 * thread rather than app.scope, so neither trigger needs Koin. Every call is inside runCatching, so nothing can escape to
 * the default uncaught handler.
 */
object LauncherEntryReset {

    fun runAsync(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        thread(name = "LauncherEntryReset") {
            try {
                run(app)
            } finally {
                onDone()
            }
        }
    }

    @Synchronized
    private fun run(context: Context) {
        runCatching {
            val pm = context.packageManager
            val main = ComponentName(context, MainActivity::class.java)
            if (pm.getComponentEnabledSetting(main) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return
            pm.setComponentEnabledSetting(
                main, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
            )
        }
    }

    /**
     * [2026-11-15] ⚠⚠ NO KOIN HERE, AND NOT directBootAware, BOTH ON PURPOSE. The August Direct Boot crash was a process
     * started before unlock reaching "KoinApplication has not been started" (see MainApplication.onCreate). Play can install
     * updates while the phone is locked. A component that is not directBootAware is not run before the first unlock after a
     * boot (inference from the platform's Direct Boot rules, not read here). The reset needs only PackageManager, so this
     * receiver injects nothing, and it also returns at once if the user is not unlocked. An update that lands before the
     * first unlock is then covered by the initAfterUnlock fallback.
     */
    class UpdateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
            if (!unlocked) return
            val pending = goAsync()
            runAsync(context) { pending.finish() }
        }
    }
}
