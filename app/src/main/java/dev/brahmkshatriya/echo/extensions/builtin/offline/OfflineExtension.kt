package dev.brahmkshatriya.echo.extensions.builtin.offline

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditorListenerClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.SettingsChangeListenerClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingMultipleChoice
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getSettings
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.addSongToPlaylist
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.createPlaylist
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.deletePlaylist
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.editPlaylist
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.moveSongInPlaylist
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.removeSongFromPlaylist
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.searchBy
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@OptIn(UnstableApi::class)
/**
 * ⚠⚠ NOTHING THIS EXTENSION PRODUCES IS SHAREABLE, AS A CLASS - AND THE STATEMENT OF THAT IS
 * THE ABSENCE OF ShareClient FROM THE LIST BELOW. Do not add it, and do not "fix" sharing by setting
 * isShareable = false on individual items: the constructions are spread across Convertors.toAlbum /
 * toArtist / toPlaylist, MediaStoreUtils' Track and Artist builders and the stub at MediaStoreUtils,
 * plus radio() below - SIX-PLUS SITES WITH NO CHOKEPOINT. Flagging one would imply the others are
 * deliberate when they are merely untouched, and a seventh would arrive unflagged.
 *
 * WHY IT IS A CLASS PROPERTY RATHER THAN A PER-ITEM ONE: these are LOCAL FILES ON ONE DEVICE. A
 * recipient cannot resolve a track, an album, a playlist or an artist from here - not just a radio.
 * There is no id we could emit that means anything on another install.
 *
 * ⚠️ THE EVIDENCE, AND radio() IS THE SHARPEST CASE: its id is `radio_${item.hashCode()}` - a
 * JVM hash, process-local, with the real payload in extras that any share format would discard.
 * THAT ID HAS ALREADY BEEN CHANGED ONCE, DELIBERATELY: the August 2026 Track.hashCode fix recorded
 * "a Track seed's generated radio-id string switches from value-based to id-based (COSMETIC, NOT A
 * BREAK)" - judged safe precisely because NOTHING COULD DEPEND ON ITS STABILITY. A shared link would
 * have been exactly such a dependency, and would have broken silently at that commit.
 * ⚠️ TWO FOR TWO ON RADIOS, each for its own reason: Deezer's carry another entity's id
 * (an artist's, or a randomly chosen seed track's - see DeezerExtension.loadRadio); this one carries
 * a derived string we have already reserved the right to change. Neither is a promise anyone can
 * share. That is what makes the unshareability structural rather than incidental.
 */
