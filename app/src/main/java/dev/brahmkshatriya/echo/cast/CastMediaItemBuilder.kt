package dev.brahmkshatriya.echo.cast

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlin.io.encoding.Base64

/**
 * Builds the one MediaItem the receiver plays, pointed at LocalMediaServer.
 *
 * ⚠⚠ EVERY FIELD HERE IS REQUIRED BY DefaultMediaItemConverter, READ FROM media3-cast 1.11.0
 * RATHER THAN INFERRED. toMediaQueueItem does `checkNotNull(mediaItem.localConfiguration)` (:140) and
 * then `setContentType(mediaItem.localConfiguration.mimeType)` (:180) - so the MIME must be on the
 * LOCAL CONFIGURATION via setMimeType, NOT on the metadata, and an item without a uri throws rather
 * than degrading. contentId comes from mediaId unless it is the default (:172-173). The metadata it
 * reads is title, subtitle, artist, albumArtist, albumTitle, artworkUri, composer, discNumber and
 * trackNumber (:142-170) - anything else we set is dropped on the floor.
 *
 * ⚠⚠ THE KEY IS BUILT THE SAME WAY MediaItemUtils.buildForSource BUILDS IT, and it has to be
 * byte-identical or the server cannot resolve it: Base64 of Key(trackId, sourceIndex, extensionId).
 * StreamableResolver maps that string back through toKey() and reads `current[trackId]` - the SHARED
 * state.servers map - so the proxy serves exactly the server the local loader already resolved, with
 * the same signed URL and therefore the same cache key. Minting a key for a different sourceIndex
 * would resolve a second time and guarantee a cache miss; see CacheOnlyTailSource's note.
 */
object CastMediaItemBuilder {

    /**
     * Null when the server is not up or the key cannot be served - the caller shows a message and
     * does not cast, rather than handing the receiver a URL that will 404.
     */
    fun build(
        track: Track,
        extensionId: String,
        sourceIndex: Int,
        streamable: Streamable,
        urlFor: (String) -> String?,
    ): MediaItem? {
        val key = Base64.encode(
            MediaItemUtils.Key(track.id, sourceIndex, extensionId).toJson().toByteArray()
        )
        val url = urlFor(key) ?: return null
        return MediaItem.Builder()
            // The LOCAL mediaId, so a cast-side callback can be matched back to our queue item. The
            // converter copies it into contentId (:172), which is what the receiver echoes back.
            .setMediaId(track.id)
            .setUri(url)
            // The receiver decides whether it can play the item from this alone, so it has to be
            // right - there is no cap and no fallback; see CastState.mimeFor.
            .setMimeType(CastState.mimeFor(streamable))
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
            .setAlbumArtist(track.album?.artists?.joinToString(", ") { it.name }?.takeIf {
                it.isNotBlank()
            })
            // Read by the converter (:165-170) and shown by some receivers; we have them for album
            // tracks and they cost nothing when null.
            .setTrackNumber(track.albumOrderNumber?.toInt())
            .setDiscNumber(track.albumDiscNumber?.toInt())
            .setArtworkUri(artworkUri(track))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    }

    /**
     * ⚠️ NETWORK-BACKED COVERS ONLY, AND LOCAL FILES DELIBERATELY GET NONE. The converter turns
     * this into `metadata.addImage(new WebImage(artworkUri))` (:160), which the RECEIVER fetches over
     * its own network - so the only thing that can work is an absolute http(s) URL.
     * ImageHolder.ResourceUriImageHolder (content:// and file://, i.e. offline and downloaded tracks)
     * and ResourceIdImageHolder are unreachable from the TV, so those tracks cast with no art rather
     * than with a broken image request.
     * ⚠️ THE ALTERNATIVE IS AN ARTWORK ENDPOINT ON LocalMediaServer and it is deliberately not
     * built: it would need the app's image pipeline to resolve an ImageHolder to bytes on the serving
     * thread, which is a second content type, a second token kind and a second cache. If casting
     * offline tracks with art ever matters, that is the shape - not a hack here.
     * ⚠️ AUTH HEADERS ARE DROPPED. NetworkRequest can carry headers the receiver will not send.
     * Every extension whose covers need headers therefore shows no art; none observed so far does,
     * and a 403 on the receiver costs the image, never the audio.
     */
    private fun artworkUri(track: Track) = (track.cover as? ImageHolder.NetworkRequestImageHolder)
        ?.request?.url
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.toUri()
}
