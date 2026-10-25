package dev.brahmkshatriya.echo.ui.feed

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.media.MediaFragment
import dev.brahmkshatriya.echo.ui.media.MediaFragment.Companion.getBundle
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.playlist.edit.search.EditPlaylistSearchClickListener
import org.koin.androidx.viewmodel.ext.android.viewModel

open class FeedClickListener(
    private val fragment: Fragment,
    val fragmentManager: FragmentManager,
    private val containerId: Int,
    private val afterOpen: () -> Unit = {}
) {

    /**
     * ⚠⚠ THE TRACKS THE EXTENSION DECLARED AS ONE COLLECTION FOR THE FEED ON
     * SCREEN, or null when it declared none. Written by FeedAdapter from the same
     * Feed.Buttons the Play/Shuffle buttons read (buttonsFlow), so it follows a tab switch
     * without any extra plumbing, and read by [onTracksClicked].
     * ⚠️ A FIELD RATHER THAN A PARAMETER, AND THE REASON IS THE NESTED ROW.
     * Six call sites reach onTracksClicked, and the one that matters most for this -
     * ShelfViewHolder's card inside a HorizontalList - sits under a nested adapter that is
     * never handed the outer feed's buttons. Threading a parameter would mean changing the
     * listener signature, four view holders, the nested adapter and one override; the listener
     * is already shared by all of them, so it is the one place that needs no plumbing.
     * ⚠️ ONE FEED PER LISTENER, WHICH IS WHAT MAKES A MUTABLE FIELD SAFE
     * HERE: getFeedListener builds one per fragment, and a fragment hosts one feed. It is set
     * from the buttons observer, so switching Library tabs replaces it rather than leaving a
     * stale list from the previous tab.
     * ⚠⚠ A LAMBDA, NOT A SNAPSHOT, SO THE READ HAPPENS AT TAP TIME. Rows and
     * buttons both come from FeedData.dataFlow - buttonsFlow is `dataFlow.combine(feedSortState)`
     * and the paging source is built from the same emission - so a row cannot be on screen
     * before the Feed.Data carrying its buttons exists. What a snapshot WOULD leave open is the
     * gap between the paging submit and the buttons observer running on the same looper, plus a
     * tab switch landing between them. Reading late closes both for free: the default returns
     * null, which is the radio behaviour, so nothing regresses if a feed declares nothing.
     */
    var declaredTracks: () -> List<Track>? = { null }

    companion object {
        // ⚠⚠ DOES THIS FEED BELONG TO A COLLECTION WHOSE ORDER MATTERS? Tapping a track inside
        // one queues it from that point; tapping a lone track anywhere else starts a radio.
        //
        // ⚠⚠ WHY THIS IS NOT A SHELF-KIND TEST, WHICH IS THE OBVIOUSLY-CORRECT-LOOKING SIGNAL.
        // A FeedType.orderedList field WAS built (2026-09-12), derived from Shelf.Lists.Tracks in
        // toFeedType, and it regressed every album and playlist tap. MediaDetailsViewModel's
        // trackCachedFlow and tracksLoadedFlow both emit album and playlist tracks as INDIVIDUAL
        // Shelf.Item rows - `.map { it?.map { t -> t.toShelf() } }`, and EchoMediaItem.toShelf() is
        // `Shelf.Item(this)` - so the ordering is destroyed before toFeedType
        // ever runs, for rendering reasons. orderedList could NEVER be true on a detail page: the field was
        // reverted as INERT, not merely unused. Do not rebuild it.
        //
        // ⚠️ THE SPLIT IS TOTAL, WHICH IS WHAT MAKES THIS COMPLETE RATHER THAN A HEURISTIC.
        // context is FeedData.State.item, and every producer was audited: MediaDetailsViewModel supplies
        // the page's item at all four sites (tracks/feed x cached/loaded); HomeFragment, LibraryFragment,
        // SearchFragment, FeedFragment and DownloadFragment all pass null. No screen mixes the two.
        // Two consequences, both accepted: FeedFragment's "see all" passes null even when expanded FROM an
        // album, so a track there radios - consistent with it being a browse surface; and DownloadFragment
        // passes null, where tracks are discrete downloads.
        //
        // ⚠️ NAMED POSITIVELY, NOT AS `context != null`, AND THAT IS DELIBERATE. A Track detail
        // page has a NON-NULL context (the track itself); onTracksClicked never fires there today because
        // trackFeed() shows only album and artist rows, so a negative test would be correct BY ACCIDENT.
        // Artist is excluded on purpose too: an artist page's top-tracks row is discrete, so radio is right.
        // ⚠⚠ AND THE POSITIVE FORM AVOIDS A TRAP THIS PROJECT HAS ALREADY HIT ONE TYPE UP. When
        // isReplayableContext() was introduced, a test on the parent type EchoMediaItem.Lists silently
        // captured Radio, because Radio : EchoMediaItem.Lists - that was the bug. Album, Playlist AND Radio
        // are all Lists here too, so the same test would misfire the same way.
        // Album and Playlist are `data class`, i.e. FINAL - neither can be subclassed, so naming them
        // positively cannot capture anything else now or later. Checked, not assumed.
        fun isOrderedCollection(context: EchoMediaItem?) =
            context is Album || context is Playlist

        /**
         * ⚠⚠ HOW MANY TRACKS A DECLARED COLLECTION MAY PUT IN THE QUEUE, AND
         * IT IS NOT A FIGURE INVENTED HERE. ResumptionUtils.QUEUE_CAP_UPCOMING is 2000 and
         * trims the PERSISTED upcoming queue for exactly this class of "play all" list, so a
         * larger in-memory queue would be silently truncated on the next cold restore anyway.
         * Matching it keeps memory and disk telling the same story. 2000 tracks is ~130 hours
         * ahead; a user with more than 2000 favorites gets the first 2000 from the tapped track
         * and then a radio, which is the same ending the list has at its true end.
         * ⚠️ KEEP THE TWO IN STEP. If that constant moves, move this one - the
         * failure mode of a mismatch is a queue that plays longer than it survives a restart.
         * ⚠️ WHY NOT THE WHOLE LIST AT nb=10000: MediaItemUtils' parked memory
         * note measures a built MediaItem at ~20-30 KB (heap_used_mb_build 45-63 MB at 2,001
         * items), so 10,000 would be 200-300 MB of timeline against a 384 MB growth limit - the
         * 1008/1013/1032 OOM territory. Chunk-and-append as it plays is the better answer and is
         * a separate design: PlayerRadio.topUpQueue is radio-keyed, and the backfill command
         * rebuilds from a CONTEXT item, which favorites do not have.
         */
        private const val DECLARED_QUEUE_CAP = 2000

        fun Fragment.getFeedListener(
            navFragment: Fragment = this,
            afterOpen: () -> Unit = {}
        ): FeedClickListener {
            val key = arguments?.getString("feedListener")
            return when (key) {
                "playlist_search" -> EditPlaylistSearchClickListener(this)
                else -> FeedClickListener(
                    this,
                    navFragment.parentFragmentManager,
                    navFragment.id,
                    afterOpen
                )
            }
        }
    }

    open fun onTabSelected(
        view: View?,
        feedId: String?,
        extensionId: String?,
        position: Int
    ): Boolean {
        val vm by fragment.viewModel<FeedViewModel>()
        val feedData = vm.feedDataMap[feedId] ?: return notFoundSnack(R.string.feed)
        feedData.selectTab(extensionId, position)
        return true
    }

    open fun onSortClicked(view: View?, feedId: String?): Boolean {
        val vm by fragment.viewModel<FeedViewModel>()
        val feedData = vm.feedDataMap[feedId] ?: return notFoundSnack(R.string.feed)
        // ⚠️ ARMS THE STATE, AND DELIBERATELY DOES NOT PERSIST IT. Opening the sheet is not a user
        // choice of sort, so there is nothing to save yet; adding a persistSortState() call here to
        // "match" the other feedSortState writers would write an empty sort on every sheet open.
        // ⚠️ THE ARMING ITSELF IS LOAD-BEARING — do not remove it as a no-op. A non-null feedSortState is
        // what puts the feed on getFeedSourceData's sort branch, and that branch is what assigns
        // loadedShelves, which FeedSortBottomSheet reads to build its chip list (getSorts). Without it the
        // sheet opens with no sorts to choose from.
        feedData.feedSortState.value = feedData.feedSortState.value ?: FeedSort.State()
        FeedSortBottomSheet.newInstance(feedId!!).show(fragment.childFragmentManager, null)
        return true
    }

    open fun onPlayClicked(
        view: View?,
        extensionId: String?,
        context: EchoMediaItem?,
        tracks: List<Track>?,
        shuffle: Boolean
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        val vm by fragment.activityViewModels<PlayerViewModel>()
        if (tracks != null) {
            if (tracks.isEmpty()) return notFoundSnack(R.string.tracks)
            vm.setQueue(extensionId, tracks, 0, context)
            vm.setShuffle(shuffle, true)
            vm.setPlaying(true)
            return true
        }
        if (context == null) return notFoundSnack(R.string.item)
        if (shuffle) vm.shuffle(extensionId, context, true)
        else vm.play(extensionId, context, true)
        return true
    }

    open fun openFeed(
        view: View?,
        extensionId: String?,
        feedId: String?,
        title: String?,
        subtitle: String?,
        feed: Feed<Shelf>?
    ): Boolean {
        val fragment = fragmentManager.findFragmentById(containerId)
            ?: return notFoundSnack(R.string.view)
        val vm by fragment.activityViewModels<FeedFragment.VM>()
        vm.extensionId = extensionId ?: return notFoundSnack(R.string.extension)
        vm.feedId = feedId ?: return notFoundSnack(R.string.item)
        vm.feed = feed ?: return notFoundSnack(R.string.feed)
        // ⚠️ THE IDS GO IN THE BUNDLE TOO, NOT INSTEAD OF THE VM. Both come
        // from the same two locals assigned just above, so this cannot change WHICH ids are
        // used - only whether they survive a process kill. The Feed still travels via the VM
        // because it cannot be parcelled. Adapted from Evoe (github.com/Doggo785/evoe),
        // 27eac710, UPL.
        fragment.openFragment<FeedFragment>(
            view,
            FeedFragment.getBundle(title.orEmpty(), subtitle, vm.extensionId, vm.feedId)
        )
        afterOpen()
        return true
    }

    fun notFoundSnack(id: Int): Boolean = with(fragment) {
        val notFound = getString(R.string.no_x_found, getString(id))
        createSnack(notFound)
        false
    }

    open fun onMediaClicked(
        view: View?, extensionId: String?, item: EchoMediaItem?, context: EchoMediaItem?
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        if (item == null) return notFoundSnack(R.string.item)
        return when (item) {
            is Radio -> {
                val vm by fragment.activityViewModels<PlayerViewModel>()
                vm.play(extensionId, item, false)
                true
            }

            else -> {
                val fragment = fragmentManager.findFragmentById(containerId)
                    ?: return notFoundSnack(R.string.view)
                fragment.openFragment<MediaFragment>(view, getBundle(extensionId, item, false))
                afterOpen()
                true
            }
        }
    }

    open fun onMediaLongClicked(
        view: View?, extensionId: String?, item: EchoMediaItem?, context: EchoMediaItem?,
        tabId: String?, index: Int
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        if (item == null) return notFoundSnack(R.string.item)
        MediaMoreBottomSheet.show(
            fragment, fragmentManager,
            containerId, extensionId, item, false,
            context = context, tabId = tabId, pos = index
        )
        return true
    }

    open fun onTracksClicked(
        view: View?,
        extensionId: String?,
        context: EchoMediaItem?,
        tracks: List<Track>?,
        pos: Int
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        if (tracks.isNullOrEmpty()) return notFoundSnack(R.string.tracks)

        // A track tapped OUTSIDE an ordered collection IS a "radio" tile (Home "Mixes inspired by",
        // search): play the SEED first, then extend it into a radio. Route to playTrackRadio, which queues
        // the seed and APPENDS the generated radio server-side — mirroring phone's setQueue+auto-radio, but
        // explicit so it works on TV where auto-radio never fires (the seed used to loop). Videos are
        // excluded (a "<title> Radio" label doesn't fit video). Taps inside an album or playlist queue the
        // run from that point.
        // ⚠️ [CORRECTED 2026-09-12] This read "a single track tapped with NO SURROUNDING CONTEXT",
        // and the test carried a `context == null && tracks.size == 1` term to match. Both halves were wrong.
        // `tracks.size == 1` was never the real rule — it held only because SearchFragment OVERRODE this
        // method to manufacture a 1-element list; with that override gone, search passes its full run and a
        // size term would silently stop radioing on the one screen that already worked. And `context == null`
        // is too coarse in the other direction: an Artist page has a non-null context but its top-tracks row
        // is discrete, so radio is right there. See isOrderedCollection above for both.
        // ⚠⚠ A TRACK THE EXTENSION DECLARED AS PART OF A COLLECTION QUEUES THE
        // WHOLE COLLECTION, AND THIS BRANCH IS FIRST BECAUSE IT IS THE STRONGEST SIGNAL.
        // [declaredTracks] is Feed.Buttons.customTrackList for the feed on screen - the
        // extension saying "these tracks are one list" - which is what a library of liked tracks
        // has instead of an Album or Playlist context. GitHub issue #4: tapping a liked track
        // radioed, because isOrderedCollection(context) is false on every library feed and the
        // run getAllTracks could offer was the ~100-item paging window.
        // ⚠️ MEMBERSHIP, NOT THE ROW, WHICH IS WHY Library -> All WORKS TOO.
        // The All tab's tracks arrive as cards inside a HorizontalList (Shelf.Lists.Items
        // defaults to Type.Linear), and ShelfViewHolder passes `listOf(item)` - a one-element
        // list that would always radio. Testing the TAPPED TRACK against the declared list needs
        // nothing from the row, so both tabs behave identically with one test.
        // ⚠️ BY ID, NOT BY IDENTITY: the row's Track and the declared Track are
        // separately parsed objects from the same response, so equals() is not safe to rely on.
        // ⚠️ VIDEOS ARE NOT EXCLUDED HERE, DELIBERATELY. The exclusion below
        // exists because "<title> Radio" makes no sense for a video; a declared collection is
        // ordered content, so a video inside one queues like everything else in it.
        // ⚠️ STILL NO tracks.size TERM, ANYWHERE. See the 2026-09-12 correction
        // below: `tracks.size == 1` only ever held because SearchFragment manufactured
        // one-element lists, and reintroducing it here would make the All tab's one-element taps
        // the test rather than the declared list.
        // ⚠️ WHO ACTUALLY DECLARES A LIST, BECAUSE THIS BRANCH RUNS AHEAD OF
        // THE ARTIST-PAGE REASONING IN [isOrderedCollection]'s NOTE. Four producers, grep
        // customTrackList: DeezerLibraryClient (liked tracks, Tracks and All), Convertors'
        // folder Shelf.Category (the Offline folder browse), OfflineExtension.loadFeed(Artist),
        // and UnifiedExtension's Feed.Buttons.withExtensionId, which passes a sub-extension's
        // list through. The third is the interesting one: that note says an Artist page's
        // top-tracks row is discrete so radio is right there, and that stays true of
        // isOrderedCollection - but Offline's artist page DECLARES its tracks, so taps there now
        // queue instead. That is the extension's own statement and it is the better answer for
        // local files, where a radio has nothing to generate from. Nothing here special-cases
        // Offline; a remote artist page that declares no list still radios.
        // ⚠️ ID COMPARISON SURVIVES UNIFIED: withExtensionId only ever
        // copies extras and capability flags - never `id` - and rows and buttons are injected by
        // the same injectExtensionId pass, so both sides carry identical ids.
        val declared = declaredTracks()
        val declaredPos = tracks.getOrNull(pos)?.id?.let { tapped ->
            declared?.indexOfFirst { it.id == tapped }?.takeIf { it >= 0 }
        }
        val vm by fragment.activityViewModels<PlayerViewModel>()
        // ⚠⚠ A TAP RESPECTS THE SHUFFLE STATE, AND THIS SIDE DOES NOT DECIDE IT.
        // `respectShuffle` asks the SERVICE to read its own flag on the player thread as it applies
        // the queue - see ShufflePlayer.notifyTapRespectShuffle. Reading viewModel.shuffleMode here
        // instead was wrong twice over: it is a mirror that starts at a default `false` before the
        // controller attaches, and the follow-up setShuffle(true) left a window (after setQueue's
        // sync-off, before the re-assert) in which a second quick tap read OFF and played in order.
        // ⚠️ SHUFFLE HERE IS A PHYSICAL REORDER, which is why a flag flip would
        // not do: the inner ExoPlayer's shuffleOrder is UnshuffledShuffleOrder (identity), so the
        // service applies the queue already shuffled with the tapped track lifted to index 0, and
        // keeps the in-order list as `original`.
        // ⚠️ NOT THE RADIO BRANCH. A radio is generated in play order and
        // extends itself (PlayerRadio.topUpQueue); reordering it would fight the station.
        // ⚠️ TOGGLING SHUFFLE OFF LATER RESTORES THE ORIGINAL ORDER FROM THE
        // CURRENT TRACK, not from the top: changeQueue(original) keeps current at 0, so tracks that
        // were before it stay dropped - the current+upcoming model documented at changeQueue. That
        // now survives a restart too: ResumptionUtils persists the order as ids (see
        // ShufflePlayer.notifyRestoredOriginalOrder), where it used to be in-memory only.
        if (declared != null && declaredPos != null) {
            // From the tapped track onward, capped - see [DECLARED_QUEUE_CAP]. setQueue would drop
            // the head itself, but slicing here is what lets the cap count from the tap rather
            // than from the top of a 10,000-track list.
            val upcoming = declared.subList(declaredPos, declared.size).take(DECLARED_QUEUE_CAP)
            vm.setQueue(extensionId, upcoming, 0, null, respectShuffle = true)
            vm.setPlaying(true)
            return true
        }
        val single = tracks.getOrNull(pos)?.takeIf {
            !isOrderedCollection(context) &&
                it.type != Track.Type.Video && it.type != Track.Type.HorizontalVideo
        }
        if (single != null) {
            vm.playTrackRadio(extensionId, single)
            return true
        }
        // Album and playlist taps take the same shuffle treatment - see the note above.
        vm.setQueue(extensionId, tracks, pos, context, respectShuffle = true)
        vm.setPlaying(true)
        return true
    }

    open fun onTrackSwiped(
        view: View?, extensionId: String?, track: Track?,
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        if (track == null) return notFoundSnack(R.string.track)
        val vm by fragment.activityViewModels<PlayerViewModel>()
        vm.addToNext(extensionId, track, false)
        return true
    }
}