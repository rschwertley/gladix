package dev.brahmkshatriya.echo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.UserManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import dev.brahmkshatriya.echo.di.DI
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.utils.AppShortcuts.configureAppShortcuts
import dev.brahmkshatriya.echo.utils.AppUpdater
import dev.brahmkshatriya.echo.utils.CoroutineUtils
import dev.brahmkshatriya.echo.utils.CrashKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.androix.startup.KoinStartup
import org.koin.core.KoinApplication
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.koinConfiguration
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(KoinExperimentalAPI::class)
class MainApplication : Application(), KoinStartup, SingletonImageLoader.Factory {

    // Single source of truth for Koin setup — referenced by BOTH the App-Startup path (onKoinStartup) and
    // the deferred ensureKoin() fallback, so the two configs can never drift.
    private fun KoinApplication.applyKoinModules() {
        androidContext(this@MainApplication)
        modules(DI.appModule)
        workManagerFactory()
    }

    override fun onKoinStartup() = koinConfiguration { applyKoinModules() }

    private val settings by inject<SharedPreferences>()
    private val extensionLoader by inject<ExtensionLoader>()

    // Guards the deferred init to exactly-once across the (mutually exclusive) unlocked onCreate path and
    // the ACTION_USER_UNLOCKED receiver — belt-and-suspenders against any delivery/TOCTOU race.
    private val initDone = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // First thing on process birth: record the monotonic start time for the process_age_s crash key.
        // No DI / settings / Firebase touched here, so it's safe even on a pre-unlock Direct-Boot spawn.
        CrashKeys.markProcessStart()
        // Static for the process and needed on EVERY report, so record it here rather than recomputing.
        // Its absence cost a full triage round: a Pixel 10 report could not be tied to a Play install
        // without it, and a wrong theory about how the binary was installed took a round to disprove.
        CrashKeys.recordInstallSource(this)
        CoroutineUtils.setDebug()
        // Firebase's directBootAware providers can spawn this process PRE-UNLOCK, where the (non-
        // directBootAware) androidx.startup InitializationProvider is skipped — so Koin isn't started AND
        // credential-encrypted storage (settings) isn't readable. Defer all settings/DI-dependent init
        // until the user unlocks; the normal (unlocked) launch runs it inline exactly as before.
        if (isUserUnlocked()) initAfterUnlock()
        else registerUnlockReceiver()
    }

    // UserManager is a core system service (effectively never null); default to "unlocked" so a null
    // service can't wedge the app into permanent deferral. isUserUnlocked is API 24+ (minSdk 24).
    private fun isUserUnlocked() =
        getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    // On a pre-unlock spawn the InitializationProvider was skipped and App Startup does NOT re-run at
    // unlock, so Koin can still be down when we reach the deferred init — start it (idempotently, with the
    // shared declaration) before resolving any inject. No-op on the normal path (App Startup already ran).
    private fun ensureKoin() {
        if (GlobalContext.getOrNull() == null) startKoin { applyKoinModules() }
    }

    // The real onCreate work, run either inline (unlocked launch) or once at ACTION_USER_UNLOCKED. Order is
    // load-bearing: Koin first, THEN the settings inject / CE read, THEN shortcuts.
    private fun initAfterUnlock() {
        if (!initDone.compareAndSet(false, true)) return
        ensureKoin()
        // ⚠⚠ THIS SITS HERE BECAUSE A RECORDED RULE REQUIRES IT, NOT AS A JUDGEMENT CALL. The
        // Direct Boot work left the instruction: any future onCreate addition touching CE storage or
        // inject MUST go behind the isUserUnlocked guard - inside initAfterUnlock - not before it.
        // This reads SharedPreferences, which is CREDENTIAL-ENCRYPTED and unreadable on a pre-unlock
        // Direct Boot spawn. Moving it up into onCreate for startup-cost reasons would BREAK A
        // DOCUMENTED CONTRACT, not trade one cost for another. Note that its neighbour
        // CrashKeys.recordInstallSource IS in onCreate, deliberately: it touches no settings and no
        // DI, which is exactly why it may live there and this may not.
        // The CAS above already makes "once per process" free.
        //
        // ⚠⚠ KNOWN AND ACCEPTED, NOT A DEFECT: THIS APP EXCLUDES NOTHING FROM CLOUD BACKUP.
        // AndroidManifest.xml declares no `allowBackup`, no `dataExtractionRules`, no
        // `fullBackupContent`, and res/xml holds no rules file (checked 2026-09-14). allowBackup
        // therefore DEFAULTS TO TRUE, and everything in the app's data dir is eligible for cloud
        // backup and device-to-device transfer, restorable onto a different device.
        // ⚠️ RECORDED BECAUSE IT WAS RAISED AS A DEFECT AND IS NOT ONE - so that the next
        // person to notice it does not re-raise it, and so that anyone who DOES need to change it can
        // see what was weighed rather than starting over. WHY IT IS ACCEPTED:
        //   ⚠️ IT IS INHERITED, NOT INTRODUCED HERE. Upstream Echo's AndroidManifest declares
        //     none of the three either, so this fork did not create the exposure and changing it is a
        //     divergence, not a repair.
        //   ⚠️ IT IS THE PLATFORM WORKING AS DESIGNED, AND ARGUABLY WHAT USERS WANT. Auto
        //     Backup is opt-OUT; backups have been end-to-end encrypted with a key derived from the
        //     device lock screen since Android 9; and they restore only to the SAME Google account.
        //     The intended behaviour is exactly what happens - set up a new phone and your apps come
        //     back with their state, logins included. Most apps do not opt out. Password managers and
        //     banking apps do, and that is the company this would be choosing to keep.
        // WHAT IS ELIGIBLE, from a survey of the getSharedPreferences call sites and the Room
        // builders - listed so a reopening starts from facts rather than a re-survey:
        //   ExtensionDatabase's UserEntity.data - a serialised User whose `extras` carry the login
        //     secret. For Deezer that is the ARL cookie (DeezerExtension's manual login form declares
        //     key = "arl" and reads it back out of user.extras). A Room db in databases/ is
        //     backup-eligible on the same default. THIS IS THE ITEM THE DECISION ACTUALLY TURNS ON.
        //   Per-extension settings - one SharedPreferences file per extension, named `<TYPE>-<id>`
        //     (ExtensionUtils.extensionPrefId), holding whatever that extension's SettingsProvider
        //     chooses to store. WE DO NOT CONTROL THAT VOCABULARY, which is the part most likely to
        //     change underneath this decision.
        //   Device-shaped state - fx_<name> / GLOBAL_FX audio-effect settings, which describe the
        //     hardware they were tuned on; gladix_health_monitor; history_sort; the main
        //     SETTINGS_NAME file (where the version-code keys read below also live).
        // ⚠⚠ WHEN TO REOPEN THIS DELIBERATELY: if an extension ever stores something that
        // genuinely must not cross devices - a device-bound token, a per-install key, anything whose
        // validity is tied to THIS phone rather than to the account. That is a change in the third
        // bullet's vocabulary, and nothing warns us when it happens. The fix would then be
        // `dataExtractionRules` excluding the specific file, NOT allowBackup=false, which would throw
        // away the restore behaviour users expect in order to protect one key.
        // ⚠️ AND IF YOU GO TO CHECK WHAT IS ELIGIBLE: `adb backup` RETURNS 0 BYTES ON AT LEAST
        // ONE DEVICE HERE AND THAT PROVES NOTHING. It exercises the legacy local-transport path, which
        // modern Android has largely retired; cloud backup and D2D transfer do not go through it. An
        // empty adb backup is evidence THAT ROUTE CANNOT ANSWER THE QUESTION, not evidence that
        // nothing is backed up. Find another method before concluding anything.
        // The version-code keys read below are defended against a restore by their own three-term
        // gate (see AppUpdater.recordSelfUpdateOnLaunch) - that gate exists because a restored value
        // would be WRONG, not because backup itself is.
        AppUpdater.recordSelfUpdateOnLaunch(this, settings)
        applyLocale(settings)
        // [2026-11-15] The fallback for LauncherEntryReset.UpdateReceiver, for an update that landed before unlock. Here, on
        // the after-unlock path, and on its own thread (see LauncherEntryReset).
        LauncherEntryReset.runAsync(this)
        // ⚠⚠ LOOKS LIKE THE BUILD-974 CRASH SHAPE AND IS NOT. EXAMINED TWICE; LEAVE IT.
        // A bare CoroutineScope(Dispatchers.IO) has no CoroutineExceptionHandler, so a throw inside it
        // reaches the DEFAULT UNCAUGHT HANDLER and kills the process — which is how a Spotify 500 became a
        // FATAL on build 974. Taking `extensionLoader` as an argument makes this look like the same gap,
        // and a reader scanning for bare scopes WILL stop here. It was flagged that way on 2026-09-10 and
        // the flag was wrong.
        // WHY IT IS SAFE: configureAppShortcuts IMMEDIATELY HANDS OFF (AppShortcuts:105-108). Its whole body
        // is `val scope = loader.scope; val musicExt = loader.music; scope.launch { … }` — two property
        // reads and a re-launch onto ExtensionLoader.scope, WHICH CARRIES THE HANDLER. Nothing that can
        // throw from extension code ever runs on THIS scope; the collectLatest that touches extensions runs
        // on the guarded one.
        // ⚠️ WHAT WOULD BREAK IT: adding work to this launch body, or changing configureAppShortcuts to do
        // anything before its hand-off. Then this scope needs the handler — use app.scope (Koin-provided,
        // see DI.kt) rather than adding a second bare one. See the called-vs-launched note at
        // App.exceptionHandler.
        CoroutineScope(Dispatchers.IO).launch { configureAppShortcuts(extensionLoader) }
    }

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                runCatching { unregisterReceiver(this) }
                initAfterUnlock()
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ACTION_USER_UNLOCKED is a protected system broadcast (only the OS sends it), so NOT_EXPORTED
            // is correct and safer — mirrors PlayerService.clearQueueReceiver.
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        // TOCTOU: if the device unlocked between the isUserUnlocked() check in onCreate and this
        // registration, the broadcast may already be gone — re-check and run inline. initDone's CAS makes
        // this safe if the receiver also fires.
        if (isUserUnlocked()) {
            runCatching { unregisterReceiver(receiver) }
            initAfterUnlock()
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    // 0.15 (≈38MB of a 256MB heap) instead of 0.25 (≈64MB): decoded covers are SOFTWARE
                    // bitmaps on the Java heap (allowHardware(false), needed by the blur/crop transforms), so
                    // this cache counts against the heap cap. 0.15 still holds ~75–125 downsampled covers
                    // (~4–8 screens), so normal browsing never re-decodes; only very deep scroll-back re-decodes
                    // from the disk cache (fast, no network, no correctness change).
                    .maxSizePercent(context, 0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image-cache"))
                    .maxSizeBytes(1024 * 1024 * 100) // 100MB
                    .build()
            }
            .allowHardware(false)
            .crossfade(true)
            .build()
    }

    override fun getPackageName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching {
            val stackTrace = Looper.getMainLooper().thread.stackTrace
            val isChromiumCall = stackTrace.any { trace ->
                trace.className.equals(CLASS_NAME, ignoreCase = true)
                        && FUNCTION_SET.any { trace.methodName.equals(it, ignoreCase = true) }
            }
            if (isChromiumCall) return spoofedPackageName(applicationContext)
        }
        return super.getPackageName()
    }

    private fun spoofedPackageName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(CHROME_PACKAGE, PackageManager.GET_META_DATA)
            CHROME_PACKAGE
        }.getOrElse {
            SYSTEM_SETTINGS_PACKAGE
        }
    }

    companion object {
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"
        private const val CLASS_NAME = "org.chromium.base.BuildInfo"
        private val FUNCTION_SET = setOf("getAll", "getPackageName", "<init>")

        fun getCurrentLanguage(sharedPref: SharedPreferences) =
            sharedPref.getString("language", null) ?: "system"

        fun setCurrentLanguage(sharedPref: SharedPreferences, locale: String?) {
            sharedPref.edit { putString("language", locale) }
            applyLocale(sharedPref)
        }

        fun applyLocale(sharedPref: SharedPreferences) {
            val value = sharedPref.getString("language", null) ?: "system"
            val locale = if (value == "system") LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(value)
            AppCompatDelegate.setApplicationLocales(locale)
        }

        val languages = mapOf(
            "ar" to "العربية",
            "as" to "Assamese",
            "be" to "Беларуская",
            "bn" to "বাংলা",
            "ca" to "Català",
            "de" to "Deutsch",
            "es" to "Español",
            "fa" to "فارسی",
            "fr" to "Français",
            "en" to "English",
            "hi" to "हिन्दी",
            "hng" to "Hinglish",
            "hu" to "Magyar",
            "in" to "Bahasa Indonesia",
            "it" to "Italiano",
            "iw" to "עברית",
            "ja" to "日本語",
            "ko" to "한국어",
            "lv" to "Latviski",
            "ms" to "Bahasa Melayu",
            "pl" to "Polski",
            "pt" to "Português",
            "pt-rBR" to "Português (Brasil)",
            "ru" to "Русский",
            "sa" to "संस्कृतम्",
            "si" to "සිංහල",
            "sk" to "Slovenčina",
            "sr" to "Српски",
            "ta" to "தமிழ்",
            "th" to "ไทย",
            "tl" to "Filipino",
            "tr" to "Türkçe",
            "uk" to "Українська",
            "vi" to "Tiếng Việt",
            "zh-rCN" to "中文 (简体)",
            "zh-rTW" to "中文 (繁體)"
        )
    }
}