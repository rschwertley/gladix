package dev.brahmkshatriya.echo.ui.player.quality

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Streamable
import java.util.Locale

object FormatUtils {
    // Every label below is BARE - no separator - and joinParts adds " • " only between the pieces that
    // actually exist. The old builders each carried their own leading " • ", which meant a Format with
    // no sampleMimeType rendered the literal string "null" as the first segment (getMimeType returns
    // String? and a template renders null as "null"). Keep them bare.
    private const val SEP = " • "

    private fun joinParts(vararg parts: String?) =
        parts.filter { !it.isNullOrEmpty() }.joinToString(SEP)

    @OptIn(UnstableApi::class)
    private fun Format.bitrateLabel() = (bitrate / 1000).takeIf { it > 0 }?.let { "$it kbps" }

    private fun Format.frameRateLabel() = frameRate.toInt().takeIf { it > 0 }?.let { "$it fps" }

    private fun Format.getMimeType() = when (val mime = sampleMimeType?.replace("audio/", "")) {
        "mp4a-latm" -> "AAC"
        else -> mime?.uppercase()
    }

    /**
     * kHz, not Hz: "44.1 kHz" rather than "44100 Hz". It is how sample rates are written
     * everywhere else, and it is also the widest piece of this string - see the note on
     * [toAudioDetails] - so the shorter form buys the bitrate room before the ellipsis reaches it.
     *
     * ⚠️ ONE DECIMAL ONLY WHEN IT IS NOT A WHOLE NUMBER, so the common rates read
     * as "48 kHz" / "96 kHz" / "192 kHz" and the 44.1 family as "44.1 kHz" / "88.2 kHz" /
     * "176.4 kHz". The one case that rounds is 22050 Hz (some podcast streams), which prints
     * "22.1 kHz"; a second decimal for that alone would widen every other label.
     * ⚠️ Locale.ROOT, SO THE SEPARATOR IS ALWAYS A DOT. Nothing else in this pill
     * is localised ("320 kbps", "2ch"), and a comma decimal in a de/ru locale would read as a
     * thousands separator on a number that is already small.
     */
    private fun Format.hertzLabel() = sampleRate.takeIf { it > 0 }?.let { hz ->
        val khz = if (hz % 1000 == 0) (hz / 1000).toString()
        else String.format(Locale.ROOT, "%.1f", hz / 1000.0)
        "$khz kHz"
    }

    private fun Format.channelLabel() = channelCount.takeIf { it > 0 }?.let { "${it}ch" }

    // Codec and bitrate are joined by a SPACE, not a separator, so they read as one fact ("MPEG 320 kbps",
    // "FLAC 1411 kbps", "Opus 70 kbps" - the conventional form) and, more to the point, so truncation
    // cannot split them. Codec and bitrate are also the fields that vary MOST, and sample rate was the
    // widest piece of the string (63dp at 12sp against the bitrate's 59dp), so the old order spent the
    // most width on the least information and pushed the bitrate to where the ellipsis reaches first.
    // See the pill note in item_player_controls.xml.
    // ⚠️ [CORRECTED 2026-10-24] "SAMPLE RATE AND CHANNEL COUNT ARE CONSTANT PER
    // EXTENSION" IS NO LONGER TRUE, AND THE ORDERING ARGUMENT DOES NOT DEPEND ON IT. Deezer's HiFi
    // FLAC is 24-bit at up to 192 kHz and is served PER SONG ("not all songs have it"), so one
    // extension now yields 44.1 and 96 and 192 kHz on consecutive tracks - which makes the sample
    // rate a varying field worth reading, not a constant. It keeps its position because of the WIDTH
    // argument above, which stands on its own, and because [hertzLabel] now prints kHz and is no
    // longer the widest piece.
    @OptIn(UnstableApi::class)
    fun Format.toAudioDetails() = joinParts(
        listOfNotNull(getMimeType(), bitrateLabel()).joinToString(" ").takeIf { it.isNotEmpty() },
        hertzLabel(),
        channelLabel()
    )

    // Same shape as toAudioDetails on purpose - resolution and bitrate as one unit, frame rate after -
    // so the two do not diverge. Only reachable on Canvas/video streams.
    fun Format.toVideoDetails() = joinParts(
        listOfNotNull("${height}p".takeIf { height > 0 }, bitrateLabel()).joinToString(" ")
            .takeIf { it.isNotEmpty() },
        frameRateLabel()
    )

