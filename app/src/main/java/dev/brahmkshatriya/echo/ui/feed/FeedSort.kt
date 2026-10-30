package dev.brahmkshatriya.echo.ui.feed

import android.content.Context
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.ui.feed.FeedSort.entries
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.toTrackString
import kotlinx.serialization.Serializable
import java.text.Collator

fun getSorts(data: List<Shelf>): List<FeedSort> {
    return entries.filter { it.shouldBeVisible(data) != null }
}

private fun Shelf.title() = when (this) {
    is Shelf.Category -> this.title
    is Shelf.Item -> this.media.title
    is Shelf.Lists<*> -> null
}

private fun Shelf.subtitle() = when (this) {
    is Shelf.Category -> this.subtitle
    is Shelf.Item -> this.media.subtitle
    is Shelf.Lists<*> -> null
}

private fun Shelf.duration() = when (this) {
    is Shelf.Category -> null
    is Shelf.Item -> when (val item = this.media) {
        is Track -> item.duration
        is Album -> item.duration
        is Playlist -> item.duration
        else -> null
    }

    is Shelf.Lists<*> -> null
}

private fun Shelf.date() = when (this) {
    is Shelf.Category -> null
    is Shelf.Item -> when (val item = this.media) {
        is Track -> item.releaseDate
        is Album -> item.releaseDate
        is Playlist -> item.creationDate
        else -> null
    }

    is Shelf.Lists<*> -> null
}

private fun Shelf.dateAdded() = when (this) {
    is Shelf.Category -> null
    is Shelf.Item -> when (val item = this.media) {
        is Track -> item.playlistAddedDate
        else -> null
    }

    is Shelf.Lists<*> -> null
}

private fun Shelf.artists() = when (this) {
    is Shelf.Category -> null
    is Shelf.Item -> when (val item = this.media) {
        is Track -> item.artists
        is EchoMediaItem.Lists -> item.artists
        else -> null
    }

    is Shelf.Lists<*> -> null
}

private fun Shelf.album() = when (this) {
    is Shelf.Category -> null
    is Shelf.Item -> when (val item = this.media) {
        is Track -> item.album
        else -> null
    }

    is Shelf.Lists<*> -> null
}

private fun Shelf.trackCount() = when (this) {
    is Shelf.Category -> null
    is Shelf.Item -> when (val item = this.media) {
        is EchoMediaItem.Lists -> item.trackCount
        else -> null
    }

    is Shelf.Lists<*> -> null
}

private fun Shelf.ifListItem(): EchoMediaItem.Lists? = when (this) {
    is Shelf.Category -> null
    is Shelf.Item -> when (media) {
        is EchoMediaItem.Lists -> media as EchoMediaItem.Lists
        else -> null
    }

    is Shelf.Lists<*> -> null
}

private fun Shelf.copy(subtitle: String) = when (this) {
    is Shelf.Category -> this.copy(subtitle = subtitle)
    is Shelf.Item -> this.copy(media = this.media.copyMediaItem(subtitle = subtitle))
    is Shelf.Lists<*> -> throw IllegalStateException()
}

// Locale-aware, case- and accent-insensitive text order (Collator.PRIMARY), so "a-ha" sorts with the
// A's and "Édith" with the E's. No leading-"The" handling, by decision. A fresh Collator per sort:
// instances are not thread-safe.
private fun Context.textOrder(): Comparator<String> {
    val collator = Collator.getInstance(resources.configuration.locales[0])
    collator.strength = Collator.PRIMARY
    return Comparator { a, b -> collator.compare(a, b) }
}

