package dev.brahmkshatriya.echo.ui.common

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.net.toFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import dev.brahmkshatriya.echo.MainActivity
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
import dev.brahmkshatriya.echo.playback.PlayerCommands
import dev.brahmkshatriya.echo.playback.PlayerService
import dev.brahmkshatriya.echo.playback.ResumptionUtils.hasSavedQueue
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.download.DownloadFragment
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel
import dev.brahmkshatriya.echo.ui.extensions.WebViewUtils.onWebViewIntent
import dev.brahmkshatriya.echo.ui.media.MediaFragment
import dev.brahmkshatriya.echo.ui.settings.TvPairingFragment
import dev.brahmkshatriya.echo.di.App
import androidx.lifecycle.lifecycleScope
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.android.ext.android.get

object FragmentUtils {
    inline fun <reified T : Fragment> Fragment.openFragment(
        view: View? = null, bundle: Bundle? = null,
    ) {
        val viewModel by activityViewModels<UiViewModel>()
        openFragment<T>(id, parentFragmentManager, viewModel, view, bundle, caller = this)
    }

    inline fun <reified T : Fragment> openFragment(
        cont: Int,
        manager: FragmentManager,
        viewModel: UiViewModel,
        view: View? = null,
        bundle: Bundle? = null,
        caller: Fragment? = null,
    ) {
        viewModel.collapsePlayer()
        manager.commit {
            setReorderingAllowed(true)
            addToBackStack(null)
            val fragment = createFragment<T>(bundle)
            // Use the calling fragment directly — findFragmentById is unreliable when multiple
            // fragments share the same container (e.g. the tab hide/show pattern in MainFragment).
            val old = caller ?: manager.findFragmentById(cont)
            if (old != null) hide(old)
            add(cont, fragment)
            setPrimaryNavigationFragment(fragment)
        }
    }

    inline fun <reified T : Fragment> createFragment(
        bundle: Bundle? = null
    ): T = T::class.java.getDeclaredConstructor().newInstance().apply { arguments = bundle }

    inline fun <reified T : Fragment> FragmentActivity.openFragment(
        view: View? = null, bundle: Bundle? = null, cont: Int = R.id.navHostFragment
    ) {
        val oldFragment = supportFragmentManager.findFragmentById(cont)
        if (oldFragment == null) {
            val viewModel by viewModel<UiViewModel>()
            openFragment<T>(cont, supportFragmentManager, viewModel, view, bundle)
        } else oldFragment.openFragment<T>(view, bundle)
    }

    inline fun <reified F : Fragment> Fragment.addIfNull(
        id: Int, tag: String, args: Bundle? = null
    ) {
        childFragmentManager.run {
            if (findFragmentByTag(tag) == null) commit {
                val fragment = createFragment<F>(args)
                add(id, fragment, tag)
            }
        }
    }

    fun MainActivity.setupIntents(
        uiViewModel: UiViewModel,
    ) {
        addOnNewIntentListener { onIntent(uiViewModel, it) }
        addOnNewIntentListener {
            val isFromGearhead = try {
                referrer?.host == "com.google.android.projection.gearhead"
            } catch (e: Exception) {
                false
            }
            // Existence only (decides whether to expand the player) — cheap stat, NOT a main-thread decode
            // of the saved queue, which ANRs on a large queue + slow device (same fix as the button gate).
            if (isFromGearhead && hasSavedQueue(this@setupIntents)) {
                uiViewModel.changePlayerState(STATE_EXPANDED)
                uiViewModel.changeMoreState(STATE_COLLAPSED)
            }
        }
        onIntent(uiViewModel, intent)
    }

