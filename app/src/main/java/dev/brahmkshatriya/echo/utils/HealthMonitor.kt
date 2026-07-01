package dev.brahmkshatriya.echo.utils

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.ConcurrentHashMap

class HealthMonitor(application: Application) {

    enum class Scope { PERSISTENT, MEMORY_ONLY }

    class ExtensionResolutionTimeout(extensionId: String, durationMs: Long) :
        Exception("extensionId=$extensionId durationMs=$durationMs")

    class ConsecutiveSkipException(skipCount: Int, lastExtensionId: String) :
        Exception("skipCount=$skipCount lastExtensionId=$lastExtensionId")

    class OrphanedSessionException(savedTrackCount: Int, firstTrackId: String) :
        Exception("savedTrackCount=$savedTrackCount firstTrackId=$firstTrackId")

    // Benign teardown race: a media3 datasource close()/read() raised an IllegalStateException
    // (checkState lifecycle assertion) because the player/cache was released while a load was
    // still closing. Suppressed at the player layer; reported rate-limited so we retain frequency
    // telemetry. The original ISE is the cause, so its (retraceable) close-cascade stack is kept.
    class DataSourceTeardownRaceException(cause: Throwable) :
        Exception("IllegalStateException during media3 datasource close/teardown", cause)

    private val prefs = application.getSharedPreferences("gladix_health_monitor", Context.MODE_PRIVATE)
    private val memoryTimestamps = ConcurrentHashMap<String, Long>()

    fun report(exception: Exception, scope: Scope, cooldownMs: Long) {
        val signature = "${exception.javaClass.simpleName}_${exception.message}"
        val now = System.currentTimeMillis()
        val lastReported = when (scope) {
            Scope.PERSISTENT -> prefs.getLong(signature, 0L)
            Scope.MEMORY_ONLY -> memoryTimestamps[signature] ?: 0L
        }
        if (now - lastReported < cooldownMs) return
        when (scope) {
            Scope.PERSISTENT -> prefs.edit { putLong(signature, now) }
            Scope.MEMORY_ONLY -> memoryTimestamps[signature] = now
        }
        FirebaseCrashlytics.getInstance().recordException(exception)
    }
}
