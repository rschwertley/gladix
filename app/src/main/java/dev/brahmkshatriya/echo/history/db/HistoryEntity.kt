package dev.brahmkshatriya.echo.history.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.utils.Serializer.toData

@Entity(primaryKeys = ["trackId", "extensionId"])
data class HistoryEntity(
    val trackId: String,
    val extensionId: String,
    val playedAt: Long,
    val trackData: String,
    val contextData: String? = null,
) {
    val track by lazy { trackData.toData<Track>().getOrNull() }
    val context by lazy { contextData?.toData<EchoMediaItem>()?.getOrNull() }
}
