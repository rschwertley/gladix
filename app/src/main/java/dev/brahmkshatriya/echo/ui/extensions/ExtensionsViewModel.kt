package dev.brahmkshatriya.echo.ui.extensions

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getOrThrow
import dev.brahmkshatriya.echo.extensions.InstallationUtils.ensureCanInstallPackages
import dev.brahmkshatriya.echo.extensions.InstallationUtils.installApp
import dev.brahmkshatriya.echo.extensions.InstallationUtils.installFile
import dev.brahmkshatriya.echo.extensions.InstallationUtils.uninstallApp
import dev.brahmkshatriya.echo.extensions.InstallationUtils.uninstallFile
import dev.brahmkshatriya.echo.extensions.db.models.ExtensionEntity
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import dev.brahmkshatriya.echo.ui.extensions.ExtensionInstallerBottomSheet.Companion.createLinksDialog
import dev.brahmkshatriya.echo.ui.extensions.list.ExtensionListViewModel
import dev.brahmkshatriya.echo.utils.AppUpdater
import dev.brahmkshatriya.echo.utils.AppUpdater.downloadUpdate
import dev.brahmkshatriya.echo.utils.AppUpdater.getUpdateFileUrl
import dev.brahmkshatriya.echo.utils.AppUpdater.githubRateLimitReset
import dev.brahmkshatriya.echo.utils.AppUpdater.isGithubNoReleases
import dev.brahmkshatriya.echo.utils.AppUpdater.isGithubRateLimit
import dev.brahmkshatriya.echo.utils.AppUpdater.updateApp
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.ContextUtils.cleanupTempApks
import dev.brahmkshatriya.echo.utils.ContextUtils.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

