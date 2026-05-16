package dev.brahmkshatriya.echo.history

import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.history.db.HistoryDao
import dev.brahmkshatriya.echo.history.db.HistoryEntity
import dev.brahmkshatriya.echo.utils.Serializer.toJson

class HistoryRepository(private val dao: HistoryDao) {

    fun getHistory() = dao.getAll()

    fun getLatest() = dao.getLatest()

    suspend fun recordTrack(extensionId: String, track: Track, context: EchoMediaItem? = null) {
        dao.upsert(
            HistoryEntity(
                trackId = track.id,
                extensionId = extensionId,
                playedAt = System.currentTimeMillis(),
                trackData = track.toJson(),
                contextData = context?.toJson(),
            )
        )
    }

    suspend fun clearHistory() = dao.deleteAll()
}
