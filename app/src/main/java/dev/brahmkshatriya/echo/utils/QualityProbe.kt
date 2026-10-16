package dev.brahmkshatriya.echo.utils

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.brahmkshatriya.echo.common.models.Streamable
import java.util.concurrent.atomic.AtomicInteger

/**
 * TEMPORARY DIAGNOSTIC — "320 selected, 128 plays, only while crossfade is enabled".
 *
 * ⚠⚠ APPROVED 2026-10-16 FOR ONE CAPTURE ROUND. INVENTORY FOR REMOVAL — nine call sites, all
 * grep-able as "QUALITY-PROBE", plus this file. Delete all nine and this file in ONE commit.
 *
 * ⚠️ THE ONLY NUMBERING IS THE `p=` VALUE. An earlier draft also numbered the SITES 1..9, which
 * disagreed with `p=` (the Deezer site was 9 but emits p=5-*, the format site was 7 but emits
 * p=6-format) and made the capture unreadable against its own hypothesis table. Site ordinals are
 * gone; each call site's comment names the exact `p=` value it prints. Thirteen lines, ten values:
 *   p=1-select     MediaItemUtils.toMetaData                  selection
 *   p=2-sheet      PlayerViewModel.changeServer               sheet pick
 *   p=2-apply      PlayerViewModel.changeCurrent              sheet apply (the silent fullIndex<0)
 *   p=3-prepare    StreamableMediaSource.prepareSourceInternal
 *   p=3-canUpdate  StreamableMediaSource.canUpdateMediaItem
 *   p=4-resolve    StreamableLoader.loadServer                what reaches the extension
 *   p=5-parse      DeezerTrackClient.loadStreamableMedia      suffix + the ?: "128" default
 *   p=5-branch     DeezerTrackClient.createStreamableForQuality   relay attempted at all?
 *   p=5-response   DeezerTrackClient.createStreamableForQuality   reqFormat vs gotFormat
 *   p=5-stepdown   DeezerTrackClient.createStreamableForQuality   x3 branches, each with a trigger
 *   p=5-final      DeezerTrackClient.createStreamableForQuality   what it returned
 *   p=6-format     PlayerEventListener.onTracksChanged        GROUND TRUTH (decoder Format)
 * PlayerService.syncCrossfade is the tenth site and emits NO line — it registers crossfade seconds
 * and the sidecar's identity so every line above can carry `xfade=` and `who=`.
 *
 * ⚠⚠ WHY A PROBE AT ALL, AND WHAT READING COULD NOT SETTLE. The relay-vs-own-account decision is
 * ONE expression on a string (DeezerTrackClient.createStreamableForQuality: `quality != "128" &&
 * quality != "mp3"`), and `quality` comes only from the chosen placeholder's id suffix. So 128 audio
 * means the `:128` placeholder was chosen — yet the sheet reports serverIndex == the 320 slot, and
 * `selectServerIndex`'s median over [flac(9), 320(6), 128(3)] is provably the 320 slot. Something
 * between those two facts is false and NO amount of further reading has separated them.
 * ⚠️ AND NOTHING IN THE CROSSFADE PATH EXPLAINS IT, which is why the probe spans the whole chain
 * rather than the crossfade. CrossfadeController's entire player surface is reads plus one
 * seekToNextMediaItem; StreamableMediaSource.Factory is a plain nested class so the sidecar's second
 * instance shares no state; the sidecar's own Factory is never used (its source is tail.factory).
 * The probe therefore does NOT assume a crossfade-side cause - see the hypothesis table in the
 * report that accompanied it.
 *
 * ⚠️ NEVER LOG A CREDENTIAL. No ARL, no sid, no licence token, no TRACK_TOKEN, no full URLs -
 * host only, via [host]. The extension side obeys the same rule.
 */
object QualityProbe {

    private const val TAG = "QUALITY-PROBE"

    /** One sequence number per resolve attempt, so lines from different tracks cannot be interleaved. */
    private val seqCounter = AtomicInteger(0)
    fun nextSeq() = seqCounter.incrementAndGet()

    /**
     * Crossfade seconds as PlayerService last applied them; 0 = off. Registered rather than read from
     * prefs at each site so every line reports the value the PLAYER is actually running with.
     */
    @Volatile
    var crossfadeSeconds: Int = 0
        private set

    /** identityHashCode of the sidecar ExoPlayer, 0 when none. Lets [who] name the player. */
    @Volatile
    private var sidecarId: Int = 0

    fun registerCrossfade(seconds: Int, sidecar: Player?) {
        crossfadeSeconds = seconds
        sidecarId = sidecar?.let { System.identityHashCode(it) } ?: 0
    }

    /** "main#1a2b3c" / "sidecar#1a2b3c" — identity, not just a role, so two instances are visible. */
    fun who(player: Player?): String {
        val id = player?.let { System.identityHashCode(it) } ?: return "none"
        val role = if (id == sidecarId && id != 0) "sidecar" else "main"
        return role + "#" + Integer.toHexString(id)
    }

    /** MediaItem identity, so "the item the sheet wrote" and "the item being prepared" are comparable. */
    fun itemId(item: MediaItem?): String =
        item?.let { "#" + Integer.toHexString(System.identityHashCode(it)) } ?: "null"

    /** Host only — never a full URL, which can carry a signed token. */
    fun host(url: String?): String = runCatching {
        url?.let { android.net.Uri.parse(it).host } ?: "null"
    }.getOrDefault("unparsable")

    /**
     * Renders a server list as index/title/quality/id-suffix, which is the four-tuple the whole
     * question turns on: the suffix is what becomes `quality` in the extension.
     */
    fun servers(list: List<Streamable>): String = list.mapIndexed { i, s ->
        i.toString() + ":" + s.title + "/q" + s.quality + "/" + s.id.substringAfterLast(':')
    }.joinToString(",", "[", "]")

    /**
     * Every line carries seq, mediaId, player identity and crossfade state, so a capture can be
     * grouped by seq and read as one chain even with two players and interleaved tracks.
     */
    fun log(point: String, seq: Int, mediaId: String?, player: Player?, fields: String) {
        Log.d(
            TAG,
            "p=" + point + " seq=" + seq + " xfade=" + crossfadeSeconds +
                " who=" + who(player) + " id=" + mediaId + " " + fields
        )
    }

    /** For sites with no Player in scope (selection, the sheet, the extension boundary). */
    fun log(point: String, seq: Int, mediaId: String?, fields: String) =
        log(point, seq, mediaId, null, fields)
}
