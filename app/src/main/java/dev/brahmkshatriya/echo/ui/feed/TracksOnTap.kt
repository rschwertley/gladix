package dev.brahmkshatriya.echo.ui.feed

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.playback.MediaItemUtils.droppingUnplayable
import dev.brahmkshatriya.echo.utils.BoundedLoadAll.forEachPageBounded
import kotlin.random.Random

/**
 * [2026-11-11] THE "TRACKS ON TAP" MARKER: a Shelf.Category whose extras carry GLADIX_TRACKS_ON_TAP = "1" asks for its songs to
 * be loaded when Play, Shuffle, Play next or Add to queue is tapped, from AlbumClient.loadTracks with the row as an Album,
 * every page read. Set by OpenGonic: "dir:<id>" answers every song under the folder (depth-first), "library:all" the whole
 * library. Read by FolderMoreBottomSheet (the long-press menu) and FeedClickListener.onPlayClicked (the folder page's Play and
 * Shuffle, which knows its row through FeedFragment's "tracksOnTap" argument). A row without it is unchanged. No cap: the
 * user's decision for Play and Shuffle (Oct 27-30, Nov 7-8).
 * ⚠️ NOT THROUGH Cached: a folder is not an album, and its id must not land in the album memo or store.
 */
const val GLADIX_TRACKS_ON_TAP = "gladix_tracks_on_tap"

fun Shelf.hasTracksOnTap() = this is Shelf.Category && extras[GLADIX_TRACKS_ON_TAP] == "1"

/**
 * [2026-11-13] THE SHUFFLE INTENT (the user's decision): loadTracksOnTap adds GLADIX_SHUFFLE = "1" to the Album's extras when the
 * list is loaded for Shuffle; Play, Play next and Add to queue send none. For an extension that can answer a load-on-tap list
 * already shuffled (OpenGonic, planned), so a Shuffle of a big folder can start without reading it all. An extension that does
 * not know the key ignores it, and UnifiedExtension.loadTracks passes the Album through whole. Nothing here changes with it:
 * QueueSampler still takes its fair 2,000, so a list of 2,000 or fewer random songs is kept as the extension returned it.
 */
const val GLADIX_SHUFFLE = "gladix_shuffle"

// The row's extras go along (they carry the marker, and under Unified the routing stamp) so the extension can tell the row apart.
// [2026-11-13] PAGE BY PAGE, not as one list (Crashlytics 63ec9068, the user's decision): [onPage] gets each page as it is read
// and returns false to stop reading (BoundedLoadAll.forEachPageBounded), so a caller that keeps a 2,000-song sample never holds
// the whole folder. An extension that returns its whole list at once (OpenGonic today) arrives as one page.
suspend fun loadTracksOnTap(
    extensions: ExtensionLoader, extensionId: String, id: String, title: String, extras: Map<String, String>,
    shuffle: Boolean = false,
    onPage: suspend (List<Track>) -> Boolean,
) {
    val extension = extensions.music.getExtensionOrThrow(extensionId)
    // [2026-11-13] See GLADIX_SHUFFLE.
    val sent = if (shuffle) extras + (GLADIX_SHUFFLE to "1") else extras
    val feed = extension.getAs<AlbumClient, Feed<Track>?> {
        loadTracks(Album(id = id, title = title, extras = sent))
    }.getOrThrow() ?: return
    feed.forEachPageBounded(onPage)
}

/**
 * [2026-11-12] A LOAD-ON-TAP QUEUE IS AT MOST FeedClickListener.DECLARED_QUEUE_CAP (2,000) SONGS, BY A FAIR SAMPLE (the user's
 * decision of 2026-11-12, like Roon; it replaces "no cap" for load-on-tap folders and "All songs" only). Why: every queued song is
 * a ~20-30 KB MediaItem (MediaItemUtils' parked memory note), so an uncapped folder Shuffle built 10k+ of them and ran a 256 MB
 * heap out (two fatal OOMs on 1178). 2,000 is the existing figure: the saved queue keeps 2,000 upcoming anyway
 * (ResumptionUtils.QUEUE_CAP_UPCOMING), and the liked list already queues 2,000.
 * - SHUFFLE: the whole list shuffled fairly and the first 2,000 kept, then put back in SOURCE order. The player is given that
 *   in-order sample with shuffle on (setQueue's shuffleList, the liked list's header Shuffle path), so the first song is random
 *   and turning shuffle off shows the sample in folder order.
 * - PLAY: the first 2,000 in source order.
 * At or under the cap the list is returned as it is: exactly today's queue.
 */
// [2026-11-13] [stoppedEarly]: Play stopped reading at the cap, so [total] is not the list's size (see QueueSampler).
class QueueSample(val tracks: List<Track>, val total: Int, val stoppedEarly: Boolean = false) {
    val isSample get() = stoppedEarly || tracks.size < total
}

/**
 * [2026-11-13] THE SAME SAMPLE, TAKEN WHILE READING (Crashlytics 63ec9068, the user's decision): [offer] each page as it arrives
 * (loadTracksOnTap's onPage) and only the kept songs are held, never the whole list. It replaces sampleForQueue, which sampled a
 * list already loaded whole.
 * - SHUFFLE: a reservoir of [cap] (Algorithm R: song number i, counted from 0, replaces a random kept one with chance cap/(i+1)),
 *   which is a uniform sample of the whole list like the shuffled-indices one it replaces. Each song is kept with its position
 *   and the sample is put back in SOURCE order at the end, so the rule above holds. Every page is read, so [QueueSample.total]
 *   is exact: "Shuffling 2,000 of N songs".
 * [CORRECTED 2026-11-13] Neither message is shown any more (PlayerViewModel.playTracksOnTap): the cap is silent. The total is
 *   still counted, and stoppedEarly still set, for any later use.
 * - PLAY: the first [cap] in source order, then false, which stops the reading. The total is unknown ("Playing the first 2,000
 *   songs"): counting it would mean reading the rest.
 * [dropUnplayable]: the songs counted and kept are the playable ones (MediaItemUtils.droppingUnplayable, per page), as Play and
 * Shuffle filtered the whole list before. Play next and Add to queue pass false: they did not filter, and still do not.
 */
class QueueSampler(
    private val shuffle: Boolean,
    private val dropUnplayable: Boolean = true,
    private val cap: Int = FeedClickListener.DECLARED_QUEUE_CAP,
) {
    private val kept = ArrayList<Pair<Int, Track>>()
    private var total = 0
    private var stoppedEarly = false

    fun offer(page: List<Track>): Boolean {
        for (track in if (dropUnplayable) page.droppingUnplayable() else page) {
            val position = total
            if (kept.size < cap) kept.add(position to track)
            else if (!shuffle) {
                stoppedEarly = true
                return false
            } else {
                val slot = Random.nextInt(position + 1)
                if (slot < cap) kept[slot] = position to track
            }
            total++
        }
        return true
    }

    fun result() = QueueSample(kept.sortedBy { it.first }.map { it.second }, total, stoppedEarly)
}

// A tracks-on-tap row that is not a folder: OpenGonic's "All songs". Folder rows carry the marker too.
fun Shelf.isTracksOnTapOnly() = hasTracksOnTap() && !isMarkedFolder()

// The order kept first under a sort or a search (FeedData.getFeedSourceData): "All songs", then marked folders, then the rest,
// each group in the order it came. Nothing marked: the list as it was.
fun List<Shelf>.keptFirst(): List<Shelf> {
    val (onTap, others) = partition { it.isTracksOnTapOnly() }
    val (folders, rest) = others.partition { it.isMarkedFolder() }
    return onTap + folders + rest
}
