package dev.brahmkshatriya.echo.download.db

import android.app.Application
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import dev.brahmkshatriya.echo.download.db.models.ContextEntity
import dev.brahmkshatriya.echo.download.db.models.DownloadEntity

@Database(
    entities = [
        ContextEntity::class,
        DownloadEntity::class,
    ],
    version = 8,
    exportSchema = false
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        private const val DATABASE_NAME = "download-db"
        fun create(app: Application) = Room.databaseBuilder(
                app, DownloadDatabase::class.java, DATABASE_NAME
            ).fallbackToDestructiveMigration(true).build()
    }
}