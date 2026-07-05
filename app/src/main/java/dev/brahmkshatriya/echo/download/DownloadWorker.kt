package dev.brahmkshatriya.echo.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.NotificationUtil.createNotificationChannel
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.brahmkshatriya.echo.MainActivity.Companion.getMainActivity
import dev.brahmkshatriya.echo.R
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DownloadWorker(
    private val context: Context,
    params: WorkerParameters,
    downloader: Downloader,
) : CoroutineWorker(context, params) {

    private val manager = downloader.taskManager
    override suspend fun doWork(): Result = coroutineScope {
        setForeground(createNotification(0))
        // Progress updater runs on the WORKER's coroutine (a child of this coroutineScope), not
        // manager.scope. It only READS manager.taskFlow, so it's decoupled from manager.scope's
        // lifecycle. Being structured means a worker stop (WorkManager cancel) propagates cancellation
        // here, so an in-flight setForeground()'s future is cancelled before the WorkSpec finishes —
        // otherwise setForegroundAsync() throws "must complete before … returning a Result".
        val job = launch {
            manager.taskFlow.collectLatest {
                if (it.isEmpty()) removeNotification()
                else if (isActive) setForeground(createNotification(it.size))
            }
        }
        manager.awaitCompletion()
        // cancelAndJoin (not cancel): await the child's unwind so no setForeground() is in flight
        // when we return Result — the completion-path fix.
        job.cancelAndJoin()
        removeNotification()
        Result.success()
    }

    @OptIn(UnstableApi::class)
    private fun createNotification(
        tracks: Int,
    ): ForegroundInfo {
        createNotificationChannel(
            context, PROGRESS_CHANNEL_ID, R.string.download_progress, 0,
            NotificationUtil.IMPORTANCE_DEFAULT
        )
        val notificationBuilder = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
        val intent = getMainIntent(context)

        val tracksTitle = runCatching {
            context.resources.getQuantityString(R.plurals.number_songs, tracks, tracks)
        }.getOrNull() ?: context.getString(R.string.n_songs, tracks)

        return ForegroundInfo(
            NOTIF_ID,
            notificationBuilder
                .setSmallIcon(R.drawable.ic_downloading)
                .setContentTitle(context.getString(R.string.downloading_x, tracksTitle))
                .setContentIntent(intent)
                .setProgress(100, 0, true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build(),
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) 0
            else FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun removeNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }

    companion object {
        private const val NOTIF_ID = 0
        private const val PROGRESS_CHANNEL_ID = "download_progress_channel"

        fun getMainIntent(context: Context) = PendingIntent.getActivity(
            context,
            0,
            Intent(context, context.getMainActivity()).apply {
                putExtra("fromDownload", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )!!
    }
}