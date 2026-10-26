package dev.brahmkshatriya.echo.history.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
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
// ⚠⚠ PATTERN, NOT THREE SEPARATE BUGS: ANY SIGNAL READ FROM `extras` ON A TRACK THAT MAY
// HAVE BEEN SLIMMED IS UNRELIABLE BY CONSTRUCTION. `extras = emptyMap()` below is wholesale - it does not
// know which keys carry identity, capability or availability, so a slimmed track is INDISTINGUISHABLE
// from a track whose extension never set those keys. Every consumer that reads extras and infers
// something about the TRACK is really inferring something about the track's PROVENANCE.
// THREE INSTANCES, ALL FOUND SEPARATELY, ALL THE SAME SHAPE:
//   1. extension_id destroyed -> radio generation broke, loadTrack's failure was masked behind
//      Cached.loadMedia's fallback, and Unified tracking died silently for six weeks.
//   2. TRACK_TOKEN destroyed -> DeezerTrackClient.loadTrack's self-heal gate reads an empty token as
//      "needs re-fetch", which is correct, but a 2026-09-12 investigation then read the SAME emptiness
//      as "this track cannot play". It measured 12/12 against unplayable tracks and was refuted two
//      captures later. See the note at that gate.
//   3. (the general case) any future extras key used as a capability or availability signal.
// ⚠️ AND SELECTIVE SLIMMING ALREADY EXISTS - toSlimContext below keeps Radio extras precisely
// because the radio work depends on them, and is the single source of truth for that. So the precedent is
// PRESERVE WHAT MATTERS, not empty the map; there is no argument that wholesale stripping is required.
// Before adding a fourth consumer of an extras key, ask whether the track could have come through here.
// ⚠⚠ DROPPING `streamables` HERE IS DELIBERATE AND LOAD-BEARING. DO NOT PUT THEM BACK.
// July 2026: History was storing a full serialized Track + context JSON (covers, artists, album
// objects, streamables, tokens, extras) when it needs a fraction of that, and the bulk blew the
// CursorWindow - a crash at row 53. The same slimming fixed a cold-start crash for three users.
// `encodeDefaults` is unset (defaults false), CONFIRMED at the time, so the now-default-valued
// streamables do not serialize at all. Empty streamables in a persisted queue is the INTENDED,
// VERIFIED design, not a loss.
// ⚠️ THE OTHER HALF OF THE CONTRACT IS loadTrack, AND THAT IS WHERE BUGS FROM THIS LIVE.
// Each extension is expected to repopulate streamables in loadTrack (Deezer rebuilds them from
// track.extras["TRACK_TOKEN"]; network extensions get them back by re-fetching). An extension
// whose loadTrack does not is in VIOLATION, and the symptom lands far from here - as a
// TrackUnavailableException out of StreamableLoader.loadServer on a restored queue.
// OfflineExtension.loadTrack was the identity function and did exactly that; see the note there.
// So: a "no playable source" report on a restored queue is a question about that extension's
// loadTrack. It is NOT a reason to revisit this line.
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

// The extension-owned key kept by [toSlimKeepingRoutingKeys], by literal because there is no shared
// constant: app code cannot see the Deezer extension's strings. The precedent for naming one here is
// CastSessionController reaching into that extension's prefs by literal id.
private const val FALLBACK_ID_EXTRA = "FALLBACK_ID"

/**
 * ⚠⚠ A Playable VERDICT MUST NOT SURVIVE A SLIM - AND isPlayable IS A
 * FIELD, SO [toSlim]'s `copy` CARRIED IT THROUGH. The Deezer extension stamps Playable.No on
 * liked/playlist rows whose pipe node has no media (DeezerApi.markUnplayable), and that verdict is a
 * snapshot of Deezer's catalogue at list-load time, not a fact about the track.
 * ⚠⚠ PERSISTED, IT BECOMES PERMANENT AND INVERTS THE FAIL-OPEN DESIGN.
 * StreamableLoader.loadServer throws TrackUnavailableException on any non-Yes value BEFORE making a
 * request, so a restored queue entry or a history row carrying one would skip instantly and forever
 * with nothing on any path to re-check it - the "state clearable only by an action its own value
 * disables" shape. The extension fails open on every uncertainty precisely so a wrong verdict costs
 * one list load; carrying it into the queue file would make it cost a reinstall.
 * ⚠️ RESET, NOT DROP, because Yes is the type's own default and every
 * consumer already reads it as "no opinion". The verdict is cheap to recompute - it comes back with
 * the next list load - so losing it costs nothing and keeping it costs a track.
 * ⚠️ ONE CALLER OPTS OUT, AND IT IS NOT AN EXCEPTION TO THE ARGUMENT
 * ABOVE - IT IS THE ARGUMENT NOT APPLYING. See [toSlimKeepingRoutingKeys]'s `keepPlayable`.
 */