    private fun FragmentActivity.onIntent(uiViewModel: UiViewModel, intent: Intent?) {
        this.intent = null
        intent ?: return
        val fromNotif = intent.hasExtra("fromNotification")
        if (fromNotif) uiViewModel.run {
            if (playerSheetState.value == STATE_HIDDEN) {
                // Existence only (decides whether to send resumeCommand) — cheap stat, not a main-thread decode.
                if (hasSavedQueue(this@onIntent)) {
                    PlayerService.getController(get<App>()) { controller ->
                        controller.sendCustomCommand(
                            PlayerCommands.resumeCommand,
                            Bundle.EMPTY
                        )
                        controller.release()
                    }
                    changePlayerState(STATE_EXPANDED)
                    changeMoreState(STATE_COLLAPSED)
                }
                return
            }
            changePlayerState(STATE_EXPANDED)
            changeMoreState(STATE_COLLAPSED)
            return
        }
        val fromDownload = intent.hasExtra("fromDownload")
        if (fromDownload) {
            uiViewModel.selectedSettingsTab.value = 0
            openFragment<DownloadFragment>()
            return
        }
        val webViewRequest = intent.hasExtra("webViewRequest")
        if (webViewRequest) {
            onWebViewIntent(intent)
            return
        }
        val uri = intent.data
        when (uri?.scheme) {
            "echo" -> runCatching { openItemFragmentFromUri(uri) }
            "gladix" -> {
                if (uri.host == "pair") {
                    val code = uri.getQueryParameter("code").orEmpty()
                    openFragment<TvPairingFragment>(null, TvPairingFragment.getBundle(code))
                }
            }
            // ⚠⚠ GUARDED ON HOST AND PREFIX EVEN THOUGH THE MANIFEST ALREADY IS - see
            // openItemFragmentFromGladixLink for why that is not redundant.
            "https" -> runCatching { openItemFragmentFromGladixLink(uri) }
            "file" -> {
                val viewModel by viewModel<ExtensionsViewModel>()
                viewModel.installWithPrompt(listOf(uri.toFile()))
            }
        }
    }

    private fun FragmentActivity.openItemFragmentFromUri(uri: Uri) {
        when (val extensionType = uri.host) {
            "music" -> {
                val extensionId = uri.pathSegments.firstOrNull()
                if (extensionId == null) {
                    createSnack("No extension id found")
                    return
                }
                val type = uri.pathSegments.getOrNull(1)
                val id = uri.pathSegments.getOrNull(2)
                if (id == null) {
                    // ⚠⚠ ECHO-SCHEME ONLY - THIS MUST NOT MOVE INTO THE SHARED HELPER.
                    // `echo://music/<ext>` WITH NO TYPE OR ID MEANS "SWITCH TO THIS EXTENSION", and
                    // that is a real producer: AppShortcuts builds exactly that form for its
                    // per-extension launcher shortcuts. A GLADIX HTTPS LINK ALWAYS CARRIES ALL THREE
                    // PARAMS BY CONSTRUCTION, so a missing id there is a MALFORMED LINK, not a switch
                    // request - and inheriting this would make a truncated share link SILENTLY CHANGE
                    // THE USER'S EXTENSION instead of reporting a bad link. Same call, opposite
                    // meaning; that is the difference the extraction exists to preserve.
                    val vm by viewModel<ExtensionsViewModel>()
                    vm.changeExtension(extensionId)
                    return
                }
                openMediaItemFragment(
                    extensionId, type, id, uri.getQueryParameter("name").orEmpty()
                )
            }

            else -> {
                createSnack("Opening $extensionType extension is not possible")
            }
        }
    }