    fun Format.toSubtitleDetails() = label ?: language ?: "Unknown"

    private fun List<Tracks.Group>.getSelectedFormat(): Format? {
        return firstNotNullOfOrNull { trackGroup ->
            val index = (0 until trackGroup.length).firstNotNullOfOrNull { i ->
                if (trackGroup.isTrackSelected(i)) i else null
            } ?: return null
            trackGroup.getTrackFormat(index)
        }
    }

    // ⚠️ `selected` is assigned as a SIDE EFFECT inside the map lambda below, not returned through the
    // chain, and is recovered by the indexOf at the end. That is the part to read carefully here.
    //
    // The `map { … }.flatten()` is kept over `flatMap { … }` on purpose and the inspection suppressed:
    // the two-step form makes the group/index NESTING explicit — outer over groups, inner over track
    // indices, then flatten — which is how the rest of this function has to be read. The two are exactly
    // equivalent in this position: same output order, same side-effect order, one fewer intermediate list.
    // The ONE case where swapping them could change behaviour is a Sequence receiver, where flatMap is
    // lazy and map{}.flatten() is not, so the side effect would run at a different time. This receiver is
    // an eager List, so that does not apply — but it is why the swap deserves a thought rather than a
    // reflex if this ever moves.
    @Suppress("SimplifiableCallChain")
    fun List<Tracks.Group>.getSelected(): Pair<List<Pair<Tracks.Group, Int>>, Int?> {
        var selected: Pair<Tracks.Group, Int>? = null
        val trackGroups = map { trackGroup ->
            (0 until trackGroup.length).map { i ->
                val pair = Pair(trackGroup, i)
                val isSelected = trackGroup.isTrackSelected(i)
                if (isSelected) selected = pair
                pair
            }
        }.flatten()
        val select = trackGroups.indexOf(selected).takeIf { it != -1 }
        return trackGroups to select
    }

    // Codec / bitrate / resolution. Falls back to "unknown quality" only when NO format resolved at all -
    // which on the player means no source was ever prepared, so it doubles as a tell for that.
    private fun Tracks.formatDetails(context: Context): List<String> {
        val audios = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val videos = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val subtitles = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        return listOfNotNull(
            audios.getSelectedFormat()?.toAudioDetails(),
            videos.getSelectedFormat()?.toVideoDetails(),
            subtitles.getSelectedFormat()?.toSubtitleDetails()
        ).ifEmpty { listOf(context.getString(R.string.unknown_quality)) }
    }

    // ONE entry per source on a merged server, so this is unbounded in length: YouTube Music merges
    // several and contributes e.g. "Audio", "Audio-audio/webm-Web...". Deezer does not merge, so it is a
    // single short title or nothing - which is why the two extensions behave so differently downstream.
    private fun sourceTitles(server: Streamable.Media.Server?, index: Int?): List<String> =
        server?.run {
            if (merged) sources.mapNotNull { it.title }
            else listOfNotNull(sources.getOrNull(index ?: -1)?.title)
        }.orEmpty()

    // SOURCES FIRST. For QualitySelectionBottomSheet, which joins the returned list with a newline into
    // a multi-line block where the source reads as a heading over its formats. Do not reorder this to fix
    // a truncation problem - this consumer does not truncate; use getDetailsFormatFirst instead.
    fun Tracks.getDetails(
        context: Context, server: Streamable.Media.Server?, index: Int?,
    ): List<String> = sourceTitles(server, index) + formatDetails(context)

    // FORMATS FIRST, sources appended. For the single-line consumers that ellipsize - the phone player's
    // metadata pill and TV's tv_track_subtitle. Same content, assembly order reversed, because when the
    // line is cut the codec and bitrate are the part worth keeping and the source titles are not: with a
    // merged server the old order spent the whole line on source titles and lost "Opus - 70kbps"
    // entirely. Ellipsis now falls on the part that was already unreadable.
    // Sources are still appended rather than dropped: on a non-merged extension the title is short and
    // occasionally meaningful, and the full list is one tap away in the quality sheet either way.
    fun Tracks.getDetailsFormatFirst(
        context: Context, server: Streamable.Media.Server?, index: Int?,
    ): List<String> = formatDetails(context) + sourceTitles(server, index)
}
