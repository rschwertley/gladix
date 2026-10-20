package dev.brahmkshatriya.echo.cast

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlin.io.encoding.Base64

/**
 * Turns a [CastPlan] plus a [Track] into the one MediaItem the receiver plays.
 *
 * ⚠⚠ EVERY FIELD HERE IS REQUIRED BY DefaultMediaItemConverter, READ FROM media3-cast 1.11.0
 * RATHER THAN INFERRED. toMediaQueueItem does `checkNotNull(mediaItem.localConfiguration)` (:140) and
 * `setContentType(mediaItem.localConfiguration.mimeType)` (:180) - so the MIME must be on the LOCAL
 * CONFIGURATION via setMimeType, never on the metadata, and an item with no uri throws rather than
 * degrading. The metadata it reads is title, subtitle, artist, albumArtist, albumTitle, artworkUri,
 * composer, discNumber and trackNumber (:142-170); anything else we set is dropped.
 *
 * ⚠⚠ [CORRECTED 2026-10-20] mediaId IS DELIBERATELY NOT SET, AND THE OLD CLAIM HERE
 * WAS THE REASON CASTING NEVER PLAYED A NOTE. It read: "mediaId IS THE TRACK ID, AND THAT IS
 * CHECKED RATHER THAN ASSUMED ... Verified against the reference app, which casts successfully with
 * mediaId set to the original item's id rather than to the URL - so this shape is not the reason a
 * load fails." Every clause was wrong in a different way, and the device settled it: build 1140
 * logged a LOAD, the receiver sat in playerState=5 to the 15 s timeout, and LocalMediaServer logged
 * NOT ONE accept - the receiver never dialled us at all.
 * ⚠⚠ WHAT THE CONVERTER ACTUALLY DOES, DECODED FROM THE AAR (no sources jar is
 * published for media3-cast 1.11.0, so this is javap on classes.jar, toMediaQueueItem):
 *     268-278  contentUrl source = localConfiguration.uri.toString()
 *     279-300  contentId = mediaId.equals("") ? uri : mediaId
 *     322-328  new MediaInfo.Builder(contentId)
 *     342      setContentUrl(uri)
 * So a NON-EMPTY mediaId puts our TRACK ID in contentId - which is what the 1140 log shows
 * (contentId=3c541c97) - and a receiver that fetches from contentId has nothing to fetch.
 * ⚠️ AND THE REFERENCE WAS MISREAD, NOT MISQUOTED. Its converter is a MODIFIED copy
 * (decompiled ia3/ma0) whose condition is INVERTED: `!mediaId.equals("") ? uri : mediaId`, so it
 * sends the URI as contentId whenever it has an id at all - i.e. always. "The reference uses the
 * item id" described the field it SET, not the field it SENT.
 * ⚠️ LEAVING mediaId AT ITS DEFAULT IS THE WHOLE FIX, rather than setting it to the
 * URL: both produce identical wire bytes (contentId == contentUrl == the served URL) and this way
 * the token lives in exactly one field. Nothing of ours reads the CAST item's mediaId - every
 * castedMediaId, pendingMediaId, prepared key and Copy id in CastSessionController comes from the
 * LOCAL item - so the default costs no correlation. If that ever stops being true, set it to the
 * served URL; never to the track id.
 */
object CastMediaItemBuilder {

    /**
     * The player key for a track's source, byte-identical to the one MediaItemUtils.buildForSource
     * mints, because StreamableResolver has to be able to decode it back.
     *
     * ⚠⚠ sourceIndex IS A LITERAL 0, AND THAT IS THE RESOLVER'S OWN CONVENTION RATHER THAN A
     * GUESS. StreamableMediaSource's single-source branch passes a literal 0 to buildForSource, and
     * CacheOnlyTailSource's note records why `item.sourceIndex` is the wrong field to reach for: the
     * extras value is -1 for every single-source track, because buildSource only runs in the
     * MULTI-source branch. Multi-source servers would need the real index; none is known to exist.
     */
    fun keyFor(track: Track, extensionId: String): String = Base64.encode(
        MediaItemUtils.Key(track.id, 0, extensionId).toJson().toByteArray()
    )

    /**
     * [servedUrl] is what LocalMediaServer.urlForPlan returned, and is ignored for
     * [CastPlan.Direct] - which carries its own origin URL and is never proxied. Null when the item
     * cannot be built, which the caller reports.
     */
    fun build(track: Track, plan: CastPlan, servedUrl: String?): MediaItem? {
        val url = when (plan) {
            is CastPlan.Direct -> plan.url
            is CastPlan.Refused -> return null
            else -> servedUrl ?: return null
        }
        return MediaItem.Builder()
            // No setMediaId: the default empty id is what makes DefaultMediaItemConverter put the
            // URL into contentId as well as contentUrl. See the note above before re-adding it.
            .setUri(url)
            // The receiver decides whether it can play the item from this alone. CastPlan.mimeFor
            // derives it from the source's own type, extension or container bytes - see the ladder
            // there; there is no cap and no fallback retry.
            .setMimeType(plan.mime)
            .setMediaMetadata(metadataFor(track))
            .build()
    }

    private fun metadataFor(track: Track): MediaMetadata {
        val artists = track.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
        return MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(artists)
            .setSubtitle(artists)
            .setAlbumTitle(track.album?.title)
            .setAlbumArtist(
                track.album?.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() }
            )
            .setTrackNumber(track.albumOrderNumber?.toInt())
            .setDiscNumber(track.albumDiscNumber?.toInt())
            .setArtworkUri(artworkUri(track))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    }

    /**
     * ⚠️ NETWORK-BACKED COVERS ONLY, AND LOCAL FILES DELIBERATELY GET NONE. The converter turns
     * this into `metadata.addImage(new WebImage(artworkUri))` (:160), fetched by the RECEIVER over its
     * own network - so only an absolute http(s) URL can work. ResourceUriImageHolder (content:// and
     * file://, i.e. offline and downloaded tracks) and ResourceIdImageHolder are unreachable from the
     * TV, so those cast with no art rather than with a broken image request.
     * ⚠️ AN ARTWORK ENDPOINT ON LocalMediaServer IS THE ALTERNATIVE AND IS NOT BUILT: it would
     * need the app's image pipeline to resolve an ImageHolder to bytes on a serving thread, which is a
     * second content type, a second token kind and a second cache. If casting offline art ever
     * matters, that is the shape - not a hack here.
     * ⚠️ AUTH HEADERS ARE DROPPED. NetworkRequest can carry headers the receiver will not send,
     * so an extension whose covers need them shows none; a 403 there costs the image, never the audio.
     */
    private fun artworkUri(track: Track) = (track.cover as? ImageHolder.NetworkRequestImageHolder)
        ?.request?.url
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.toUri()
}