    /**
     * Opens a media detail page from the four things every item link carries.
     *
     * ⚠⚠ SHARED BY echo:// AND https://, AND THE CALLERS DELIBERATELY DO NOT SHARE EVERYTHING.
     * Only the item-building and the fragment open are common. What each caller keeps to itself:
     *   echo://  the `id == null` -> changeExtension fallback (see the note at that branch), and the
     *            host dispatch, where `host` is the extension TYPE ("music") rather than a domain.
     *   https:// the host/prefix guard and "this link is malformed" handling, because every param is
     *            mandatory in that format.
     * Widening this helper to absorb either one would give the other a behaviour it must not have.
     */
    private fun FragmentActivity.openMediaItemFragment(
        extensionId: String, type: String?, id: String, name: String,
    ) {
        val item: EchoMediaItem? = when (type) {
            "artist" -> Artist(id, name)
            "track" -> Track(id, name)
            "album" -> Album(id, name)
            "playlist" -> Playlist(id, name, false)
            else -> null
        }
        // ⚠⚠ NO "radio" BRANCH, AND THAT IS A KNOWN GAP RATHER THAN AN OVERSIGHT.
        // EchoMediaItem includes Radio, so `radio` is expressible in both link formats and would land
        // here as `else -> null` -> "Invalid item type".
        // ⚠⚠ [AMENDED] ITS REAL TRIGGER IS NOT "WHEN GLADIX LINKS EXIST" - IT IS "WHEN AN
        // EXTENSION PRODUCES A RADIO WITH A STABLE, RESOLVABLE ID", AND NO SHIPPING ONE DOES. An
        // earlier version of this note said the share feature would make it live; that was wrong,
        // because generating a type=radio link needs an extension willing to claim its radio is
        // shareable, and none is:
        //   Deezer  radios carry another entity's id - an artist's, or a RANDOMLY CHOSEN seed
        //           track's - and loadRadio now sets isShareable = false.
        //   Offline radios carry `radio_${item.hashCode()}` on a local-files extension, and Offline
        //           implements no ShareClient, so nothing it produces is offered at all.
        // ⚠️ AND THE SHARE ACTION REQUIRES ShareClient (see MediaDetailsViewModel.share), which
        // makes this MORE latent rather than less: an extension must BOTH implement ShareClient AND
        // leave isShareable true on a Radio before a type=radio link can exist.
        // ⚠️ THE RENDERING HALF IS SETTLED, CONTRARY TO THE EARLIER NOTE: MediaFragment CAN show
        // a Radio detail page - MediaHeaderAdapter has an `is Radio` arm in its already-exhaustive type
        // `when`, Cached loads its tracks through RadioClient.loadTracks, and the shelf feed is
        // deliberately null for Radio. So this is one `"radio" -> Radio(id, name)` arm when wanted.
        // THE REASON TO WAIT IS ID QUALITY, NOT THE UI. Deliberately not built.
        if (item == null) {
            createSnack("Invalid item type")
            return
        }
        openFragment<MediaFragment>(null, MediaFragment.getBundle(extensionId, item, false))
    }

    /**
     * A shared TRACK link: never interrupt, never lose the user's place.
     *
     *   PLAYING      open the track's own More sheet over the current screen. No details page behind
     *                it, so dismissing leaves the user exactly where they were - intended.
     *   NOT PLAYING  play it (replacing the queue) and open the full player.
     *
     * ⚠⚠ PlayerState.current, NOT PlayerViewModel.isPlaying, AND THE DIFFERENCE IS THE WHOLE
     * RELIABILITY OF THIS BRANCH. isPlaying is a MutableStateFlow(false) written only by
     * PlayerUiListener, which needs a CONNECTED MediaController - on a link launch into a fresh
     * Activity it still reads its initial false while audio is playing. PlayerState is a Koin
     * singleton whose Current is written service-side by PlayerEventListener, so it is already
     * correct here.
     * ⚠️ AND A COLD PROCESS NEEDS NO SPECIAL CASE: playback runs in a foreground service in
     * THIS process, so "audio playing" and "cold process" are mutually exclusive. A cold start reads
     * current == null, which is the honest answer - nothing is playing.
     *
     * ⚠⚠ WHY THE EXPAND IS A ONE-SHOT WAIT AND NOT changePlayerState RIGHT HERE. Three
     * recorded regressions live in this exact situation (a fresh, not-yet-laid-out Activity):
     * an unconditional expand on cold start left the bar stuck at the top for 30-60s; a state change
     * before layout left it stuck permanently; and a fresh Activity with current == null parked the
     * sheet fully offscreen. changePlayerState now defers until laid out on its own, but it ALSO
     * returns silently when playerBehaviour is not yet set - which on a cold-start link it is not.
     * So the expand waits for a real track instead of a clock.
     * ⚠⚠ !isPlaceholder IS WHAT MAKES IT ORDERING-INDEPENDENT. On a cold start the service
     * ALSO restores the saved queue, and PlayerViewModel seeds a PLACEHOLDER Current from that
     * snapshot (isPlaying = false) whenever current is still null. If that placeholder lands first,
     * MainActivity's current-observer takes its null->non-null edge with playWhenReady false and
     * COLLAPSES the sheet - and its `!hadTrack` edge never fires again, so the real track would play
     * in the mini player. Waiting on a NON-placeholder current makes the placeholder irrelevant
     * whether it arrives first, last, or not at all.
     * ⚠️ THE mediaId MATCH IS EXACT, VERIFIED RATHER THAN ASSUMED: playItem builds the queue
     * entry straight from the stub handed to play() - MediaState.Unloaded(extId, item) - and
     * MediaItemUtils.build does setMediaId(state.item.id). The id never passes through extension
     * code on this path, so it equals the link's `i=` verbatim for every extension. (Contrast
     * Cached.loadMedia, which compares CANONICAL ids because a details-page load may legitimately
     * canonicalise - that is a different path and does not apply here.) Without the match, the
     * restore's own real track would satisfy the wait and expand the player on the wrong song.
     * ⚠️ AND IT IS BOUNDED. If the shared track never arrives - a load failure, a removed
     * extension - the wait expires and nothing happens, so a later unrelated play can never inherit
     * this expand. Doing nothing is the right failure: the user still has whatever screen they were
     * on, and play() has already reported its own error through the usual path.
     * ⚠️ ACCEPTED: on a cold start the placeholder may still COLLAPSE the sheet for a frame
     * or two before this expands it. Removing that would mean changing MainActivity's
     * current-observer, which three separate fixes have already converged on. Not worth it.
     */
    private fun FragmentActivity.openSharedTrack(extensionId: String, track: Track) {
        val playerState by inject<PlayerState>()
        val playerViewModel by viewModel<PlayerViewModel>()
        val uiViewModel by viewModel<UiViewModel>()
        if (playerState.current.value?.isPlaying == true) {
            MediaMoreBottomSheet.show(
                this, R.id.navHostFragment, extensionId, track, false
            )
            return
        }
        // loaded = false: this is a link stub, exactly as FeedClickListener passes for an unloaded
        // item. Same command a normal track tap sends, so queue replacement, the "Playing from"
        // context and history all behave identically.
        playerViewModel.play(extensionId, track, false)
        lifecycleScope.launch {
            val arrived = withTimeoutOrNull(SHARED_TRACK_EXPAND_TIMEOUT_MS) {
                playerState.current.first {
                    it != null && !it.isPlaceholder && it.mediaItem.mediaId == track.id
                }
            }
            if (arrived != null) uiViewModel.changePlayerState(STATE_EXPANDED)
        }
    }

