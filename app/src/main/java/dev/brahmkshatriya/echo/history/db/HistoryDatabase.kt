package dev.brahmkshatriya.echo.history.db

import android.app.Application
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HistoryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE HistoryEntity ADD COLUMN contextData TEXT")
            }
        }

        fun create(app: Application) = Room.databaseBuilder(
            app, HistoryDatabase::class.java, "history-db"
        ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration(true).build()
    }
}
