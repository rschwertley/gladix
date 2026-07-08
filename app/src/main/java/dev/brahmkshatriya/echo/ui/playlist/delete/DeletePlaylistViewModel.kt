package dev.brahmkshatriya.echo.ui.playlist.delete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DeletePlaylistViewModel(
    extensionLoader: ExtensionLoader,
    private val app: App,
    private val extensionId: String,
    private val item: Playlist,
    private val loaded: Boolean,
) : ViewModel() {

    val extensionFlow = extensionLoader.music.map { list ->
        list.find { it.id == extensionId }
    }.stateIn(viewModelScope, Eagerly, null)

    val playlistFlow = extensionFlow.transformLatest {
        emit(null)
        if (it == null) return@transformLatest
        if (loaded) emit(Result.success(item))
        else emit(it.getAs<PlaylistEditClient, Playlist> { loadPlaylist(item) })
    }.stateIn(viewModelScope, Eagerly, null)

    private val deleteFlow = MutableSharedFlow<Unit>()
    fun delete() = viewModelScope.launch { deleteFlow.emit(Unit) }

    val deleteStateFlow = deleteFlow.transformLatest {
        emit(DeleteState.Deleting)
        val result = runCatching {
            val extension = extensionFlow.value!!
            val playlist = playlistFlow.value!!.getOrThrow()
            // Any? (not Unit): an extension whose deletePlaylist drifted to return a value would crash
            // with "X cannot be cast to Unit". .map { } drops the Any? result to Unit so the block stays
            // typed Result<Unit> for DeleteState.Deleted; the call still runs and throws on failure.
            extension.getAs<PlaylistEditClient, Any?> { deletePlaylist(playlist) }.map { }.getOrThrow()
        }
        result.getOrElse {
            if (it is CancellationException) throw it
            // A NotSupported delete (e.g. Spotify's non-deletable "Liked Songs" pseudo-playlist) would
            // otherwise render as the confusing "Unsupported playlist type is not supported in Spotify".
            // Show a clear, extension-agnostic message instead. messageFlow surfaces globally (not tied
            // to this sheet), so it's seen even though the sheet dismisses on the failed Deleted state.
            if (it is AppException.NotSupported)
                app.messageFlow.emit(Message(app.context.getString(R.string.playlist_cant_be_deleted)))
            else app.throwFlow.emit(it)
        }
        emit(DeleteState.Deleted(result))
    }.stateIn(viewModelScope, Eagerly, DeleteState.Initial)

    init {
        viewModelScope.launch {
            playlistFlow.first { it != null }
            delete()
        }
    }
}