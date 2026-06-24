package dev.brahmkshatriya.echo.download.db.models

import dev.brahmkshatriya.echo.ui.common.ExceptionUtils
import dev.brahmkshatriya.echo.utils.Serializer.toData
import java.io.File

data class DownloadSummary(
    val id: Long,
    val extensionId: String,
    val trackId: String,
    val contextId: Long?,
    val exceptionFile: String? = null,
    val finalFile: String? = null,
    val fullyDownloaded: Boolean = false,
) {
    val exception by lazy {
        runCatching {
            exceptionFile?.let { File(it) }?.readText()?.toData<ExceptionUtils.Data>()?.getOrThrow()
        }.getOrNull()
    }
    val isFinal by lazy { finalFile != null || exceptionFile != null }
}
