package dev.brahmkshatriya.echo.ui.feed

import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import java.util.UUID

/**
 * [2026-11-08] The live page behind the last "Playing from <page>" label put on the queue, so a tap on that label
 * (PlayerFragment.openFeedPage) reopens the page itself rather than its cached copy. ONE SLOT, NOT A LIST: a new label
 * replaces it, and a tap on an older label falls to the cache path.
 *
 * Keyed by a random key carried in the label (MediaItemUtils.FEED_PAGE_SLOT), not by the feed id, because feed ids are not
 * unique: two Offline folders with the same name share one (Convertors' FileNode.toShelf uses the folder name), and some
 * Deezer "see all" shelves use their title (DeezerParser.toShelfItemsList). Process lifetime only; a Feed holds lambdas and
 * cannot be saved.
 */
object FeedPageSlot {
    private class Entry(val key: String, val extensionId: String, val feedId: String, val feed: Feed<Shelf>)

    @Volatile
    private var entry: Entry? = null

    /** The label for a queue being set from a page now, registering [feed] (when there is one) as the live page. */
    fun label(extensionId: String?, feedId: String?, title: String, feed: Feed<Shelf>?): Radio {
        var key: String? = null
        if (extensionId != null && feedId != null && feed != null) {
            key = UUID.randomUUID().toString()
            entry = Entry(key, extensionId, feedId, feed)
        }
        return MediaItemUtils.feedPageLabel(feedId, title, extensionId, key)
    }

    fun get(key: String, extensionId: String, feedId: String): Feed<Shelf>? =
        entry?.takeIf { it.key == key && it.extensionId == extensionId && it.feedId == feedId }?.feed
}
