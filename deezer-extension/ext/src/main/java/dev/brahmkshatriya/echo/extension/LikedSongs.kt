package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Track

/**
 * One liked song, light, for the artist page's "Favorite tracks" entry (2026-10-30). Held for the session by
 * DeezerExtension's liked index; never persisted.
 *
 * ⚠⚠ BUILT FROM THE ALREADY-GRAFTED LIKED RECORD, AND REBUILT WITHOUT A FETCH - THAT IS THE POINT.
 * favorite_song.getList pre-substitutes: the top-level SNG_ID can be the substitute and the real song sits
 * in FALLBACK, which DeezerLibraryClient.graftFavTrack grafts (Aug 6). A single-track fetch by the
 * top-level id has NO FALLBACK to graft from - that was the Aug 6 art leak, and losing FALLBACK_ID is the
 * Baby Beluga skip class (see DeezerTrackClient's FALLBACK_ID note). So this keeps exactly what toTrack()
 * needs to rebuild the grafted Track: id, FALLBACK_ID, the grafted title (SNG_TITLE + VERSION), artist
 * ids/names, album id/title/cover, cover, duration, explicit, and the two small extras the stream path
 * reads (FILESIZE_MP3_MISC, GAIN). There is no ALT_VERSION or other substitution stamp on the record:
 * ALT_VERSION is stamped on the STREAMABLE at play time (DeezerTrackClient), not on the liked record.
 * ⚠️ NO TOKENS (the 1151 rule): TRACK_TOKEN / TRACK_TOKEN_EXPIRE are left empty, exactly as a
 * token-stripped Library row from the disk cache arrives; DeezerTrackClient.loadTrack re-fetches an
 * empty token and carries FALLBACK_ID across that rebuild.
 * ⚠️ NO PLAYABILITY VERDICT: DeezerApi.markUnplayable's verdict must not outlive its list, so the page
 * re-marks on every open.
 * SIZE, ESTIMATED (not measured): the entry object (~64 B), five short Strings (~200 B), one cover
 * ImageHolder with its ~110-character URL (~200 B; the album cover shares it when equal), and ~200 B per
 * artist - about 0.7 KB with one artist, ~0.9 KB with two. ~0.4 MB for 500 liked songs, ~7-9 MB at the
 * 10,000 cap. Artist covers and the CONTRIB_* extras are dropped to keep it there.
 */
class LikedEntry(
    val id: String,
    val fallbackId: String,
    val title: String,
    val artists: List<Artist>,
    val albumId: String,
    val albumTitle: String,
    val albumCover: ImageHolder?,
    val cover: ImageHolder?,
    val duration: Long?,
    val isExplicit: Boolean,
    val mp3Misc: String,
    val gain: String,
) {
    fun toTrack(): Track = Track(
        id = id,
        title = title,
        cover = cover,
        duration = duration,
        artists = artists,
        album = Album(id = albumId, title = albumTitle, cover = albumCover),
        isExplicit = isExplicit,
        extras = mapOf(
            "FALLBACK_ID" to fallbackId,
            "TRACK_TOKEN" to "",
            "TRACK_TOKEN_EXPIRE" to "",
            "FILESIZE_MP3_MISC" to mp3Misc,
            "TYPE" to "cover",
            "GAIN" to gain,
            "loved" to "1",
        ),
    )

    /**
     * Is this a song by [artist]? Either the artist is among the song's artists as the liked record carries
     * them (matched by id), or the title names them as a guest. Whether ARTISTS lists featured performers is
     * NOT verified (no multi-artist liked song was available to check), so the title match is built anyway.
     * Idea credit: Evoe (github.com/Doggo785/evoe) 55a49365 / 1e058b12 - matching only.
     */
    fun isBy(artist: Artist): Boolean {
        if (artist.id.isNotBlank() && artists.any { it.id == artist.id }) return true
        val name = artist.name.trim()
        if (name.isEmpty()) return false
        return featuredNames(title).any { it.equals(name, ignoreCase = true) }
    }

    companion object {
        /** From a GRAFTED liked Track (graftFavTrack's output, or a Track the host hands likeItem). */
        fun of(track: Track) = LikedEntry(
            id = track.id,
            fallbackId = track.extras["FALLBACK_ID"].orEmpty(),
            title = track.title,
            artists = track.artists.map { Artist(id = it.id, name = it.name) },
            albumId = track.album?.id.orEmpty(),
            albumTitle = track.album?.title.orEmpty(),
            // One shared object when the two are equal (the usual case: both come from the same md5).
            albumCover = track.album?.cover?.let { if (it == track.cover) track.cover else it },
            cover = track.cover,
            duration = track.duration,
            isExplicit = track.isExplicit,
            mp3Misc = track.extras["FILESIZE_MP3_MISC"] ?: "0",
            gain = track.extras["GAIN"] ?: "0",
        )

        private val FEAT_MARKER = Regex("""(?i)(?:\b(?:feat|ft)\.?|\bfeaturing)\s+""")
        private val LIST_SPLIT = Regex("""(?i)\s*(?:,|&|/|\band\b|\bx\b)\s*""")

        /**
         * The guest names a title credits: ONLY the text after a "feat." / "ft." / "featuring" marker, up to
         * the closing bracket or a " - ", split into whole items on , & / "and" "x". Each item is compared
         * whole (isBy), so a short name never matches inside another word.
         */
        fun featuredNames(title: String): List<String> =
            FEAT_MARKER.findAll(title).flatMap { m ->
                val rest = title.substring(m.range.last + 1)
                val end = listOf(rest.indexOf(')'), rest.indexOf(']'), rest.indexOf(" - "))
                    .filter { it >= 0 }.minOrNull() ?: rest.length
                rest.substring(0, end).split(LIST_SPLIT)
                    .map { it.trim().trimEnd('.', ')', ']') }
                    .filter { it.isNotEmpty() }
            }.toList()
    }
}
