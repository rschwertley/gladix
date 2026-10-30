package dev.brahmkshatriya.echo.ui.playlist.edit

import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditCoverClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditorListenerClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceUriImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.cache.Cached.bustPlaylistTracksCache
import dev.brahmkshatriya.echo.extensions.cache.Cached.writePlaylistTracksCache
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getOrThrow
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.InstallationUtils.NoFileSelected
import dev.brahmkshatriya.echo.extensions.InstallationUtils.openFileSelector
import dev.brahmkshatriya.echo.utils.CoroutineUtils.combineTransformLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EditPlaylistViewModel(
    extensionLoader: ExtensionLoader,
    private val app: App,
    private val extensionId: String,
    private val initial: Playlist,
    private val loaded: Boolean,
    private val selectedTab: String?,
    private val removeIndex: Int,
) : ViewModel() {
    val extensionFlow = extensionLoader.music.map { list -> list.find { it.id == extensionId } }
        .stateIn(viewModelScope, Eagerly, null)

    val playlistFlow = extensionFlow.transformLatest { extension ->
        emit(null)
        if (extension == null) return@transformLatest
        emit(extension.getAs<PlaylistClient, Playlist> {
            if (!loaded) loadPlaylist(initial) else initial
        })
    }.stateIn(viewModelScope, Eagerly, null)

    private val feedFlow = playlistFlow.transformLatest { playlist ->
        emit(null)
        val extension = extensionFlow.value ?: return@transformLatest
        if (playlist == null) return@transformLatest
        emit(extension.getAs<PlaylistClient, Feed<Track>> {
            loadTracks(playlist.getOrThrow())
        })
    }

    val selectedTabFlow = MutableStateFlow<Tab?>(null)
    val tabsFlow = feedFlow.map { result ->
        val tabs = result?.getOrNull()?.tabs ?: emptyList()
        selectedTabFlow.value = tabs.find { it.id == selectedTab } ?: tabs.firstOrNull()
        tabs
    }.stateIn(viewModelScope, Eagerly, emptyList())

    val originalList = feedFlow.combineTransformLatest(selectedTabFlow) { result, tab ->
        emit(null)
        if (result == null) return@combineTransformLatest
        val extension = extensionFlow.value ?: return@combineTransformLatest
        val tracks = extension.getAs<PlaylistClient, List<Track>> {
            result.getOrThrow().getPagedData(tab).pagedData.loadAll()
        }
        emit(tracks)
    }.stateIn(viewModelScope, Eagerly, null)

    val currentTracks = MutableStateFlow<List<Track>?>(null)
    // Returns whether the action was applied. Only PlaylistTrackAdapter's swipe reads it, to release a
    // swiped row that nothing removed; every other caller ignores it.
    fun edit(action: Action<Track>): Boolean {
        var applied = false
        currentTracks.value = currentTracks.value?.toMutableList()?.apply {
            runCatching {
                when (action) {
                    is Action.Add -> addAll(action.index, action.items)
                    is Action.Move -> add(action.to, removeAt(action.from))
                    is Action.Remove -> action.indexes.forEach { removeAt(it) }
                }
                applied = true
            }.getOrElse {
                viewModelScope.launch { app.throwFlow.emit(it) }
            }
        }
        return applied
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val newActions = currentTracks.transformLatest { current ->
        emit(null)
        val list = originalList.value?.getOrNull()
        if (list == null || current == null) return@transformLatest
        emit(computeActions(list, current))
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, Eagerly, null)

    data class Data(
        val title: String,
        val desc: String?,
        val coverEditable: Boolean,
        val cover: ImageHolder?,
    )

    val nameFlow = MutableStateFlow(initial.title)
    val descriptionFlow = MutableStateFlow(initial.description)

    sealed interface CoverState {
        data object Initial : CoverState
        data object Removed : CoverState
        data class Changed(val file: File) : CoverState
    }

    val coverFlow = MutableStateFlow<CoverState>(CoverState.Initial)
    val dataFlow = combineTransformLatest(nameFlow, descriptionFlow, coverFlow, extensionFlow) {
        val title = it[0] as String
        val desc = it[1] as String?
        val cover = it[2] as CoverState
        val extension = it[3] as Extension<*>?
        emit(Data(title, desc, false, null))
        val isEditable = extension?.isClient<PlaylistEditCoverClient>() == true
        val image = when (cover) {
            is CoverState.Changed -> cover.file.toUri().toString().toResourceUriImageHolder()
            CoverState.Initial -> initial.cover
            CoverState.Removed -> null
        }
        emit(Data(title, desc, isEditable, image))
    }

    sealed interface SaveState {
        data object Initial : SaveState
        data class Performing(val action: Action<Track>, val tracks: List<Track>) : SaveState
        data object Saving : SaveState
        data class Saved(val result: Result<Unit>) : SaveState
    }

    private val saveFlow = MutableSharedFlow<Unit>()
    val isSaveable = newActions.combine(dataFlow) { actions, pair ->
        val playlist = playlistFlow.value?.getOrNull() ?: return@combine false
        if (playlist.title != pair.title) return@combine true
        if (playlist.description != pair.desc) return@combine true
        if (coverFlow.value != CoverState.Initial) return@combine true
        !actions.isNullOrEmpty()
    }

    val saveState = saveFlow.transformLatest {
        emit(SaveState.Saving)
        val saved = SaveState.Saved(runCatching {
            val playlist = playlistFlow.value!!.getOrThrow()
            val extension = extensionFlow.value!!
            if (playlist.title != nameFlow.value || playlist.description != descriptionFlow.value) {
                extension.getAs<PlaylistEditClient, Any?> {
                    editPlaylistMetadata(playlist, nameFlow.value, descriptionFlow.value)
                }.getOrThrow()
            }
            when (val cover = coverFlow.value) {
                CoverState.Initial -> {}
                CoverState.Removed -> extension.getAs<PlaylistEditCoverClient, Any?> {
                    editPlaylistCover(playlist, null)
                }

                is CoverState.Changed -> extension.getAs<PlaylistEditCoverClient, Any?> {
                    editPlaylistCover(playlist, cover.file)
                }
            }

            // ⚠⚠ COMPUTED HERE, NOT READ FROM THE [newActions] FLOW, BECAUSE A
            // STALE READ OF THAT FLOW MADE "Remove from playlist" A SILENT NO-OP. This was
            // `newActions.value!!`, and that flow is
            // `currentTracks.transformLatest { emit(null); emit(computeActions(...)) }`
            // .flowOn(Dispatchers.IO).stateIn(Eagerly, null) - so its value trails [currentTracks]
            // by one IO dispatch. The one-shot remove path in init edits currentTracks and emits
            // saveFlow with NO suspension point in between, so this line sampled the value computed
            // for the PREVIOUS currentTracks - which, right after originalList populated it, is
            // computeActions(list, list) = AN EMPTY LIST. Empty then took the early return below:
            // SaveState.Saved(success), EditPlaylistBottomSheet saw isSuccess, fired its "reload"
            // fragment result and dismissed, and nothing had been removed. No error, no snackbar,
            // no Crashlytics - it read as a completed operation, which is why build 1116 shipped it.
            // ⚠️ THE EDITOR SCREEN NEVER SHOWED THIS, and that is the tell for
            // why it went unnoticed: there a human swipes, then taps Save, and the seconds between
            // let the IO recomputation land. Only the sheet, which saves programmatically in the
            // same breath as the edit, loses the race - and it loses it every time.
            // ⚠️ THIS IS THE AUTHORITATIVE PAIR: originalList is what was sent
            // to the extension as `tracks`, currentTracks is what the user's edits produced, and
            // computeActions is a pure function of the two. The flow stays for [isSaveable], where
            // being one dispatch behind only delays a button enabling and cannot lose work.
            // ⚠️ STILL OFF THE MAIN THREAD: the flow used flowOn(IO) for this
            // diff and a long playlist is the reason, so the withContext keeps that property.
            val original = originalList.value!!.getOrThrow()
            val newActions = withContext(Dispatchers.IO) {
                computeActions(original, currentTracks.value ?: original)
            }
            if (newActions.isEmpty()) return@runCatching

            // Same value `original` holds - the diff and the list sent to the extension MUST be the
            // pair computeActions was given, or an index in an action would point into a different
            // list than the one being edited.
            var tracks = original
            val selectedTab = selectedTabFlow.value
            extension.getIf<PlaylistEditorListenerClient, Any?> {
                onEnterPlaylistEditor(playlist, tracks)
            }.getOrThrow()

            // Busted BEFORE the actions run, not after: the entry is about to be wrong either way, and
            // clearing it up front means a PARTIALLY applied edit (some actions committed, then a failure)
            // also leaves no stale entry behind. The cost of busting on an edit that then fails entirely is
            // one refetch, which is the correct outcome anyway.
            // The editor's "reload" fragment result already refreshes the page you came from, but only if
            // you are on that playlist's own page - it does nothing for the durable entry when you got here
            // any other way.
            if (newActions.isNotEmpty()) bustPlaylistTracksCache(app, playlist.id)
            extension.getAs<PlaylistEditClient, Any?> {
                newActions.forEach { action ->
                    when (action) {
                        is Action.Add -> {
                            addTracksToPlaylist(playlist, tracks, action.index, action.items)
                            tracks =
                                loadTracks(playlist).run { getPagedData(selectedTab).pagedData }
                                    .loadAll()
                        }

                        is Action.Move -> {
                            moveTrackInPlaylist(playlist, tracks, action.from, action.to)
                            tracks = tracks.toMutableList().apply {
                                add(action.to, removeAt(action.from))
                            }
                        }

                        is Action.Remove -> {
                            removeTracksFromPlaylist(playlist, tracks, action.indexes)
                            tracks =
                                loadTracks(playlist).run { getPagedData(selectedTab).pagedData }
                                    .loadAll()
                        }
                    }
                }
            }.getOrThrow()
            // ⚠⚠ WRITE THE ORDER WE JUST COMMITTED, RATHER THAN ASKING FOR IT BACK.
            // `tracks` is the list the action loop above maintained and sent to the extension, and the
            // getOrThrow() on the line above means the server accepted every action. So this is the
            // authoritative post-edit order, and it is strictly better than a refetch: the editor
            // paints instantly, and a read endpoint that lags the write it just acknowledged cannot
            // put the pre-edit order back on screen. (Reported 2026-10-15: after a reorder, Deezer's
            // own site showed the new order immediately while Gladix showed the old one through
            // repeated refreshes, a force-close and a Clear cache.)
            // ⚠️ PLACED AFTER THE EDIT CLIENT, BEFORE onExitPlaylistEditor, AND THE
            // ORDER MATTERS BOTH WAYS. After the edits: a partially applied edit throws above and
            // never reaches here, so a half-finished list is never cached - the bust at the top of
            // this block is what covers that case, and it stays. Before the listener: the track edits
            // are already committed at this point, so the cache matches the server whatever the
            // listener then does or throws.
            // ⚠️ ONLY `Move` IS PURELY LOCAL. Add and Remove re-read `tracks` from the
            // extension inside the loop, so for those this writes a server-sourced list and the fix
            // is only as current as that read; reorder - the reported case - is fully covered.
            writePlaylistTracksCache(app, playlist, tracks)
            // .map { } drops the Any? result to Unit so the runCatching stays typed Result<Unit> for
            // SaveState.Saved; the call still runs and still throws on failure (no trailing Unit needed).
            extension.getIf<PlaylistEditorListenerClient, Any?> {
                onExitPlaylistEditor(playlist, tracks)
            }.map { }.getOrThrow()
        })
        saved.result.getOrThrow(app.throwFlow)
        emit(saved)
    }.stateIn(viewModelScope, Eagerly, SaveState.Initial)

    fun save() = viewModelScope.launch { saveFlow.emit(Unit) }
    /**
     * ⚠⚠ BACKING OUT OF THE PICKER IS SILENT, AND IT WAS NOT. This used to end
     * in getOrThrow(app.throwFlow), which emits to the flow whose collector shows a snackbar AND
     * files a Crashlytics non-fatal (App's throwFlow collector) - so a user who opened the cover
     * picker and pressed back got "No file selected" on screen and a crash report filed, every
     * time. Same rule the installer work applied to a cancelled uninstall: a cancellation is not
     * a defect.
     * ⚠️ [NoFileSelected] COVERS BOTH CANCEL CASES - a back-out and a result
     * lost to an Activity recreation, which InstallationUtils.waitForResult now delivers as a
     * cancel instead of stranding this coroutine on viewModelScope forever (a ViewModel store
     * survives the recreation, so nothing would ever have resumed it).
     * ⚠️ EVERY OTHER FAILURE STILL REPORTS: an unreadable URI or a copy error
     * throws from getTempFile and reaches throwFlow exactly as before. CancellationException is
     * rethrown rather than swallowed - runCatching captures it, and dropping it here would make
     * a cancelled scope look like a completed one.
     */
    fun changeCover(activity: FragmentActivity) = viewModelScope.launch {
        val file = runCatching { activity.openFileSelector(fileType = "image/*") }.getOrElse {
            if (it is CancellationException) throw it
            if (it !is NoFileSelected) app.throwFlow.emit(it)
            null
        } ?: return@launch
        coverFlow.value = CoverState.Changed(file)
    }

    init {
        viewModelScope.launch {
            originalList.collectLatest {
                currentTracks.value = it?.getOrNull()
            }
        }
        // The one-shot path behind MediaMoreBottomSheet's "Remove from playlist": no editor UI, no
        // Save button - edit once and commit.
        // ⚠⚠ THE `newActions.first { it != null }` THAT USED TO SIT BEFORE THE
        // EMIT IS GONE, AND IT WAS THE BUG, NOT THE GUARD IT LOOKED LIKE. It was there to let the
        // diff catch up with [edit], but a StateFlow's `first` samples the CURRENT value, and that
        // value was the previous diff - an EMPTY list, which satisfies `it != null` instantly. So it
        // waited for nothing, and the save then read that same empty list and no-opped. See the note
        // at [saveState], which now computes the actions itself; nothing here needs to wait for a
        // flow at all, because [edit] updates currentTracks SYNCHRONOUSLY.
        // ⚠️ DO NOT "REPAIR" IT AS `first { !it.isNullOrEmpty() }` INSTEAD.
        // That waits for the right thing and HANGS FOREVER on the one case it must not: if
        // removeIndex is out of range, edit()'s runCatching swallows the IndexOutOfBounds, the list
        // is unchanged, the diff stays empty, and the sheet sits on "Loading" with no way out -
        // the exact failure the removeIndex note in MediaMoreBottomSheet warns about. Emitting
        // unconditionally keeps that case ending in a dismissal.
        viewModelScope.launch {
            if (removeIndex == -1) return@launch
            currentTracks.first { it != null }
            edit(Action.Remove(listOf(removeIndex)))
            saveFlow.emit(Unit)
        }
    }

    sealed interface Action<T> {
        data class Add<T>(val index: Int, val items: MutableList<T>) : Action<T>
        data class Remove<T>(val indexes: List<Int>) : Action<T>
        data class Move<T>(val from: Int, val to: Int) : Action<T>
    }

    companion object {
        // I am KR bbg.
        // Don't forget to subscribe to my OnlyFans at https://github.com/justfoolingaround
        fun <T> computeActions(
            old: List<T>, new: List<T>,
        ): MutableList<Action<T>> {
            val out = mutableListOf<Action<T>>()
            val before = old.toMutableList()

            val afterIds = new.map { it }.toSet()
            val removeIndexes = before.mapIndexedNotNull { index, item ->
                if (item !in afterIds) index else null
            }

            if (removeIndexes.isNotEmpty()) {
                removeIndexes.sortedDescending().forEach { before.removeAt(it) }
                out.add(Action.Remove(indexes = removeIndexes))
            }

            val additions = mutableListOf<T>()
            var addIndex = -1
            new.forEachIndexed { targetIndex, targetItem ->
                if (before.none { it == targetItem }) {
                    if (addIndex == -1) addIndex = targetIndex
                    additions.add(targetItem)
                    before.add(targetIndex, targetItem)
                } else if (additions.isNotEmpty()) {
                    out.add(Action.Add(index = addIndex, items = additions.toMutableList()))
                    additions.clear()
                    addIndex = -1
                }
            }
            if (additions.isNotEmpty()) {
                out.add(Action.Add(index = addIndex, items = additions))
            }

            val moveActions = mutableListOf<Action.Move<T>>()
            new.forEachIndexed { targetIndex, targetItem ->
                val currentIndex = before.indexOfFirst { it == targetItem }
                if (currentIndex != -1 && currentIndex != targetIndex) {
                    moveActions.add(Action.Move(from = currentIndex, to = targetIndex))
                    val item = before.removeAt(currentIndex)
                    before.add(targetIndex, item)
                }
            }

            var ind = -1
            var last: Action.Move<T>? = null
            moveActions.forEach {
                val l = last
                if (l != null) {
                    if (l.from == it.to) {
                        if (ind == -1) ind = l.from - 1
                    } else {
                        if (ind == -1) out.add(l)
                        else {
                            out.add(Action.Move(from = ind, to = l.from))
                            ind = -1
                        }
                    }
                }
                last = it
            }
            val l = last
            if (l != null) {
                if (ind != -1) out.add(Action.Move(from = ind, to = l.from))
                else out.add(l)
            }

            return out
        }
    }
}
