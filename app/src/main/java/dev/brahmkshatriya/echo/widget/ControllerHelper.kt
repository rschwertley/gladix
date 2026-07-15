package dev.brahmkshatriya.echo.widget

import android.graphics.Bitmap
import androidx.media3.session.MediaController
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.getController
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverIndex
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ControllerHelper {

    var controller: MediaController? = null
    var callback: (() -> Unit)? = null
    var image: Bitmap? = null
    var listener: WidgetPlayerListener? = null

    // Last-played fallback for the widget when there's no live controller item (post-process-death / nothing
    // playing). Decoded OFF-MAIN in register() below and read synchronously by BaseWidget.updateView, so the
    // widget never parses the (unbounded) saved queue on the main thread — that was a large-queue ANR on slow
    // devices. It's a plain in-memory cache: a stale read only shows a slightly-old last-played for one frame.
    @Volatile
    var lastKnownItem: MediaState.Unloaded<Track>? = null

    val map = mutableMapOf<String, () -> Unit>()

    @Synchronized
    fun register(app: App, key: String, updateCallback: () -> Unit): Job? {
        map[key] = updateCallback
        // Warm the last-played fallback off the main thread whenever there's no live current item to show.
        // Runs on app.scope (Dispatchers.IO, app-lifetime). The widget renders its placeholder until this
        // lands, then updateWidgets() refreshes it. Skipped when a live item already exists (no decode at all).
        // Returned to the caller so a BroadcastReceiver can tie its goAsync() lifetime to the decode.
        val decodeJob = if (controller?.currentMediaItem == null) app.scope.launch {
            val ctx = app.context
            val tracks = with(ctx) { recoverTracks() }
            val index = with(ctx) { recoverIndex() } ?: 0
            lastKnownItem = tracks?.getOrNull(index)?.first ?: tracks?.firstOrNull()?.first
            withContext(Dispatchers.Main) { updateWidgets() }
        } else null
        if (callback == null) callback = getController(app.context) {
            controller = it
            val playerListener = WidgetPlayerListener { img ->
                image = img
                updateWidgets()
            }
            listener = playerListener
            it.addListener(playerListener)
            playerListener.controller = it
            updateWidgets()
        }
        return decodeJob
    }

    @Synchronized
    fun unregister(key: String) {
        map.remove(key)
        if (map.isNotEmpty()) return
        callback?.invoke()
        controller = null
        listener?.removed()
        listener = null
        image = null
    }

    @Synchronized
    fun updateWidgets() {
        map.values.forEach { it() }
    }
}