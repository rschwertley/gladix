package dev.brahmkshatriya.echo.history.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.utils.Serializer.toData

@Entity(primaryKeys = ["trackId", "extensionId"])
data class HistoryEntity(
    val trackId: String,
    val extensionId: String,
    val playedAt: Long,
    val trackData: String,
    val contextData: String? = null,
) {
    val track by lazy { trackData.toData<Track>().getOrNull() }
    val context by lazy { contextData?.toData<EchoMediaItem>()?.getOrNull() }
}

// History only needs a track's id (for live re-resolution on tap/play) plus display fields (title,
// artist names + covers, cover, duration) and — for the more-sheet's "Go to Album"/artist nav — a
// slimmed album + slimmed artists. Everything heavy (streamables, extras, description, banners,
// nested album artists) is dropped so a row stays well under the CursorWindow limit that getAll hit.
fun Track.toSlim(): Track = copy(
    streamables = emptyList(),
    extras = emptyMap(),
    description = null,
    background = null,
    subtitle = null,
    genres = emptyList(),
    plays = null,
    releaseDate = null,
    playlistAddedDate = null,
    isrc = null,
    artists = artists.map { it.copy(bio = null, extras = emptyMap(), background = null, banners = emptyList()) },
    album = album?.copy(description = null, extras = emptyMap(), background = null, artists = emptyList())
)

// A stored context only needs id/type/title/cover (for the "Go to <context>" button + re-resolution
// by id, which happens with loaded=false). Radio is the exception: its re-resolution reads extras
// (kind + seeds), so keep those; drop everything else's extras/description.
fun EchoMediaItem.toSlimContext(): EchoMediaItem = copyMediaItem(
    description = null,
    subtitle = null,
    extras = if (this is Radio) extras else emptyMap()
)
