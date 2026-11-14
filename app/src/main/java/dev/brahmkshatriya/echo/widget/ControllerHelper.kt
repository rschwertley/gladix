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

    // [2026-11-14] ONE CONNECTION ATTEMPT FOR THE PROCESS, ON PURPOSE FOR NOW (recorded with b1b80943, where PlayerViewModel
    // gained a bounded reconnect; see PlayerViewModel.reconnectIfDead). `callback` is assigned once, synchronously, by the
    // first register, so a failed connection leaves `controller` null for good and BaseWidget.onReceive returns at
    // `ControllerHelper.controller ?: return`: the widget's buttons do nothing until the process restarts. Separately,
    // unregister invokes `callback` but never sets it back to null, so once every widget is removed and one is added
    // again, register never reconnects either, even after a first connection that succeeded. Both read from source, not
    // seen on a device. NOT CHANGED, by the user's decision: in August the widget was ruled out as the cause of the
    // build-1039 service-creation storm BECAUSE `callback` is assigned once, synchronously. A widget that reconnects
    // would lose that property, so any fix here needs the same hard cap as reconnectIfDead and its own decision.
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
        if (callback == null) callback = getController(app) {
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