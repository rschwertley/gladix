package dev.brahmkshatriya.echo.history.db

import android.app.Application
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

@Database(
    entities = [HistoryEntity::class],
    version = 4,
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE HistoryEntity ADD COLUMN contextData TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""
                    CREATE TABLE HistoryEntity_new (
                        trackId TEXT NOT NULL,
                        extensionId TEXT NOT NULL,
                        playedAt INTEGER NOT NULL,
                        trackData TEXT NOT NULL,
                        contextData TEXT,
                        PRIMARY KEY (trackId, extensionId)
                    )
                """.trimIndent())
                connection.execSQL("""
                    INSERT OR IGNORE INTO HistoryEntity_new
                    SELECT trackId, extensionId, playedAt, trackData, contextData FROM HistoryEntity
                """.trimIndent())
                connection.execSQL("DROP TABLE HistoryEntity")
                connection.execSQL("ALTER TABLE HistoryEntity_new RENAME TO HistoryEntity")
            }
        }

        fun create(app: Application) = Room.databaseBuilder(
            app, HistoryDatabase::class.java, "history-db"
        ).addMigrations(MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration(true).build()
    }
}
