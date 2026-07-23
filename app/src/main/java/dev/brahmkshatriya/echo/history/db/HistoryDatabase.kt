package dev.brahmkshatriya.echo.history.db

import android.app.Application
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson

@Database(
    entities = [HistoryEntity::class],
    version = 5,
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL("ALTER TABLE HistoryEntity ADD COLUMN contextData TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL("""
                    CREATE TABLE HistoryEntity_new (
                        trackId TEXT NOT NULL,
                        extensionId TEXT NOT NULL,
                        playedAt INTEGER NOT NULL,
                        trackData TEXT NOT NULL,
                        contextData TEXT,
                        PRIMARY KEY (trackId, extensionId)
                    )
                """.trimIndent())
                connection.executeSQL("""
                    INSERT OR IGNORE INTO HistoryEntity_new
                    SELECT trackId, extensionId, playedAt, trackData, contextData FROM HistoryEntity
                """.trimIndent())
                connection.executeSQL("DROP TABLE HistoryEntity")
                connection.executeSQL("ALTER TABLE HistoryEntity_new RENAME TO HistoryEntity")
            }
        }

        // Slim every existing row IN PLACE (columns unchanged): parse the fat trackData/contextData JSON,
        // re-serialize the slimmed Track/context (toSlim/toSlimContext). Read ONE row at a time by rowid so
        // a full-table read can never overflow the CursorWindow — the exact 2MB limit getAll was hitting.
        // A row that can't be read or parsed is DELETED (it was already unusable: the UI skips null tracks,
        // and an unreadable row is what crashes getAll), so the migration can never fail the whole DB.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                val rowIds = mutableListOf<Long>()
                connection.prepare("SELECT rowid FROM HistoryEntity").use { stmt ->
                    while (stmt.step()) rowIds.add(stmt.getLong(0))
                }
                for (rowId in rowIds) {
                    try {
                        var trackJson: String? = null
                        var contextJson: String? = null
                        connection.prepare(
                            "SELECT trackData, contextData FROM HistoryEntity WHERE rowid = ?"
                        ).use { stmt ->
                            stmt.bindLong(1, rowId)
                            if (stmt.step()) {
                                trackJson = stmt.getText(0)
                                contextJson = if (stmt.isNull(1)) null else stmt.getText(1)
                            }
                        }
                        val track = trackJson?.toData<Track>()?.getOrNull()
                        if (track == null) {
                            deleteRow(connection, rowId)
                            continue
                        }
                        val slimTrack = track.toSlim().toJson()
                        val slimContext = contextJson?.toData<EchoMediaItem>()?.getOrNull()
                            ?.toSlimContext()?.toJson()
                        connection.prepare(
                            "UPDATE HistoryEntity SET trackData = ?, contextData = ? WHERE rowid = ?"
                        ).use { stmt ->
                            stmt.bindText(1, slimTrack)
                            if (slimContext != null) stmt.bindText(2, slimContext) else stmt.bindNull(2)
                            stmt.bindLong(3, rowId)
                            stmt.step()
                        }
                    } catch (_: Throwable) {
                        // Read/parse/update failure (e.g. a single >2MB row) → delete so the table is readable.
                        runCatching { deleteRow(connection, rowId) }
                    }
                }
                // Apply the new 150-row cap to pre-existing tables (recordTrack keeps it capped going forward).
                connection.executeSQL(
                    "DELETE FROM HistoryEntity WHERE rowid IN (SELECT rowid FROM HistoryEntity " +
                        "ORDER BY playedAt ASC LIMIT MAX(0, (SELECT COUNT(*) FROM HistoryEntity) - 150))"
                )
            }

            private fun deleteRow(connection: SQLiteConnection, rowId: Long) {
                connection.prepare("DELETE FROM HistoryEntity WHERE rowid = ?").use { stmt ->
                    stmt.bindLong(1, rowId)
                    stmt.step()
                }
            }
        }

        fun create(app: Application) = Room.databaseBuilder(
            app, HistoryDatabase::class.java, "history-db"
        ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigration(true).build()
    }
}