    // Generous on purpose: this bounds a FAILURE, not a latency budget. A cold start has to create
    // the service, connect a controller and resolve a stream before the track can land, and
    // expanding late is harmless while expiring early means a shared link silently does not open the
    // player. StreamableLoader's own ceiling is withTimeout(30_000), so this is deliberately shorter
    // than the slowest thing it waits on - if the load is going to take longer than this, it is
    // failing rather than loading.
    private const val SHARED_TRACK_EXPAND_TIMEOUT_MS = 15_000L

    // ⚠⚠ THE HOST AND PREFIX ARE DUPLICATED FROM AndroidManifest.xml AND THAT IS DELIBERATE.
    // The manifest filter already restricts what reaches us, so this guard is redundant TODAY. It is
    // here for the day a SECOND https filter is added - handling deezer.com links is already an
    // identified option - because on that day every such URI would arrive at this same `"https" ->`
    // branch and, without the guard, be reported as a malformed Gladix link. Keep these two strings
    // in step with the manifest; there is no way to share a literal between XML and Kotlin here.
    private const val GLADIX_LINK_HOST = "rschwertley.github.io"
    // ⚠⚠ THE TRAILING SLASH IS LOAD-BEARING - DO NOT TIDY IT OFF. pathPrefix is a LITERAL
    // prefix match, so "/gladix/o/" cannot claim "/gladix/october-update.html" (after "/gladix/o"
    // comes "c", not "/"). Written as "/gladix/o" it would claim that page and every other path on
    // the project site beginning with "o". The single-letter segment is only safe BECAUSE of the
    // slash. Keep this in step with AndroidManifest.xml's pathPrefix.
    private const val GLADIX_LINK_PREFIX = "/gladix/o/"

