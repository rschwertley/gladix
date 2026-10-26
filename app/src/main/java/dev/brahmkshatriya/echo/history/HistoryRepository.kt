package dev.brahmkshatriya.echo.history

import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.history.db.HistoryDao
import dev.brahmkshatriya.echo.history.db.HistoryEntity
import dev.brahmkshatriya.echo.history.db.toSlimContext
import dev.brahmkshatriya.echo.history.db.toSlimKeepingRoutingKeys
import dev.brahmkshatriya.echo.utils.Serializer.toJson

class HistoryRepository(private val dao: HistoryDao) {

    fun getHistory() = dao.getAll()

    fun getLatest() = dao.getLatest()

    suspend fun getByExtension(extensionId: String, limit: Int = 50) =
        dao.getByExtension(extensionId, limit)

    suspend fun recordTrack(extensionId: String, track: Track, context: EchoMediaItem? = null) {
        dao.upsert(
            HistoryEntity(
                trackId = track.id,
                extensionId = extensionId,
                playedAt = System.currentTimeMillis(),
                // ⚠⚠ THE SAME ALLOWLIST THE SAVED QUEUE USES, AND FOR THE SAME
                // REASON. A History tap with a REPLAYABLE context reloads that context
                // (backfillQueue → freshContextUpcoming), so the stored track's extras do not
                // matter there - but a NO-CONTEXT tap replays the stored track itself, and liked-list
                // plays now carry context = null, so a FALLBACK-grafted favourite recorded with plain
                // toSlim() came back unplayable: no FALLBACK_ID, no substitution, skipped. See
                // HistoryEntity.toSlimKeepingRoutingKeys.
                // ⚠️ NO MIGRATION, DELIBERATELY: rows written before this keep
                // plain-toSlim extras and are capped at 150, so they age out on their own. The
                // oversized-row migration in HistoryDatabase is a different job and stays as it is.
                trackData = track.toSlimKeepingRoutingKeys().toJson(),
                contextData = context?.toSlimContext()?.toJson(),
            )
        )
        dao.trimToLimit()
    }

    suspend fun clearHistory() = dao.deleteAll()

    suspend fun delete(trackId: String, extensionId: String) = dao.delete(trackId, extensionId)
}
