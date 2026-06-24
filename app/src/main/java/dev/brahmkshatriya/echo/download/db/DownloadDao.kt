package dev.brahmkshatriya.echo.download.db

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.REPLACE
import androidx.room3.Query
import dev.brahmkshatriya.echo.download.db.models.ContextEntity
import dev.brahmkshatriya.echo.download.db.models.DownloadEntity
import dev.brahmkshatriya.echo.download.db.models.DownloadSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = REPLACE)
    suspend fun insertContextEntity(context: ContextEntity): Long

    @Insert(onConflict = REPLACE)
    suspend fun insertDownloadEntity(download: DownloadEntity): Long

    @Query("SELECT * FROM DownloadEntity WHERE id = :trackId")
    suspend fun getDownloadEntity(trackId: Long): DownloadEntity?

    @Query("SELECT * FROM ContextEntity WHERE id = :contextId")
    suspend fun getContextEntity(contextId: Long?): ContextEntity?

    @Query(
        "SELECT id, extensionId, trackId, contextId, exceptionFile, finalFile, fullyDownloaded " +
            "FROM DownloadEntity"
    )
    fun getDownloadsFlow(): Flow<List<DownloadSummary>>

    @Query("SELECT data FROM DownloadEntity WHERE id = :id")
    suspend fun getDownloadData(id: Long): String?

    @Query("SELECT * FROM ContextEntity")
    fun getContextFlow(): Flow<List<ContextEntity>>

    @Delete
    fun deleteDownloadEntity(download: DownloadEntity)

    @Query("DELETE FROM DownloadEntity WHERE id = :id")
    fun deleteDownloadEntityById(id: Long)

    @Delete
    fun deleteContextEntity(context: ContextEntity)

    @Query("SELECT * FROM DownloadEntity WHERE contextId = :id")
    fun getDownloadsForContext(id: Long?): List<DownloadEntity>
}