    /**
     * Builds the share link for [item], or null when no correct link can be made.
     *
     * ⚠⚠ IT LIVES BESIDE THE PARSER ON PURPOSE. openItemFragmentFromGladixLink reads
     * e/t/i/n and this writes them; keeping producer and consumer in one file is what stops the
     * two drifting, and it is why this does not duplicate GLADIX_LINK_HOST/PREFIX. Those two
     * constants are already one copy too many (the manifest holds the third) - do not make a
     * fourth somewhere else.
     *
     * ⚠⚠ RETURNING null IS A REAL ANSWER AND THE CALLER MUST HIDE THE ACTION, not
     * fall back to something. Three ways to get it:
     *   e would be "unified"   An unstamped item viewed through Unified. A link saying
     *                        e=unified is DEAD ON ARRIVAL - the recipient rebuilds a stub with no
     *                        extras, and UnifiedExtension routes by extras[EXTENSION_ID], so
     *                        Map.extensionId throws ExtensionNotFoundException(null) before
     *                        anything resolves. Better to offer nothing than a link that cannot work.
     *   unsupported type   openMediaItemFragment handles exactly track/album/artist/playlist. A
     *                        Radio link would parse and then hit `else -> null` -> "Invalid item
     *                        type" on the recipient's device. See the radio note at that function.
     *   blank id           nothing to resolve.
     *
     * ⚠️ s IS BUILT FROM ARTISTS, NOT FROM subtitleWithOutE, and the difference is not
     * cosmetic: Track.subtitleWithOutE LEADS WITH DURATION ("03:21 • Daft Punk"), and
     * subtitleWithE prepends the explicit marker. Both would ride into the URL and onto the
     * landing page, where a recipient wants the attribution and nothing else. Omitted for Artist
     * (no artists to name) and whenever blank. The app never reads s - only the landing page does.
     */
    fun gladixLinkFor(extensionId: String, item: EchoMediaItem): String? {
        // The STAMP wins over the extension being browsed: an item seen through Unified must name
        // the sub-extension that can actually resolve it, which is also what the recipient wants
        // since they may not use Unified at all.
        val ext = item.extras[UnifiedExtension.EXTENSION_ID] ?: extensionId
        if (ext.isBlank() || ext == UnifiedExtension.UNIFIED_ID) return null
        val type = when (item) {
            is Track -> "track"
            is Album -> "album"
            is Artist -> "artist"
            is Playlist -> "playlist"
            else -> return null
        }
        if (item.id.isBlank()) return null
        val artists = when (item) {
            is Track -> item.artists
            is EchoMediaItem.Lists -> item.artists
            else -> emptyList()
        }.joinToString(", ") { it.name }.trim()
        // appendQueryParameter encodes each VALUE, so a title with & or a comma cannot break the
        // query - do not assemble this string by hand.
        return Uri.Builder()
            .scheme("https")
            .authority(GLADIX_LINK_HOST)
            .encodedPath(GLADIX_LINK_PREFIX)
            .appendQueryParameter("e", ext)
            .appendQueryParameter("t", type)
            .appendQueryParameter("i", item.id)
            .appendQueryParameter("n", item.title)
            .apply { if (artists.isNotBlank()) appendQueryParameter("s", artists) }
            .build()
            .toString()
    }

