package dev.brahmkshatriya.echo.utils

import android.content.Context
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import java.io.File

object CacheUtils {

    // durable = true stores under filesDir instead of cacheDir, so Android's storage-pressure cache
    // wipe can't evict the entries. Used only for the Android Auto "auto/" Triple cache, whose loss
    // silently drops queue items; all other caches stay in cacheDir (disposable). The 50 MB LRU cap is
    // enforced identically in either location on every save — which matters more for filesDir, since the
    // OS won't reclaim it for us.
    fun cacheDir(context: Context, folderName: String, durable: Boolean = false) =
        File(if (durable) context.filesDir else context.cacheDir, "context/$folderName")
            .apply { mkdirs() }

    const val CACHE_FOLDER_SIZE = 50 * 1024 * 1024 //50MB

    inline fun <reified T> Context.saveToCache(
        id: String, data: T?, folderName: String = T::class.java.simpleName, durable: Boolean = false
    ) = runCatching {
        val fileName = id.hashCode().toString()
        val cacheDir = cacheDir(this, folderName, durable)
        val file = File(cacheDir, fileName)

        var size = cacheDir.walk().sumOf { it.length().toInt() }
        while (size > CACHE_FOLDER_SIZE) {
            val files = cacheDir.listFiles()
            files?.sortBy { it.lastModified() }
            files?.firstOrNull()?.delete()
            size = cacheDir.walk().sumOf { it.length().toInt() }
        }
        file.writeText(data.toJson())
    }

    inline fun <reified T> Context.getFromCache(
        id: String, folderName: String = T::class.java.simpleName, durable: Boolean = false
    ): T? {
        val fileName = id.hashCode().toString()
        val cacheDir = cacheDir(this, folderName, durable)
        val file = File(cacheDir, fileName)
        return if (file.exists()) runCatching {
            file.readText().toData<T>().getOrThrow()
        }.getOrNull() else null
    }
}
