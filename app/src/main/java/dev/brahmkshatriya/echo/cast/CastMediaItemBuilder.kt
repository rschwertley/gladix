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
 * ⚠️ mediaId IS THE TRACK ID, AND THAT IS CHECKED RATHER THAN ASSUMED. It becomes the
 * receiver's contentId (:172-173) while contentUrl carries the URL separately (:181), so the receiver
 * has both. Verified against the reference app (Echo Expressive 4.1.2), which casts successfully to the
 * same Default Media Receiver with mediaId set to the original item's id rather than to the URL - so
 * this shape is not the reason a load fails.
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
            .setMediaId(track.id)
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