    /**
     * Opens a Gladix share link:
     * `https://rschwertley.github.io/gladix/o/?e=deezer&t=track&i=1621122342&n=Song+Title&s=Deezer`
     *
     * ⚠⚠ WHY EACH PARAM EXISTS, SO THE TRIM IS NOT RE-LITIGATED. The names are single
     * letters because a share link is read by humans in messaging apps and length is the cost; the
     * JUSTIFICATION for each is what matters:
     *   e, t, i  REQUIRED and FUNCTIONAL - extension id, item type, item id. Without any one the
     *            link cannot resolve to anything.
     *   n        OPTIONAL, the item title. Two uses, and the second is MEASURED rather than assumed:
     *            it titles the landing page (which otherwise can only say "a track from Deezer"),
     *            AND it becomes the stub header via Track(id, name) - displayed until the real item
     *            resolves, WHICH THIS PROJECT HAS MEASURED AT 2.4-3.8s (the same figure the 5s
     *            buffering watchdog races; one capture caught createPeriod 338ms before the trip).
     *            Without n that is seconds of blank header on every shared link.
     *   s        OPTIONAL, the extension DISPLAY NAME. Ignored by this parser - it exists for the
     *            landing page and for the not-installed message.
     * ⚠️ [CORRECTED] s WAS FIRST ARGUED AS EARNING ITS PLACE IN THE NOT-INSTALLED MESSAGE,
     * AND THAT WAS TOO STRONG. This project's own ids are legible, so "You don't have the deezer
     * extension installed" reads fine from the raw id and needs no param. The case that survives is
     * THIRD-PARTY ids, which are repo-shaped: echo-spotify-extension, echo-echodown-extension,
     * echo-lrclib-extension. "You don't have the echo-spotify-extension extension installed"
     * stutters, and nothing but s can fix it. Weaker than first claimed, but real.
     * ⚠️ n AND s CANNOT BE RECONSTRUCTED BY THE RECIPIENT - the landing page has no network
     * path to the service, and the app cannot resolve an extension it does not have. If they are not
     * in the URL they do not exist. That is why the two optional params are the human-context ones.
     *
     * ⚠⚠ FORWARD COMPATIBILITY: EVERYTHING EXCEPT e/t/i IS OPTIONAL FROM DAY ONE. Query
     * params are unordered and skippable, so a future param (an artist name, say) costs nothing to
     * add - PROVIDED no consumer ever requires one, because links already in the wild will lack it.
     *
     * ⚠⚠ QUERY STRING, NOT PATH SEGMENTS - AND SOMEONE WILL WANT TO "TIDY" IT INTO SEGMENTS.
     * `/gladix/open/?ext=..` resolves to a REAL FILE (open/index.html on the Pages branch), while
     * `/gladix/open/deezer/track/123` matches no file and is served by GitHub's 404.html fallback -
     * which RENDERS, but with HTTP status 404. Both verify identically, because App Links matches on
     * pathPrefix and IGNORES the query, so the only difference is that the segment form hands every
     * recipient without the app a 404. Do not convert it.
     *
     * ⚠️ `/gladix/open/` RATHER THAN `/gladix/`, AND THE REASON IS IN A DIFFERENT REPO. App
     * Links VERIFICATION is host-level (assetlinks.json at the root of rschwertley.github.io covers
     * everything), but the FILTER is ours to narrow - and `/gladix/` is the obvious prefix and is
     * WRONG. The TV pairing page lives at rschwertley.github.io/gladix/pair.html, and
     * R.string.tv_pairing_option_3 tells users to VISIT IT IN A BROWSER. Claiming `/gladix/` would
     * make that link open the app instead, breaking pairing for exactly the people who have Gladix
     * installed. NOTHING IN THIS MANIFEST OR THIS FILE REFERENCES THAT PAGE, so the constraint is
     * invisible from here - which is why it is written down rather than left to be rediscovered.
     *
     * Param names match the echo:// form where they overlap (`name`), so the two stay readable side
     * by side.
     */
    private fun FragmentActivity.openItemFragmentFromGladixLink(uri: Uri) {
        if (uri.host != GLADIX_LINK_HOST) return
        if (uri.path?.startsWith(GLADIX_LINK_PREFIX) != true) return
        val extensionId = uri.getQueryParameter("e")
        val type = uri.getQueryParameter("t")
        val id = uri.getQueryParameter("i")
        if (extensionId.isNullOrBlank() || type.isNullOrBlank() || id.isNullOrBlank()) {
            createSnack("Invalid Gladix link")
            return
        }
        // `s` is deliberately NOT read here - see the param note above. It is carried for the landing
        // page and for the not-installed message, which is raised where a missing extension is
        // actually detected (MediaViewModel), not at parse time.
        val name = uri.getQueryParameter("n").orEmpty()
        // ⚠⚠ TRACKS DIVERGE HERE, AND THE BRANCH MUST STAY IN THIS HANDLER RATHER THAN IN
        // openMediaItemFragment. That helper has TWO callers - this one and the echo:// handler,
        // which AppShortcuts also drives for its per-extension launcher shortcuts. Those must keep
        // opening the details page: "play it, do not interrupt" is a property of a SHARED LINK, not
        // of every way an item id can reach the app. Putting it in the helper silently changed
        // echo://music/<ext>/track/<id> too.
        // The other three types fall through unchanged - browsing a list is the point of sharing one.
        // A shared TRACK is a "listen to this" gesture: it plays when nothing is, and offers the
        // item's own actions over the current screen when something is. See openSharedTrack.
        if (type == "track") {
            openSharedTrack(extensionId, Track(id, name))
            return
        }
        openMediaItemFragment(extensionId, type, id, name)
    }
}

