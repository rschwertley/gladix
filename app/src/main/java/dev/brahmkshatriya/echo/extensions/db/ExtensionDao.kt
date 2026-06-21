package dev.brahmkshatriya.echo.extensions.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import dev.brahmkshatriya.echo.extensions.db.models.ExtensionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtensionDao {

    @Query("SELECT * FROM ExtensionEntity")
    fun getExtensionFlow(): Flow<List<ExtensionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setExtension(extensionEntity: ExtensionEntity)
}