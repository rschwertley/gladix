package dev.brahmkshatriya.echo.history.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Upsert
    suspend fun upsert(entry: HistoryEntity)

    @Query("DELETE FROM HistoryEntity WHERE rowid IN (SELECT rowid FROM HistoryEntity ORDER BY playedAt ASC LIMIT MAX(0, (SELECT COUNT(*) FROM HistoryEntity) - 500))")
    suspend fun trimToLimit()

    @Query("SELECT * FROM HistoryEntity ORDER BY playedAt DESC")
    fun getAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM HistoryEntity ORDER BY playedAt DESC LIMIT 1")
    fun getLatest(): Flow<HistoryEntity?>

    @Query("SELECT * FROM HistoryEntity WHERE extensionId = :extensionId ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getByExtension(extensionId: String, limit: Int): List<HistoryEntity>

    @Query("DELETE FROM HistoryEntity")
    suspend fun deleteAll()

    @Query("DELETE FROM HistoryEntity WHERE trackId = :trackId AND extensionId = :extensionId")
    suspend fun delete(trackId: String, extensionId: String)
}