class ExtensionsViewModel(
    val extensionLoader: ExtensionLoader,
    val app: App
) : ExtensionListViewModel<MusicExtension>() {
    override val extensionsFlow = extensionLoader.music
    override val currentSelectionFlow = extensionLoader.current
    override fun onExtensionSelected(extension: MusicExtension) {
        extensionLoader.setupMusicExtension(extension, true)
    }

    private val extensionDao = extensionLoader.db.extensionDao()
    fun setExtensionEnabled(extensionType: ExtensionType, id: String, checked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            extensionDao.setExtension(ExtensionEntity(id, extensionType, checked))
        }
    }

    fun changeExtension(id: String) {
        viewModelScope.launch {
            runCatching {
                val ext = extensionLoader.music.getExtensionOrThrow(id)
                extensionLoader.setupMusicExtension(ext, true)
            }.getOrElse {
                if (it is CancellationException) throw it
                app.throwFlow.emit(it)
            }
        }
    }

    val lastSelectedManageExt = MutableStateFlow(0)
    val manageExtListFlow = extensionLoader.all.combine(lastSelectedManageExt) { _, last ->
        extensionLoader.getFlow(ExtensionType.entries[last]).value
    }

    fun moveExtensionItem(toPos: Int, fromPos: Int) {
        val type = ExtensionType.entries[lastSelectedManageExt.value]
        val flow = extensionLoader.priorityMap[type]!!
        val list = extensionLoader.getFlow(type).value.map { it.id }.toMutableList()
        list.add(toPos, list.removeAt(fromPos))
        flow.value = list
    }

    // 24 HOURS IS INTENTIONAL — it matches Google Play's own daily auto-update check cadence.
    // b00018c3 (2026-06-14) setting this back to 24h was a DELIBERATE decision, not a stray revert.
    // ⚠️ The 2026-06-13 session note describing 24h as "unintentional scope creep" is WRONG. Do not
    // "restore" 2h on the strength of that note. If this value ever changes, it should be for a
    // reason recorded right here.
    private val updateTime = 1000 * 60 * 60 * 24 // Check every 24hrs
    private fun shouldCheckForExtensionUpdates(): Boolean {
        val check = app.settings.getBoolean("check_for_updates", true)
        if (!check) return false
        val lastUpdateCheck = app.context.getFromCache<Long>("last_update_check") ?: 0
        val elapsed = System.currentTimeMillis() - lastUpdateCheck
        return elapsed > updateTime
    }

    private suspend fun message(msg: String) {
        app.messageFlow.emit(Message(msg))
    }

    fun update(activity: FragmentActivity, force: Boolean) = viewModelScope.launch {
        if (!force) extensionLoader.isLoaded.first { it }
        if (!force && !shouldCheckForExtensionUpdates()) return@launch
        app.context.saveToCache("last_update_check", System.currentTimeMillis())
        activity.cleanupTempApks()
        // Automatic (force = false) is an UNPROMPTED background check and must stay silent unless
        // something actually happens — Play doesn't announce that it looked, and F-Droid removed even
        // its index-progress notification. `force` is the correct discriminator: it is false only at
        // configureExtensionsUpdater's startup call, and true at all three user-initiated entry
        // points (ManageExtensionsFragment, SettingsBottomSheet, SettingsOtherFragment). Progress
        // messages further down ("downloading update for X") are deliberately NOT gated — those fire
        // only when work is genuinely under way, which is worth telling the user about either way.
        if (force) message(app.context.getString(R.string.checking_for_extension_updates))
        // The install-permission prompt is threaded in as a lambda rather than checked here, so it
        // only ever fires once an update actually exists (updateApp calls it after resolving the
        // URL, before downloading). Declining returns null, which falls through to the extension
        // branch below exactly as "no app update" already does.
        val appApk = updateApp(app) { activity.ensureCanInstallPackages() }
        runCatching {
            if (appApk != null) {
                // 0L, not 0: saveToCache picks its folder from T::class.java.simpleName, so an Int
                // literal wrote to the "int" folder while shouldCheckForExtensionUpdates reads
                // getFromCache<Long> out of "long". This reset has therefore never taken effect.
                app.context.saveToCache("last_update_check", 0L)
                awaitInstallation(appApk).getOrThrow()
            } else {
                var anyUpdateFound = false
                var anyFailed = false
                var rateLimited: Throwable? = null
                // ⚠⚠ `for` WITH A `break`, NOT forEach - THE LOOP MUST BE ABLE TO STOP. Once
                // GitHub has rate-limited the IP every remaining request is guaranteed to fail, and
                // continuing spends quota that other apps on the same IP also need, turns one cause
                // into N identical reports, and delays the reset for nothing.
                for (ext in extensionLoader.all.value) {
                    // announce = force: an automatic pass records check failures without interrupting.
                    when (val result = updateExt(ext, announce = force)) {
                        ExtUpdate.Updated -> anyUpdateFound = true
                        ExtUpdate.Failed -> anyFailed = true
                        ExtUpdate.UpToDate -> Unit
                        // Deliberately NOT anyFailed, and deliberately not a break. See
                        // ExtUpdate.PermanentlyFailed. The user has already been told (manual) or the
                        // report already filed silently (automatic); all this arm decides is that the
                        // throttle survives, so the next pass is tomorrow rather than next launch.
                        ExtUpdate.PermanentlyFailed -> Unit
                        is ExtUpdate.RateLimited -> {
                            rateLimited = result.error
                            break
                        }
                    }
                }
                // ONE message for the pass. Always shown, even on an automatic check: unlike "all
                // extensions up to date", this is the answer to "why has nothing updated in months",
                // and the user cannot ask a question they do not know they have.
                // Raw text, not an R.string: the message is BUILT in AppUpdater.githubHttpError
                // from GitHub's X-RateLimit-Reset header, so the concrete time is the whole point
                // and a static resource could not carry it. (R.string.error does not exist; do not
                // reach for it.)
                if (rateLimited != null) {
                    message(
                        rateLimited.message ?: "GitHub rate limit reached, try again later"
                    )
                    // ⚠⚠ EXACTLY ONE NON-FATAL PER PASS, AND THE COUNT IS THE POINT. Zero would
                    // be wrong: this issue sat MUTED SINCE MAY and rose to 8 events on build 1106
                    // alone, and a silent fix would remove the only evidence that it is still
                    // happening. Eight per pass is what caused the mute. One per pass keeps the trend
                    // readable at a rate nobody needs to mute.
                    // silentThrowFlow, not throwFlow: the user has already been told by the message()
                    // above, and a second snackbar for the same event is the noise being removed.
                    app.silentThrowFlow.emit(rateLimited)
                }
                // Only claim "up to date" when we actually found out AND the user asked. Two
                // separate gates:
                //  - anyFailed: a failed check or download used to land here too, so a transient
                //    GitHub error reassured the user that everything was current. On failure we stay
                //    silent rather than adding a second message — the failure already produced its
                //    own snackbar via throwFlow.
                //  - force: an unprompted background check that finds nothing says NOTHING. A manual
                //    check still confirms the result, because the user asked and deserves an answer.
                // ⚠⚠ THE ZEROING STAYS FOR ORDINARY FAILURES AND MUST NOT FIRE ON A RATE
                // LIMIT. Its intent is right - a transient failure should not cost 24 hours of not
                // checking - but it cannot tell a transient failure from a SELF-INFLICTED one, and
                // for a rate limit "retry sooner" is exactly backwards.
                // ⚠⚠ THE LOOP IT CLOSED, WHICH IS WHY THIS MATTERS: a rate-limited pass fails
                // every extension -> anyFailed -> throttle zeroed -> the NEXT LAUNCH runs a full pass
                // -> rate-limited again. The failure removed the only thing preventing the failure.
                // ⚠️ NEITHER HALF WAS A MISTAKE ON ITS OWN, AND THE RECORD SHOULD NOT READ AS
                // ONE. Both landed in the same August 2026 session ("last_update_check zeroed on
                // failure and fixed (0L), throttle restored to 24h, automatic pass silenced"). The
                // zeroing was INTENDED; it had simply never taken effect, because saveToCache picks
                // its folder from T::class.java.simpleName and the old `0` Int literal wrote to int/
                // while this reads getFromCache<Long> out of long/ - silent in both directions, which
                // is exactly why the zeroing looked harmless when it was written. Fixing the folder
                // made a deliberate change live, and the two together made a loop. Neither change
                // considered the rate-limit case.
                // ⚠️ AND "CHECKS ON EVERY COLD START" HAS NOW BEEN REACHED TWICE BY TWO ROUTES.
                // The same session had it from a throttle wrongly set to 2h on the strength of a bad
                // summary note. Same user-visible outcome, unrelated mechanism - so a report of
                // over-frequent checking does NOT identify its own cause.
                if (rateLimited != null) {
                    // Back off until the limit actually resets rather than for a full day: line ~109
                    // already stamped `now`, so subtracting the window and adding the wait lands the
                    // next eligible check just after the reset. No reset time -> leave the 24h stamp.
                    val waitMs = rateLimited.githubRateLimitReset()
                        ?.let { it * 1000 - System.currentTimeMillis() }
                        ?.coerceIn(0L, updateTime.toLong())
                    if (waitMs != null) app.context.saveToCache(
                        "last_update_check",
                        System.currentTimeMillis() - updateTime + waitMs + 60_000L
                    )
                } else if (anyFailed) app.context.saveToCache("last_update_check", 0L)
                else if (!anyUpdateFound && force)
                    message(app.context.getString(R.string.all_extensions_up_to_date))
            }
        }.getOrElse { if (it is CancellationException) throw it; app.throwFlow.emit(it) }
    }

    data class PromptResult(
        val file: File,
        val accepted: Boolean,
        val type: ImportType,
        val id: String,
        val supportedLinks: List<String>
    )

    val installPromptFlow = MutableSharedFlow<File>()
    private val promptResultFlow = MutableSharedFlow<PromptResult>()
    val installFileFlow = MutableSharedFlow<File>()
    val installedFlow = MutableSharedFlow<Pair<File, Result<Unit>>>()
    val linksDialogFlow = MutableSharedFlow<Pair<File, List<String>>>()

    private suspend fun install(id: String, type: ImportType, file: File): Result<Unit> {
        return if (type == ImportType.App) awaitInstallation(file)
        else runCatching { installFile(app.context, extensionLoader.fileIgnoreFlow, id, file) }
    }

    // SUBSCRIBE-THEN-EMIT, VIA onSubscription. NOT COSMETIC, AND DO NOT REORDER BACK.
    //
    // This used to be `installFileFlow.emit(file)` followed by `installedFlow.first { ... }` — emit first,
    // subscribe second. Both flows are replay-0 MutableSharedFlows, and a replay-0 emission with no
    // subscriber is DISCARDED, not deferred. So any path where the collector finishes installing before
    // this coroutine gets as far as subscribing loses the result permanently, and `first { }` then waits
    // forever: no message and no failure. A silent strand, on the app-update path that has never once
    // executed.
    // ⚠️ [CORRECTED 2026-09-12] THIS USED TO ADD "and the caller has already written
    // last_update_check, so nothing retries for 24h". THAT NO LONGER HOLDS, and the reason is worth
    // keeping: the reset above is now `0L` rather than `0`, so it writes to the folder
    // shouldCheckForExtensionUpdates actually reads (saveToCache picks its folder from
    // T::class.java.simpleName). While it was `0` the reset silently went to the `int` folder and the 24h
    // lockout really did apply. It does not now - a strand leaves last_update_check at 0, so the NEXT
    // launch re-checks. The strand is still a hang; it is no longer also a 24-hour one.
    //
    // The window is normally closed by luck rather than by design: configureExtensionsUpdater's collector
    // calls installApp, which suspends almost immediately at waitForResult (launching the installer), and
    // that suspension hands the main thread back so this coroutine can subscribe. The luck runs out when
    // installApp fails WITHOUT ever suspending — FileProvider.getUriForFile throws IllegalArgumentException
    // synchronously for a path the provider does not cover — because runCatching then emits the failure
    // with no suspension in between.
    //
    // onSubscription runs its block AFTER this collector is registered and BEFORE any value is collected,
    // which is the exact guarantee needed: the install cannot start until someone is listening for how it
    // ends. Same replay-0 mechanism as the Aug/Sep queueFlow defect, opposite direction — that one lost an
    // emission because the SUBSCRIBER was gone, this one because the subscriber had not arrived yet.
    //
    // ⚠⚠ [CORRECTED 2026-09-12] THIS USED TO SAY THE CONFIG-CHANGE HALF WAS "DELIBERATELY LEFT
    // OPEN". IT IS MOSTLY CLOSED, AND RE-SCOPING IT WOULD BE RE-SOLVING A SOLVED PROBLEM.
    // The claim was: "if the ACTIVITY is destroyed (a config change) while an install is in flight,
    // installFileFlow's emit reaches no collector at all and is dropped the same way."
    // THE IN-FLIGHT CASE IS HANDLED, by the DefaultLifecycleObserver registered in
    // configureExtensionsUpdater a few lines below: on Activity destroy it emits
    // `file to Result.failure(CancellationException())`, and `first { }` here receives it. It works because
    // the AWAITING side outlives the Activity - ExtensionsViewModel is Activity-scoped, so viewModelScope
    // and installedFlow both survive a config change; only the COLLECTOR dies and is recreated.
    // ⚠️ WHAT ACTUALLY REMAINS is much narrower: awaitInstallation must BEGIN inside the
    // recreation gap, between the old Activity's onDestroy and the new one's configureExtensionsUpdater.
    // Then onSubscription's emit finds no collector, `currentFile` was never set so the observer cannot
    // help either (and has already run), and this waits forever. A millisecond race, on a path that has
    // never executed. CLOSED 2026-09-12 as not worth retention machinery - see the census note in
    // AppUpdater for why the messageFlow remedy is not available here anyway.
    private suspend fun awaitInstallation(file: File): Result<Unit> {
        return installedFlow
            .onSubscription { installFileFlow.emit(file) }
            .first { it.first == file }.second
    }

    fun promptDismissed(
        file: File, install: Boolean, type: ImportType, id: String, supportedLinks: List<String>
    ) = viewModelScope.launch {
        promptResultFlow.emit(PromptResult(file, install, type, id, supportedLinks))
    }

    // Tri-state. A plain Boolean conflated "no update available" with "we never found out", which
    // is what let update() report "all extensions up to date" straight after a failed check or a
    // failed download. Failed also covers a failed INSTALL, which previously returned `true` — it
    // suppressed the up-to-date message correctly but for the wrong reason, and reported nothing.
    // ⚠⚠ RateLimited IS NOT "Failed WITH A NICER NAME" - IT SELECTS A DIFFERENT PASS-LEVEL
    // BEHAVIOUR AND CARRIES THE ERROR TO DO IT. A normal failure is per-extension: report it, keep
    // going, the next repo may be fine. A rate limit is IP-wide: every remaining repo is guaranteed
    // to fail, so the pass stops, reports ONCE, and backs off until the limit resets. The throwable
    // rides along because only it knows the reset time (X-RateLimit-Reset).
    private sealed interface ExtUpdate {
        data object Updated : ExtUpdate
        data object UpToDate : ExtUpdate
        data object Failed : ExtUpdate
        data class RateLimited(val error: Throwable) : ExtUpdate

        /**
         * A failure retrying cannot fix - today only a 404 from the releases endpoint.
         *
         * ⚠⚠ DISTINCT FROM [Failed] FOR EXACTLY ONE REASON: it must not set `anyFailed`,
         * because that zeroes last_update_check and a dead repo would then re-run the whole pass on
         * every launch, for ever. It is NOT quieter than [Failed] - the error still reaches
         * throwFlow (manual) or silentThrowFlow (automatic) from getExtensionUpdate, untouched.
         * ⚠️ AND UNLIKE [RateLimited] IT DOES NOT BREAK THE LOOP. A rate limit means the
         * next repo would fail identically; a missing repo says nothing about any other, so the
         * pass continues and the remaining extensions are still checked.
         */
        data object PermanentlyFailed : ExtUpdate
    }

    private suspend fun updateExt(
        ext: Extension<*>, show: Boolean = false, announce: Boolean = true,
    ): ExtUpdate {
        val file = getExtensionUpdate(ext, show, announce).getOrElse {
            // Order matters only for readability - the two classifiers are disjoint by construction
            // (different exception types). Both walk the cause chain; see their notes.
            return when {
                it.isGithubRateLimit() -> ExtUpdate.RateLimited(it)
                it.isGithubNoReleases() -> ExtUpdate.PermanentlyFailed
                else -> ExtUpdate.Failed
            }
        } ?: return ExtUpdate.UpToDate
        val type = ext.metadata.importType
        if (type == ImportType.File) {
            installPromptFlow.emit(file)
            val result = promptResultFlow.first { it.file == file }
            if (!result.accepted) return ExtUpdate.Updated
        }
        install(ext.id, type, file).onFailure {
            if (it is CancellationException) throw it
            app.throwFlow.emit(it)
            return ExtUpdate.Failed
        }
        message(app.context.getString(R.string.extension_updated_successfully, ext.name))
        return ExtUpdate.Updated
    }

    fun update(extension: Extension<*>) = viewModelScope.launch { updateExt(extension, true) }

    fun installWithPrompt(files: List<File>) = viewModelScope.launch {
        files.forEach { file ->
            installPromptFlow.emit(file)
            val result = promptResultFlow.first { it.file == file }
            if (!result.accepted) return@forEach
            install(result.id, result.type, result.file).onFailure {
                if (it is CancellationException) throw it
                app.throwFlow.emit(it)
                return@forEach
            }
            message(app.context.getString(R.string.extension_installed_successfully))
            if (result.type == ImportType.App)
                linksDialogFlow.emit(file to result.supportedLinks)
        }
    }

    fun uninstall(activity: FragmentActivity, extension: Extension<*>) = viewModelScope.launch {
        val fileResult = runCatching {
            uninstallFile(extensionLoader.fileIgnoreFlow, extension.metadata.path)
        }.exceptionOrNull()
        val appResult = runCatching {
            uninstallApp(activity, extension.metadata.path)
        }.exceptionOrNull()
        val result = if (extension.metadata.importType == ImportType.App) appResult else fileResult
        if (result == null) message(app.context.getString(R.string.extension_uninstalled_successfully))
        else if (result is CancellationException) throw result
        else app.throwFlow.emit(result)
    }

    companion object {
        fun FragmentActivity.configureExtensionsUpdater() {
            val viewModel by viewModel<ExtensionsViewModel>()
            collect(viewModel.installPromptFlow) {
                ExtensionInstallerBottomSheet.newInstance(it).show(supportFragmentManager, null)
            }
            collect(viewModel.linksDialogFlow) {
                createLinksDialog(it.first, it.second)
            }

            viewModel.update(this, false)
            // ⚠️ NEVER RESET AFTER A SUCCESSFUL INSTALL, SO THE OBSERVER BELOW FIRES ON EVERY
            // LATER ACTIVITY DESTROY - once per rotation for the rest of the session - emitting a spurious
            // `Result.failure(CancellationException())` for a file that installed fine. Harmless TODAY and
            // recorded rather than fixed: awaitInstallation has already returned by then, so nothing is
            // subscribed to receive it. Written down because it explains an emission someone will
            // eventually see in a capture and reasonably read as a failed install.
            // It stops being harmless the moment anything else subscribes to installedFlow.
            var currentFile: File? = null
            collect(viewModel.installFileFlow) {
                currentFile = it
                viewModel.installedFlow.emit(it to runCatching { installApp(this, it) })
            }
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    val file = currentFile ?: return
                    viewModel.run {
                        viewModelScope.launch {
                            installedFlow.emit(
                                file to Result.failure(CancellationException())
                            )
                        }
                    }
                }
            })
        }
    }

    private val client = OkHttpClient()
    // Result<File?> rather than File?, so the caller can tell the two null cases apart:
    // success(null) = nothing to update, failure = we never found out. Both already emit to
    // throwFlow; only the return value was lossy.
    /**
     * @param announce whether a FAILURE OF THE CHECK may interrupt the user. True for a pass the user
     *   started; false for the automatic startup pass, where the failure is recorded but not shown.
     *
     * ⚠⚠ IT GATES ONLY THE FIRST FAILURE SITE, AND THE LINE IS THE `downloading update for X`
     * MESSAGE BELOW - NOT `force`. Everything before that emit happened without the user being told
     * anything, so staying silent costs them nothing. Everything AFTER it follows a progress message
     * they have already seen, and suppressing those failures would leave "downloading update for
     * Spotify" on screen with no outcome, forever. That progress emit is ungated on purpose (see the
     * note in update()): it fires only when work is genuinely under way.
     */
    private suspend fun getExtensionUpdate(
        extension: Extension<*>,
        show: Boolean = false,
        announce: Boolean = true,
    ): Result<File?> {
        val currentVersion = extension.version
        val updateUrl = extension.metadata.updateUrl ?: return Result.success(null)
        val url = runCatching {
            getUpdateFileUrl(currentVersion, updateUrl, client, app.context).getOrThrow()
        }.getOrElse {
            if (it is CancellationException) throw it
            val e = it.named(extension.name)
            // ⚠⚠ A RATE LIMIT DOES NOT EMIT HERE. throwFlow has TWO collectors - App.kt's
            // recordException AND setupExceptionHandler's snackbar - so an emission per extension
            // is a snackbar AND a Crashlytics non-fatal per extension, all from ONE cause. That is
            // what produced eight identical MissingFieldExceptions within one second from three
            // unrelated repos. The pass reports it once instead; see update().
            // ⚠️ AND THIS PATH WAS NEVER "SILENCED" BY THE force GATE. That gate covers only
            // the status messages ("checking for extension updates", "all extensions up to date");
            // failures emitted unconditionally, so an AUTOMATIC background pass has been showing
            // users snackbars for a check they never asked for.
            // A rate limit is reported ONCE for the whole pass (see update()), never per extension.
            // Anything else goes to the screen only if the user asked; it is RECORDED either way, via
            // App.silentThrowFlow - suppressing the emit entirely would suppress the Crashlytics
            // report too, and that is how a rising signal disappears.
            if (!e.isGithubRateLimit()) {
                if (announce) app.throwFlow.emit(e) else app.silentThrowFlow.emit(e)
            }
            return Result.failure(e)
        }
        if (url == null) {
            if (show) message(
                app.context.getString(R.string.no_update_available_for_x, extension.name)
            )
            return Result.success(null)
        }
        // ⚠⚠ THIS EMIT IS THE LINE `announce` IS DRAWN AT. Everything above it happened
        // without the user being told anything; everything below follows THIS message.
        message(app.context.getString(R.string.downloading_update_for_x, extension.name))
        val file = runCatching {
            downloadUpdate(app.context, url, client).getOrThrow()
        }.getOrElse {
            if (it is CancellationException) throw it
            val e = it.named(extension.name)
            // ⚠️ DELIBERATELY IGNORES `announce` - DO NOT "FINISH" THE GATING BY ADDING IT.
            // The user has just been told "downloading update for X" on ANY pass, automatic
            // included. Silence after that is not quiet, it is a hang: the message stays on screen
            // and nothing ever resolves it. Same reasoning at updateExt's install() failure.
            app.throwFlow.emit(e)
            return Result.failure(e)
        }
        return Result.success(file)
    }

    // getUpdateFileUrl/downloadUpdate wrap failures as UpdateException, which carries no identity, so
    // an extension update error rendered as a bare "Error while updating" — indistinguishable from an
    // app-update failure in a user report. Re-tag with the extension name. The cause chain is carried
    // over unchanged and anything that isn't an UpdateException passes straight through, so the
    // Result contract and control flow are identical to before.
    private fun Throwable.named(name: String): Throwable =
        (this as? AppUpdater.UpdateException)?.let { AppUpdater.UpdateException(it.cause, name) } ?: this

}