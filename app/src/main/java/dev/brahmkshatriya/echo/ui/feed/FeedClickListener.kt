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
        val single = tracks.getOrNull(pos)?.takeIf {
            !isOrderedCollection(context) &&
                it.type != Track.Type.Video && it.type != Track.Type.HorizontalVideo
        }
        val vm by fragment.activityViewModels<PlayerViewModel>()
        if (single != null) {
            vm.playTrackRadio(extensionId, single)
            return true
        }
        vm.setQueue(extensionId, tracks, pos, context)
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