package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.objOrNull
import dev.brahmkshatriya.echo.extension.arrOrNull
import dev.brahmkshatriya.echo.extension.requireObj
import dev.brahmkshatriya.echo.extension.requireArr
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

class DeezerAlbumClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    companion object {
        private const val HANDOFF_TTL_MS = 30_000L
    }

    private suspend fun results(album: Album) =
        // STRICT ON THE TYPE ONLY: an album or show whose envelope is a literal must NOT read as
        // a valid album with nothing in it, so a present-but-wrong `results` throws
        // DeezerShapeException (DeezerJson).
        // ⚠️ THE `!!` STAYS, SO AN ABSENT KEY FAILS EXACTLY AS BEFORE. It threw
        // a NullPointerException here and never reached Cached.loadMedia's "no longer available"
        // path - that one fires when loadItem RETURNS a blank item, not when it throws - and
        // nothing downstream was re-checked against a new type, so the absent case is left
        // untouched rather than quietly re-typed. Do not "tidy" this into .requireObj alone.
        (if (album.type == Album.Type.Show) api.show(album) else api.album(album))["results"]
            ?.requireObj("results")!!

    // ══ SINGLE-SLOT HANDOFF: loadAlbum -> loadTracks ══
    // A cold album open issued THE SAME request twice. loadAlbum and loadTracks both call results(album),
    // which is one api.album() (or api.show()) call, and the response already contains both halves -
    // loadAlbum throws away SONGS, loadTracks throws away the metadata. Sequential, so neither the app's
    // in-flight dedup nor any parallelisation could collapse them; only reuse can.
    //
    // WHY A HANDOFF AND NOT A CACHE. A short-TTL cache keyed on album id was the obvious shape and is the
    // wrong one here:
    //   the payload is a whole album, metadata plus every song - tens of KB for a 12-track album, around a
    //     megabyte for a large compilation - so holding several during a browse is a real cost for a
    //     one-shot benefit;
    //   the app cannot tell this client "that was a refresh", so any TTL long enough to feel safe is long
    //     enough that a user who opens an album, sees something wrong and pulls to refresh gets the cached
    //     payload handed straight back and nothing appears to happen.
    // Consumption solves both. loadAlbum stores; loadTracks takes AND CLEARS. So the slot is empty between
    // opens, a refresh always refetches (loadTracks cleared it, so the next loadAlbum has nothing to reuse),
    // and at most one payload is ever held.
    //
    // The TTL is only a safety net for the NEVER-CONSUMED case: the user opens the header and backs out
    // before loadTracks runs, or the app's durable album cache short-circuits loadTracks entirely (it does,
    // within 24h - loadAlbum still runs on every open because loadMedia never short-circuits). Then nothing
    // takes the slot and a payload would sit there indefinitely. 30s is two orders of magnitude longer than
    // the gap it must span, so it never interferes with the handoff; it could be 10 or 60 without changing
    // behaviour, which is the point - consumption does the work, not expiry.
    //
    // Keyed on id AND show-ness because those pick different endpoints. Two albums opened in quick
    // succession: the second loadAlbum overwrites the slot, the first loadTracks finds a key mismatch and
    // fetches. Degrades to today's behaviour; it can never serve the wrong album's payload.
    private data class Handoff(val key: String, val atMs: Long, val results: JsonObject)

    private val handoff = AtomicReference<Handoff?>(null)

    private fun handoffKey(album: Album) = "${album.type == Album.Type.Show}:${album.id}"

    // CAS rather than getAndSet: a mismatched slot belongs to a DIFFERENT album that may still be about to
    // consume it, so a failed match must leave it alone rather than discard someone else's payload.
    private fun takeHandoff(album: Album): JsonObject? {
        val held = handoff.get() ?: return null
        if (held.key != handoffKey(album)) return null
        if (System.currentTimeMillis() - held.atMs > HANDOFF_TTL_MS) return null
        return if (handoff.compareAndSet(held, null)) held.results else null
    }

    suspend fun loadAlbum(album: Album): Album {
        deezerExtension.handleArlExpiration()
        val resultsObject = results(album)
        // Always fetched, never read from the slot - that is what keeps pull-to-refresh honest.
        handoff.set(Handoff(handoffKey(album), System.currentTimeMillis(), resultsObject))
        return parser.run { if (album.type == Album.Type.Show) resultsObject.toShow() else resultsObject.toAlbum() }
    }

    fun loadTracks(album: Album): Feed<Track> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        // Reuses the payload loadAlbum just fetched when this is the paired call; falls through to a real
        // request otherwise (a retry, a re-invoked PagedData.Single, or an unpaired loadTracks), which is
        // the correct behaviour in each of those cases.
        val resultsObject = takeHandoff(album) ?: results(album)
        if (album.type == Album.Type.Show) {
            // Same guard as the album branch: a show with no episodes, or no bookmarked episodes,
            // returns a results object without EPISODES.data — yield empty rather than NPE on !!.
            // ABSENT stays empty - that is the decided behaviour above - but a PRESENT literal
            // throws rather than silently emptying a show's episode list (DeezerJson).
            val dataArray = resultsObject["EPISODES"]?.requireObj("EPISODES")
                ?.get("data")?.requireArr("EPISODES.data").orEmpty()
            // Bookmarks are lenient: no bookmarks is a genuine state and only affects resume
            // positions, never which episodes exist.
            val bookmarkMap = api.getBookmarkedEpisodes()["results"].objOrNull()
                ?.get("data")?.arrOrNull().orEmpty().associate { ep ->
                ep.jsonObject["EPISODE_ID"]?.jsonPrimitive?.content to
                        ep.jsonObject["OFFSET"]?.jsonPrimitive?.content?.toLongOrNull()
            }

            dataArray.map { episode ->
                parser.run { episode.jsonObject.toEpisode(bookmarkMap) }
            }.reversed()
        } else {
            // Some albums (region-restricted / unavailable / placeholder) return a valid results
            // object with no SONGS (or SONGS without data). Guard the chain instead of !! so those
            // just yield an empty track list rather than crashing album load with an NPE.
            // As above: absent SONGS keeps the decided empty-track-list behaviour, a literal
            // throws rather than presenting the album as having no tracks.
            val dataArray = resultsObject["SONGS"]?.requireObj("SONGS")
                ?.get("data")?.requireArr("SONGS.data").orEmpty()
            val baseTracks = dataArray.mapIndexed { index, song ->
                val currentTrack = parser.run { song.jsonObject.toTrack() }
                val nextTrack = parser.run { dataArray.getOrNull(index + 1)?.jsonObject?.toTrack() }
                currentTrack.copy(
                    extras = currentTrack.extras + mapOf(
                        "NEXT" to nextTrack?.id.orEmpty(),
                        "album_id" to album.id
                    )
                )
            }
            // One batched availability pass, as the playlist path does - see
            // DeezerApi.markUnplayable for the signal, the fail-open list and the FALLBACK belt. The
            // NEXT chain above is built from dataArray rather than from this list, so marking after
            // it cannot disturb the chain.
            // ⚠️ THE FALLBACK BELT IS LOAD-BEARING HERE AND MEASURED IDLE
            // ELSEWHERE, WHICH IS NOT THE SAME AS SAFE. Album rows DO carry FALLBACK_ID: the stamp
            // is unconditional in the shared DeezerParser.toTrack
            // (`data["FALLBACK"]?.jsonObject?.str("SNG_ID").orEmpty()`), and toTrack unwraps itself,
            // so it does not depend on the graft that only the playlist and favorites paths run -
            // those grafts move DISPLAY fields (artists/album/cover/background), never this extra.
            // What is NOT measured is pipe's behaviour on an ALBUM top-level id that has a FALLBACK.
            // The 2026-10-25 capture covered the liked list only, where every FALLBACK-carrying id
            // answered with media PRESENT pointing at that same FALLBACK (16/16, equal durations) and
            // none answered null, so the belt never fired. Albums carry FALLBACK in the OPPOSITE
            // direction (top-level already correct for display), so if pipe ever answers media:null
            // for one, this belt is the only thing stopping a row that plays through its substitute
            // from being greyed out. Do not remove it as dead code on the strength of that 16/16.
            // ⚠⚠ THE SHOW BRANCH ABOVE DELIBERATELY DOES NOT GET THIS, and it
            // is not an oversight about podcasts being less important: pipe has NO episode in
            // `tracksByIds`. PodcastEpisode is its own type with its own root field
            // (`podcastEpisodesByIds(ids: [String!]!): [PodcastEpisode]!`), so episode ids sent to
            // tracksByIds come back as NULL NODES - which markUnplayable reads as "unknown" and
            // leaves alone, correctly. The result would be one wasted request per show open and not
            // one row marked. If episode availability is ever wanted it needs that other field and
            // its own mapping, not this call moved up a branch.
            api.markUnplayable(baseTracks)
        }
    }.toFeed()
}