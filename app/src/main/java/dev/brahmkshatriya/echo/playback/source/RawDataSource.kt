package dev.brahmkshatriya.echo.playback.source

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.runBlocking
import java.io.InputStream

@OptIn(UnstableApi::class)
class RawDataSource : BaseDataSource(true) {

    class Factory : DataSource.Factory {
        override fun createDataSource() = RawDataSource()
    }

    private var stream: InputStream? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val streamable = dataSpec.customData as Streamable.Source.Raw
        Log.d("GladixPlayback", "RawDataSource.open: invoking InputProvider pos=${dataSpec.position} uri=${dataSpec.uri}")
        val (source, total) = runBlocking {
            streamable.streamProvider!!.provide(dataSpec.position, dataSpec.length)
        }
        Log.d("GladixPlayback", "RawDataSource.open: stream ready total=$total pos=${dataSpec.position}")
        uri = dataSpec.uri
        stream = source
        return total
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return stream!!.read(buffer, offset, length)
    }

    override fun getUri() = uri

    override fun close() {
        stream?.close()
        stream = null
        uri = null
    }
}