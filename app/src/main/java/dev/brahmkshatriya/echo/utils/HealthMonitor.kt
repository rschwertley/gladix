package dev.brahmkshatriya.echo.utils

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import java.util.concurrent.ConcurrentHashMap

class HealthMonitor(application: Application) {

    enum class Scope { PERSISTENT, MEMORY_ONLY }

    class ExtensionResolutionTimeout(extensionId: String, durationMs: Long) :
        Exception("extensionId=$extensionId durationMs=$durationMs")

    class ConsecutiveSkipException(skipCount: Int, lastExtensionId: String) :
        Exception("skipCount=$skipCount lastExtensionId=$lastExtensionId")

    class OrphanedSessionException(savedTrackCount: Int, firstTrackId: String) :
        Exception("savedTrackCount=$savedTrackCount firstTrackId=$firstTrackId")

    // Tripwire for the resumption fix: on restore, the track at the saved index must be the track
    // that was current at save time. Fires only if a future change re-poisons the persisted index
    // with a non-full-basis value (e.g. a windowed index). Diagnostic only — restore still proceeds.
    class ResumeIndexMismatchException(expectedId: String, actualId: String, index: Int, size: Int) :
        Exception("resume index/id mismatch: index=$index size=$size expected=$expectedId actual=$actualId")

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
        // BuildConfig.HAS_FIREBASE is a compile-time boolean (no Firebase type referenced), so in
        // no-json builds this branch is dead and FirebaseCrashlytics is never loaded.
        if (BuildConfig.HAS_FIREBASE) FirebaseCrashlytics.getInstance().recordException(exception)
    }
}