private fun Track.resetPlayable(): Track =
    if (isPlayable == Track.Playable.Yes) this else copy(isPlayable = Track.Playable.Yes)

/**
 * [toSlim], plus the few NON-DISPLAY keys that routing and stream recovery need. The rule lives
 * here, beside toSlim, because this file owns slimming; ResumptionUtils.queueSlim and
 * HistoryRepository.recordTrack are both callers of THIS, not copies of it.
 *
 * ⚠⚠ KEEP A KEY, NEVER A PAYLOAD - that is the whole rule, and it is what
 * separates this from the re-fatten the build-1008/1013 OOMs forbid. Two keys qualify:
 *   UnifiedExtension.EXTENSION_ID  routes a Unified track to its sub-extension. Without it a
 *                                  restored Unified track throws ExtensionNotFoundException(null)
 *                                  in radio(), loadFeed() and loadTrack() - see the restamped()
 *                                  note in ResumptionUtils for the four symptoms.
 *   FALLBACK_ID                    IS THE PLAYABLE RECORDING for a FALLBACK-grafted Deezer
 *                                  favourite: the track's own id is the unavailable original that
 *                                  Deezer pre-substituted (DeezerLibraryClient.graftFavTrack), so
 *                                  an entry without this key cannot reach the substitution at all
 *                                  and the track is skipped as unavailable.
 * ⚠️ TRACK_TOKEN IS STILL DROPPED, DELIBERATELY. It is the heavy,
 * perishable half; DeezerTrackClient.loadTrack already re-fetches on an empty one, and persisting
 * it would store a credential that is stale by the time it is read. See the "Baby Beluga" note at
 * createStreamableForQuality for what a stale token costs.
 * ⚠️ NON-BLANK ONLY: DeezerParser.toTrack writes FALLBACK_ID
 * unconditionally with `.orEmpty()`, so an un-grafted track carries "" - storing that is file size
 * for no information, and an empty value would also beat a real one in a later `+` merge.
 *
 * @param keepPlayable skips [resetPlayable]. FOR ONE CALLER, AndroidAutoCallback.saveDeclaredList,
 * and it does not weaken that function's rule - it is that rule's premise failing to hold.
 * ⚠⚠ THE AA FILE CANNOT OUTLIVE ITS LIST: it is rewritten from a fresh
 * DeezerApi.markUnplayable pass on EVERY browse, and the signature below it now includes
 * isPlayable, so a changed verdict rewrites the file. The stale-forever failure [resetPlayable]
 * exists to prevent has no way to happen there.
 * ⚠⚠ AND KEEPING IT IS WHAT STOPS THE CAR DOING THE WORK TWICE. The AA
 * tap builds MediaState.Unloaded from these stored Tracks, so a stored Playable.No makes
 * StreamableLoader.loadServer throw with ZERO requests. Reset, every dead track instead runs the
 * full DeezerTrackClient.createStreamableForQuality ladder first - up to ~22 Deezer calls each -
 * and a run of ADJACENT dead likes (a pulled album is exactly that shape) turns one browse-and-play
 * into hundreds of refused requests against an account with a daily ceiling.
 * ⚠️ THE SAVED QUEUE AND HISTORY KEEP THE RESET, which is the line
 * between the two: both are read long after the list that produced them, with nothing to
 * regenerate them from. Do not add a second opt-out without that same "rewritten every time"
 * property to point at.
 */
fun Track.toSlimKeepingRoutingKeys(keepPlayable: Boolean = false): Track =
    toSlim().let { if (keepPlayable) it else it.resetPlayable() }.let { slim ->
        val keep = extras
            .filterKeys { it == UnifiedExtension.EXTENSION_ID || it == FALLBACK_ID_EXTRA }
            .filterValues { it.isNotEmpty() }
        if (keep.isEmpty()) slim else slim.copy(extras = keep)
    }

// A stored context only needs id/type/title/cover (for the "Go to <context>" button + re-resolution
// by id, which happens with loaded=false). Radio is the exception: its re-resolution reads extras
// (kind + seeds), so keep those; drop everything else's extras/description.
fun EchoMediaItem.toSlimContext(): EchoMediaItem = copyMediaItem(
    description = null,
    subtitle = null,
    extras = if (this is Radio) extras else emptyMap()
)