// ⚠️ MISSING VALUES ARE KEPT, AFTER THE SORTED ROWS (2026-10-29). Date, Tracks, Artists and Albums used
// to DROP rows with no value, and since the GitHub #4 follow-up Play and taps queue what is shown, so the
// hidden songs stopped playing too. Duration, Subtitle and Title used to put them FIRST. `relabel`
// rewrites the subtitle of rows that have a value only; missing rows keep theirs.
// ⚠️ MISSING ROWS STAY LAST IN BOTH DIRECTIONS, the spreadsheet convention (blanks at the bottom whether
// ascending or descending). So "Reversed" is applied HERE, to the sorted rows only, and not by
// FeedData.getFeedSourceData reversing the whole result - which would have put the missing rows first.
// FeedData still reverses the list itself when Reversed is on with no sort field. FeedSort.Artists does
// the same split inline.
private fun <K : Any> List<Shelf>.sortedMissingLast(
    reversed: Boolean,
    order: Comparator<in K>,
    key: (Shelf) -> K?,
    relabel: ((Shelf, K) -> Shelf)? = null,
): List<Shelf> {
    val (has, missing) = map { it to key(it) }.partition { it.second != null }
    val sorted = has.sortedWith(compareBy<Pair<Shelf, K?>, K>(order) { it.second!! })
        .map { (shelf, k) -> relabel?.invoke(shelf, k!!) ?: shelf }
    return (if (reversed) sorted.reversed() else sorted) + missing.map { it.first }
}

enum class FeedSort(
    val title: Int,
    val sorter: Context.(List<Shelf>, reversed: Boolean) -> List<Shelf>
) {
    Title(R.string.sort_title, { list, reversed ->
        list.sortedMissingLast(reversed, textOrder(), { it.title()?.takeIf { t -> t.isNotEmpty() } })
    }),
    Subtitle(R.string.sort_subtitle, { list, reversed ->
        list.sortedMissingLast(reversed, textOrder(), { it.subtitle()?.takeIf { t -> t.isNotEmpty() } })
    }),
    Duration(R.string.sort_duration, { list, reversed ->
        list.sortedMissingLast(reversed, naturalOrder(), { it.duration() })
    }),
    Date(R.string.sort_date, { list, reversed ->
        list.sortedMissingLast(reversed, naturalOrder(), { it.date() }) { shelf, date ->
            shelf.copy(subtitle = date.toString())
        }
    }),
    // Sorted, filtered and labelled by dateAdded() (Track.playlistAddedDate), matching shouldBeVisible.
    // It used date() - the release date - until 2026-10-29, so "Date added" behaved as "Date".
    DateAdded(R.string.sort_date_added, { list, reversed ->
        list.sortedMissingLast(reversed, naturalOrder(), { it.dateAdded() }) { shelf, date ->
            shelf.copy(subtitle = date.toString())
        }
    }),
    Artists(R.string.artists, { list, reversed ->
        val (has, missing) = list.partition { !it.artists().isNullOrEmpty() }
        val sorted = has.flatMap { shelf ->
            shelf.artists().orEmpty().map {
                it to shelf.copy(subtitle = it.name)
            }
        }.sortedWith(compareBy(textOrder()) { it.first.name }).groupBy { it.first.id }
            .values.flatMap { it -> it.map { it.second } }
        (if (reversed) sorted.reversed() else sorted) + missing
    }),
    Album(R.string.albums, { list, reversed ->
        list.sortedMissingLast(reversed, textOrder(), { it.album()?.title }) { shelf, title ->
            shelf.copy(subtitle = title)
        }
    }),
    Tracks(R.string.tracks, { list, reversed ->
        list.sortedMissingLast(reversed, naturalOrder(), { it.trackCount() }) { shelf, _ ->
            shelf.copy(subtitle = shelf.ifListItem()?.toTrackString(this) ?: "???")
        }
    })
    ;

    fun shouldBeVisible(data: List<Shelf>): FeedSort? {
        val take = when (this) {
            Title -> data.any { it.title()?.isNotEmpty() ?: false }
            Subtitle -> data.any { it.subtitle()?.isNotEmpty() ?: false }
            Date -> data.any { it.date() != null }
            DateAdded -> data.any { it.dateAdded() != null }
            Duration -> data.any { it.duration() != null }
            Artists -> data.any { it.artists()?.isNotEmpty() ?: false }
            Album -> data.mapNotNull { it.album() }.toSet().size > 1
            Tracks -> data.any { it.trackCount() != null }
        }
        return if (take) this else null
    }

    @Serializable
    data class State(
        val feedSort: FeedSort? = null,
        val reversed: Boolean = false,
        val save: Boolean = false,
    )
}