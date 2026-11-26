package dev.brahmkshatriya.echo.playback.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.utils.CoroutineUtils.futureCatching
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadDrawable
import kotlinx.coroutines.CoroutineScope

@UnstableApi
class PlayerBitmapLoader(
    val context: Context,
    private val scope: CoroutineScope
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String) = true

    override fun decodeBitmap(data: ByteArray) = scope.futureCatching {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Failed to decode bitmap")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.futureCatching {
        val cover = uri.getQueryParameter("actual_data")!!.toData<ImageHolder>().getOrThrow()
        // [2026-11-26] A resource image (the art_music placeholder, MediaItemUtils.toMetaData) skips Coil's memory cache: its key
        // ignores the theme, so this service-themed note was served to the UI's no-cover rows (dark on History). Drawn the
        // same way as before, so the car, notification and lock screen look unchanged. Network covers are cached as before.
        cover.loadDrawable(context, skipMemoryCacheForResources = true)?.toBitmapOrNull()
            ?: error("Failed to load bitmap of $cover")
    }
}