class OfflineExtension(
    private val context: Context,
) : ExtensionClient, HomeFeedClient, TrackClient, AlbumClient, ArtistClient, PlaylistClient,
    RadioClient, LibraryFeedClient, LikeClient, PlaylistEditorListenerClient,
    SearchFeedClient, SettingsChangeListenerClient {

    companion object {
        val metadata = Metadata(
            className = "OfflineExtension",
            path = "",
            importType = ImportType.BuiltIn,
            type = ExtensionType.MUSIC,
            id = "echo-offline",
            name = "Offline",
            description = "An extension for all your downloaded files.",
            version = "v${BuildConfig.VERSION_CODE}",
            author = "Gladix",
            icon = R.drawable.ic_offline.toResourceImageHolder(),
        )
    }

    override suspend fun onSettingsChanged(settings: Settings, key: String?) {
        refreshLibrary()
    }

    override suspend fun getSettingItems(): List<Setting> {
        val folders = getLibrary().folders
        return listOf(
            SettingSwitch(
                context.getString(R.string.refresh_library_on_reload),
                "refresh_library",
                context.getString(R.string.refresh_library_on_reload_summary),
                false
            ),
            SettingSlider(
                context.getString(R.string.duration_filter),
                "limit_value",
                context.getString(R.string.duration_filter_summary),
                10,
                0,
                120,
                10
            ),
            SettingMultipleChoice(
                context.getString(R.string.blacklist_folders),
                "blacklist_folders",
                context.getString(R.string.blacklist_folders_summary),
                folders.toList(),
                folders.toList()
            ),
            SettingTextInput(
                context.getString(R.string.blacklist_folder_keywords),
                "blacklist_keywords",
                context.getString(R.string.blacklist_folder_keywords_summary)
            ),
            // user can supply a pipe‑separated list of artist names that should not be
            // split even if they contain commas or ampersands (or pipes).  Example:
            // "Paola & Chiara | Earth, Wind & Fire".
            SettingTextInput(
                context.getString(R.string.artist_exclusions),
                "artist_exclusions",
                context.getString(R.string.artist_exclusions_summary)
            )
        )
    }

    private val settings = getSettings(context, metadata)
    override fun setSettings(settings: Settings) {}
    private val refreshLibrary
        get() = settings.getBoolean("refresh_library") ?: true

    private val mutex = Mutex()
    private var _library: MediaStoreUtils.LibraryStoreClass? = null
    private suspend fun getLibrary() = mutex.withLock {
        if (_library == null) _library = MediaStoreUtils.getAllSongs(context, settings)
        _library!!
    }

    private suspend fun refreshLibrary() {
        _library = MediaStoreUtils.getAllSongs(context, settings)
    }

    private suspend fun find(artist: Artist) =
        getLibrary().artistMap[artist.id.toLongOrNull()]

    private suspend fun find(album: Album) =
        getLibrary().albumList.find { it.id == album.id.toLong() }

    private suspend fun find(playlist: Playlist) =
        getLibrary().playlistList.find { it.id == playlist.id.toLong() }

    // String compare, not toLong(): Track.id is the MediaStore _id rendered as a String at
    // MediaStoreUtils' Track construction (`id = id.toString()`), and a persisted queue round-trips
    // that String. Matching on it avoids a parse that could silently miss on anything non-numeric.
    private suspend fun find(track: Track) =
        getLibrary().songList.find { it.id == track.id }

    // The MediaStore ids resolved by find() can come from PERSISTED items (restored queue, history, saved
    // playlists) captured before a library re-scan, so a lookup can legitimately miss (the album/artist/
    // playlist was removed or re-indexed). UI load paths surface that miss as this readable exception instead
    // of a bare NPE from find()!!; the radio path (loadTracks(radio)) degrades instead, resolving tracks
    // null-safely via find(). Signatures are non-null interface overrides, so a miss must throw, not return null.
    private fun notInLibrary(type: String, id: String): Nothing = throw IllegalStateException(
        "This $type is no longer in the device's music library (id: $id) — it may have been removed, or the " +
            "library re-scanned, since it was saved."
    )

    private fun List<EchoMediaItem>.toShelves(buttons: Feed.Buttons? = null): Feed<Shelf> =
        map { it.toShelf() }.toFeed(buttons)

    override suspend fun loadHomeFeed() = Feed(listOf()) {
        if (refreshLibrary) refreshLibrary()
        val library = getLibrary()
        val recentlyAdded = library.songList.sortedByDescending {
            it.extras["addDate"]?.toLongOrNull()
        }.map { it }
        val albums = library.albumList.map {
            it.toAlbum()
        }.shuffled()
        val artists = library.artistMap.values.map {
            it.toArtist()
        }.shuffled()

        val recent = if (recentlyAdded.isNotEmpty()) Shelf.Lists.Tracks(
            "recents",
            context.getString(R.string.recently_added),
            recentlyAdded.take(9),
            more = recentlyAdded.toShelves().takeIf { recentlyAdded.size > 9 }
        ) else null

        val albumShelf = if (albums.isNotEmpty()) Shelf.Lists.Items(
            "albums",
            context.getString(R.string.albums),
            albums.take(10),
            more = albums.toShelves().takeIf { albums.size > 10 }
        ) else null

        val artistsShelf = if (artists.isNotEmpty()) Shelf.Lists.Items(
            "artists",
            context.getString(R.string.artists),
            artists.take(10),
            more = artists.toShelves().takeIf { artists.size > 10 }
        ) else null

        val data = PagedData.Single {
            listOfNotNull(recent, albumShelf, artistsShelf) + library.songList.map { it.toShelf() }
        }
        data.toFeedData(
            Feed.Buttons(
                showSearch = false, showSort = true, showPlayAndShuffle = true
            )
        )
    }

    /**
     * ⚠⚠ RE-RESOLVES FROM MEDIASTORE BECAUSE OF A CONTRACT EVERY OTHER EXTENSION ALREADY
     * HONOURS, AND THIS ONE DID NOT. THE BODY WAS `= track`, THE IDENTITY FUNCTION.
     *
     * THE CONTRACT: a persisted queue stores `Track.toSlim()`, which sets `streamables = emptyList()`.
     * loadTrack is where an extension puts them back. Deezer does it by rebuilding streamables from
     * `track.extras["TRACK_TOKEN"]`; every network extension does it implicitly by re-fetching. This
     * one returned its input unchanged, so a slim track stayed slim.
     *
     * ⚠️ DO NOT "FIX" THIS AT toSlim BY PUTTING streamables BACK - SEE THE NOTE THERE. The
     * dropping is DELIBERATE and load-bearing: it was the July 2026 fix for a CursorWindow crash at
     * row 53, and the same slimming fixed a cold-start crash for three users. Restoring the bulk
     * reintroduces two crashes to repair one extension.
     *
     * WHAT IT LOOKED LIKE: `TrackUnavailableException` from StreamableLoader.loadServer on a cold
     * start restoring an offline queue (echo-offline, 19 items, ~4MB heap - a process that had done
     * nothing else). Chain: slim track -> Cached.loadMedia(preferCache = true) MISSES (fresh process,
     * empty media cache) -> falls through to this function -> slim track returned -> `servers` empty
     * -> selectServerIndex returns -1 -> `servers.getOrNull(-1)` is null -> throw. The cache hit is
     * why it is intermittent rather than constant.
     *
     * ⚠️ `?: track` RATHER THAN `?: notInLibrary("track", ...)`, DELIBERATELY. The other find()
     * callers throw on a miss, and doing that here would ALSO convert every dangling MediaStore id
     * (file moved, deleted, SD card unmounted) from its current failure into a readable throw. That
     * may well be the better behaviour, but IT IS A SEPARATE DECISION AND MUST NOT ARRIVE AS A SIDE
     * EFFECT OF THIS ONE - it is parked, deliberately, not overlooked. Falling back to the input
     * leaves all four cases where they are today except the one being fixed:
     *   slim + in library   -> streamables restored (THE FIX)
     *   slim + dangling id  -> input returned; surfaces as "no playable source" at loadServer
     *   full + in library   -> unchanged
     *   full + dangling id  -> input returned; still fails at FileDataSource open, exactly as today
     * Note this function does NOT stat the file and never has, so a missing file cannot be detected
     * here anyway - only a missing LIBRARY ENTRY can.
     *
     * ⚠⚠ WHY REPOPULATING streamables HERE IS SUFFICIENT AND NOT INERT - the non-obvious half,
     * and unreadable from this function alone. MediaItemUtils.build bakes
     * `serverIndex = selectServerIndex(...)` into the item at RESTORE time, and for a slim track that
     * returns -1 (PlayerService.selectServerIndex: empty streamables and no downloads -> -1; it returns
     * a VALUE, it does not throw, which is also why a slim track builds fine and the restore count does
     * not drop it). If that -1 stuck, putting streamables back here would change nothing and loadServer
     * would still do `getOrNull(-1)`.
     * IT DOES NOT STICK: `toMetaData`'s `serverIndex` is a PARAMETER defaulting to null, NOT a read of
     * the existing bundle, and MediaItemUtils.buildLoaded calls it without passing one - so the index is
     * RECOMPUTED against the streamables this function just restored. StreamableLoader.load then calls
     * loadServer on that rebuilt item, not the original. Check this before assuming any other
     * loadTrack-side repopulation takes effect.
     *
     * ⚠⚠ THE runCatching IS LOAD-BEARING AND IS NOT DEFENSIVE PADDING - `?: track` ALONE DOES NOT
     * DELIVER THE "ZERO BEHAVIOUR CHANGE" THIS FIX WAS ARGUED ON. `?: track` catches a MISS. It never
     * catches a FAILURE, and those are different things here: getLibrary() does not return null when the
     * scan fails, it PROPAGATES getAllSongs' throw - and `_library` stays null, so every subsequent call
     * retries the failing scan rather than settling. The old body was `= track`, the identity function,
     * which CANNOT throw; without this wrap the fix would convert a library-load failure into a playback
     * error on the very path it exists to repair (the cold restore - a UI-initiated play is safe, since
     * the user seeing the track means _library is already populated).
     * ⚠️ AND THAT FAILURE IS OBSERVED, NOT HYPOTHETICAL. July 2026: MediaStoreUtils.createPlaylist's
     * "Failed to build unique file" for the Liked playlist ABORTED THE ENTIRE OFFLINE LIBRARY LOAD. That
     * site was hardened with its own runCatching, but it is the SHAPE that matters here - a single
     * MediaProvider fault anywhere in getAllSongs takes the whole scan down, and this function is now
     * downstream of it.
     *
     * ⚠⚠ KNOWN CONSEQUENCE, ACCEPTED RATHER THAN OPEN: THIS MOVED A ONE-TIME MEDIASTORE SCAN ONTO
     * THE PLAYBACK PATH. loadTrack previously touched nothing. getLibrary() memoizes behind a Mutex, so
     * there is NO per-track scan and the songList lookup is a microsecond-scale linear walk (no song map
     * exists; artistMap is keyed but songs are not - correctly not worth indexing). But on a COLD
     * HEADLESS RESTORE of an Offline queue - resume with no UI opened, Android Auto - the FIRST track's
     * loadTrack is now what pays for the full getAllSongs scan, which walks every audio row and does a
     * contentResolver.openInputStream(uri)!!.close() album-art probe per entry. Previously that cost was
     * paid when the Library tab was opened.
     * ⚠️ IT LANDS INSIDE THE BUFFERING WINDOW, WHERE TWO TIMERS ARE ARMED: PlayerEventListener's 5s
     * BUFFERING_WATCHDOG_MS (suppressed only while activeLoadCount > 0 AND within RESOLVE_GRACE_MS =
     * 25_000) and media3's 60s stuck-buffering detector.
     * ⚠️ UNMEASURED - no timing has been taken on a large library, so this is a named risk, not a
     * known defect. IF A COLD-START OFFLINE STALL IS EVER REPORTED, THIS IS THE FIRST CANDIDATE, and the
     * alternative is PRE-WARMING the library at service start so the scan happens outside the buffering
     * window. Written down because that stall would otherwise have to be re-derived from scratch, and a
     * whole session has already gone into one stall nobody could explain.
     */
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track =
        runCatching { find(track) }.getOrNull() ?: track

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean) =
        Uri.fromFile(File(streamable.id)).toString().toSource(isLive = false).toMedia()

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    override suspend fun loadAlbum(album: Album) =
        (find(album) ?: notInLibrary("album", album.id)).toAlbum()

    override suspend fun loadTracks(album: Album): Feed<Track> = PagedData.Single {
        (find(album) ?: notInLibrary("album", album.id)).songList
            .sortedBy { it.extras["trackNumber"]?.toLongOrNull() }.map { it }
    }.toFeed()

    override suspend fun loadFeed(album: Album) =
        getArtistsWithCategories(album.artists) { it.album?.id != album.id }

    private suspend fun getArtistsWithCategories(
        artists: List<Artist>, filter: (Track) -> Boolean,
    ) = artists.map { small ->
        val artist = find(small)
        val category = artist?.songList?.filter {
            filter(it)
        }?.map { it }?.ifEmpty { null }?.let { tracks ->
            Shelf.Lists.Items(
                small.id,
                context.getString(R.string.more_by_x, small.name),
                tracks,
                more = tracks.toShelves()
            )
        }
        listOfNotNull(artist.toArtist().toShelf(), category)
    }.flatten().toFeed()

    override suspend fun loadArtist(artist: Artist) =
        (find(artist) ?: notInLibrary("artist", artist.id)).toArtist()

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        return (find(artist) ?: notInLibrary("artist", artist.id)).run {
            val tracks = songList.ifEmpty { null }?.toList()
            val albums = albumList.map { it.toAlbum() }.ifEmpty { null }
            listOfNotNull(
                tracks?.let {
                    val id = "${artist.id}_tracks"
                    listOf(
                        Shelf.Lists.Items(
                            id,
                            context.getString(R.string.songs) + " (${it.size})",
                            listOf(),
                            more = tracks.toShelves().takeIf { tracks.size >= 10 }
                        )
                    )
                },
                tracks?.take(10)?.map { it.toShelf() },
                albums?.let {
                    val id = "${artist.id}_albums"
                    listOf(
                        Shelf.Lists.Items(
                            id,
                            context.getString(R.string.albums) + " (${it.size})",
                            it,
                            more = albums.toShelves().takeIf { albums.size >= 4 }
                        )
                    )
                }
            ).flatten().toFeed(Feed.Buttons(showPlayAndShuffle = true, customTrackList = tracks))
        }
    }

    override suspend fun loadPlaylist(playlist: Playlist) =
        if (playlist.id == "cached") playlist
        else (find(playlist) ?: notInLibrary("playlist", playlist.id)).toPlaylist()

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = PagedData.Single {
        (find(playlist) ?: notInLibrary("playlist", playlist.id)).songList.map { it }
    }.toFeed()

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    override suspend fun loadRadio(radio: Radio) = radio

    override suspend fun loadTracks(radio: Radio): Feed<Track> = PagedData.Single {
        val mediaItem = requireNotNull(radio.extras["mediaItem"]) {
            "Offline radio is missing its mediaItem extra (id: ${radio.id})"
        }.toData<EchoMediaItem>().getOrThrow()
        val library = getLibrary()
        when (mediaItem) {
            is Album -> {
                val tracks = loadTracks(mediaItem).loadAll().asSequence()
                    .map { it.artists }.flatten().map { artist ->
                        library.artistMap[artist.id.toLongOrNull()]?.songList?.map { it } ?: emptyList()
                    }.flatten().filter { it.album?.id != mediaItem.id }.take(25)

                val randomTracks = library.songList.shuffled().take(25).map { it }
                (tracks + randomTracks).distinctBy { it.id }.toMutableList()
            }

            is Playlist -> {
                val tracks = loadTracks(mediaItem).loadAll()
                val randomTracks = getLibrary().songList.shuffled().take(25).map { it }
                (tracks + randomTracks).distinctBy { it.id }.toMutableList()
            }

            is Artist -> {
                val tracks = find(mediaItem)?.songList?.map { it } ?: emptyList()
                val randomTracks = getLibrary().songList.shuffled().take(25).map { it }
                (tracks + randomTracks).distinctBy { it.id }.toMutableList()
            }

            is Track -> {
                // Resolve the seed track's album tracks null-safely (mirrors the artist branch below). A
                // persisted track can carry an album id that no longer resolves after a library re-scan; the
                // radio must degrade to artist+random rather than fail. Was loadTracks(loadAlbum(it)), whose
                // find(album)!! NPE'd on such a dangling id — the reported crash. null here is dropped by the
                // listOfNotNull below.
                val albumTracks = mediaItem.album?.let { album ->
                    find(album)?.songList?.sortedBy { it.extras["trackNumber"]?.toLongOrNull() }
                }
                val artistTracks = mediaItem.artists.map { artist ->
                    find(artist)?.songList ?: emptyList()
                }.flatten().map { it }
                val randomTracks = getLibrary().songList.shuffled().take(25).map { it }
                val allTracks =
                    listOfNotNull(albumTracks, artistTracks, randomTracks).flatten()
                        .distinctBy { it.id }
                        .toMutableList()
                allTracks.removeIf { it.id == mediaItem.id }
                allTracks
            }

            else -> throw IllegalAccessException()
        }.shuffled()
    }.toFeed()

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        val id = "radio_${item.hashCode()}"
        val title = item.title
        return Radio(
            id = id,
            title = this.context.getString(R.string.x_radio, title),
            extras = mapOf("mediaItem" to item.toJson())
        )
    }

    private fun List<EchoMediaItem>.sorted() =
        sortedBy { it.title.lowercase() }.map { it.toShelf() }

    private fun List<Shelf>.toPair(buttons: Feed.Buttons? = null) =
        PagedData.Single { this }.toFeedData(buttons)

    private fun Pair<List<Shelf>, Boolean>.toFeed() =
        first.toPair(if (second) Feed.Buttons(false, showSort = true) else null)

    override suspend fun loadSearchFeed(query: String) = Feed(
        (if (query.isBlank()) listOf("Songs", "Albums", "Artists", "Genres")
        else listOf("All", "Songs", "Albums", "Artists")).map { Tab(it, it) }
    ) { tab ->
        if (query.isBlank()) {
            if (refreshLibrary) refreshLibrary()
            when (tab?.id) {
                "Albums" -> getLibrary().albumList.map { it.toAlbum() }.sorted().toPair(
                    Feed.Buttons(showSearch = false, showSort = true)
                )

                "Artists" -> getLibrary().artistMap.values.map { it.toArtist() }.sorted().toPair(
                    Feed.Buttons(showSearch = false, showSort = true)
                )

                "Genres" -> getLibrary().genreList.map { it.toShelf() }.toPair()
                else -> getLibrary().songList.sortedByDescending {
                    it.extras["addDate"]?.toLongOrNull()
                }.map { it.toShelf() }.toPair(
                    Feed.Buttons(
                        showSearch = false, showSort = true, showPlayAndShuffle = true
                    )
                )
            }
        } else {
            val tracks = getLibrary().songList.map { it }.searchBy(query) {
                listOf(it.title, it.album?.title) + it.artists.map { artist -> artist.name }
            }
            val albums = getLibrary().albumList.map { it.toAlbum() }.searchBy(query) {
                listOf(it.title) + it.artists.map { artist -> artist.name }
            }
            val artists = getLibrary().artistMap.values.map { it.toArtist() }.searchBy(query) {
                listOf(it.name)
            }

            when (tab?.id) {
                "Songs" -> tracks.map { it.second.toShelf() } to true
                "Albums" -> albums.map { it.second.toShelf() } to true
                "Artists" -> artists.map { it.second.toShelf() } to true
                else -> {
                    val items = listOf(
                        "Songs" to tracks, "Albums" to albums, "Artist" to artists
                    ).sortedBy { it.second.firstOrNull()?.first ?: 20 }
                        .map { it.first to it.second.map { pair -> pair.second } }
                        .filter { it.second.isNotEmpty() }

                    val exactMatch = items.firstNotNullOfOrNull {
                        it.second.find { item -> item.title.contains(query, true) }
                    }?.toShelf()

                    val containers = items.map { (title, items) ->
                        val id = "${query}_$title"
                        Shelf.Lists.Items(id, title, items, more = items.toShelves())
                    }

                    listOf(listOfNotNull(exactMatch), containers).flatten() to false
                }
            }.toFeed()
        }
    }

    override suspend fun loadLibraryFeed() = Feed(
        listOf("Playlists", "Folders").map { Tab(it, it) }
    ) { tab ->
        if (refreshLibrary) refreshLibrary()
        val pagedData: PagedData<Shelf> = when (tab?.id) {
            "Folders" -> getLibrary().folderStructure.folderList.entries.firstOrNull()?.value
                ?.toShelf(context, null)?.feed?.getPagedData?.invoke(null)?.pagedData
                ?: PagedData.Single { listOf() }

            else -> PagedData.Single {
                getLibrary().playlistList.map { it.toPlaylist().toShelf() }
            }
        }
        pagedData.toFeedData(Feed.Buttons())
    }

    override suspend fun listEditablePlaylists(track: Track?) = getLibrary().playlistList.map {
        val has = it.songList.any { song -> song.id == track?.id }
        it.toPlaylist() to has
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        val library = getLibrary()
        val playlist = library.likedPlaylist?.id
            ?: throw ClientException.NotSupported("Couldn't create Liked Playlist")
        if (shouldLike) context.addSongToPlaylist(playlist, item.id.toLong(), 0)
        else {
            val index = library.likedPlaylist.songList.indexOfFirst { it.id == item.id }
            context.removeSongFromPlaylist(playlist, index)
        }
        refreshLibrary()
    }

    override suspend fun isItemLiked(item: EchoMediaItem) =
        getLibrary().likedPlaylist?.songList?.find { it.id == item.id } != null

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        val id = context.createPlaylist(title)
        refreshLibrary()
        return getLibrary().playlistList.find { it.id == id }!!.toPlaylist()
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        context.deletePlaylist(playlist.id.toLong())
        refreshLibrary()
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist, title: String, description: String?,
    ) {
        context.editPlaylist(playlist.id.toLong(), title)
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>,
    ) {
        new.forEach {
            context.addSongToPlaylist(playlist.id.toLong(), it.id.toLong(), index)
        }
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>,
    ) {
        indexes.forEach { index ->
            context.removeSongFromPlaylist(playlist.id.toLong(), index)
        }
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int,
    ) {
        val song = tracks[fromIndex].id.toLong()
        context.moveSongInPlaylist(playlist.id.toLong(), song, fromIndex, toIndex)
    }

    override suspend fun onEnterPlaylistEditor(playlist: Playlist, tracks: List<Track>) {}
    override suspend fun onExitPlaylistEditor(playlist: Playlist, tracks: List<Track>) {
        refreshLibrary()
    }
}