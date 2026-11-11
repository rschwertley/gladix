package dev.brahmkshatriya.echo.ui.feed

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow

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

// The row's extras go along (they carry the marker, and under Unified the routing stamp) so the extension can tell the row apart.
suspend fun loadTracksOnTap(
    extensions: ExtensionLoader, extensionId: String, id: String, title: String, extras: Map<String, String>,
): List<Track> {
    val extension = extensions.music.getExtensionOrThrow(extensionId)
    val feed = extension.getAs<AlbumClient, Feed<Track>?> {
        loadTracks(Album(id = id, title = title, extras = extras))
    }.getOrThrow() ?: return emptyList()
    return feed.loadAll()
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
