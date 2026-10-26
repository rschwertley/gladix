package dev.brahmkshatriya.echo.playback

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ThumbRating
import androidx.media3.common.util.UnstableApi
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.selectServerIndex
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.text.Charsets.UTF_8

object MediaItemUtils {

    /**
     * Drops tracks the extension has declared unplayable from a list that is about to BECOME a
     * queue. Call it on the slice, never on a single tapped item.
     *
     * ⚠⚠ FILTER BEFORE ANY `take(cap)`, OR THE CAP COUNTS CORPSES. A liked
     * list with 50 dead tracks in one pulled album would otherwise spend 50 of its 2000 queue slots
     * on tracks that cannot play, and a shorter cap would spend proportionally more. Every caller
     * here orders it that way.
     * ⚠⚠ THIS IS A SECOND LINE, NOT THE FIRST OR THE LAST. The verdict only
     * exists where an extension set it (DeezerApi.markUnplayable: Deezer liked, playlist and album
     * rows), and HistoryEntity.resetPlayable strips it from anything persisted - so a restored
     * queue, a radio, a History tap and every other extension arrive here with Playable.Yes and are
     * untouched. The dead-track handling that already exists stays exactly as it is: the silent skip
     * in PlayerEventListener, the catalogue-dead breaker exemption with its health check, and
     * CrossfadeController's decline on a known-unplayable next item. This filter removes the skips
     * we can SEE coming; those still catch the ones we cannot.
     * ⚠️ [keepFirst] IS FOR THE CAR, AND IT IS A DELIBERATE ASYMMETRY. On
     * the phone a tapped row that is marked never reaches a queue at all - the viewholder gates it to
     * FeedClickListener.onUnplayableClicked, which shows the reason and does nothing else. Android
     * Auto has no equivalent affordance, so a tap there must still DO something: the head is kept
     * even when marked (it skips instantly, at zero requests) rather than turning a tap into a
     * silent no-op or quietly playing a different song than the one touched.
     */
    fun List<Track>.droppingUnplayable(keepFirst: Boolean = false): List<Track> =
        filterIndexed { index, track ->
            (keepFirst && index == 0) || track.isPlayable == Track.Playable.Yes
        }

    /**
     * Can this stored context be RE-LOADED and the original track found inside it again?
     *
     * Named for the question because three call sites previously asked it three different ways — each
     * spelled `is EchoMediaItem.Lists` inline — and a fourth would have made the same mistake. Anything
     * that locates a stored track inside a freshly loaded context must ask THIS, not the type.
     *
     * ⚠️ RADIO IS EXCLUDED EVEN THOUGH IT IS AN [EchoMediaItem.Lists] (see Radio's declaration — that
     * supertype is exactly what made this a bug). A radio REGENERATES on every load: the list that comes
     * back is a different station, so the stored track's id can never be found in it and any index lookup
     * against one is guaranteed to miss. Before 2026-09-04 a Radio context fell into the Lists branch at
     * every site, the miss hit an `?: 0` fallback, and History played the new station's FIRST track instead
     * of the one tapped — for two months, silently, because the fallback looked like a sane default.
     *
     * A radio history tap belongs on the seed path instead: play the tapped track, let PlayerRadio append
     * a freshly generated station after it. That path already resolves through MediaState.Unloaded, so
     * routing here replays no stored streamable state and strips no extras.
     *
     * Returning true implies [EchoMediaItem.Lists] via a contract, so existing smart casts at the call
     * sites keep working.
     */
    // Fully qualified: this file imports androidx.annotation.OptIn, which is NOT the annotation needed here.
    @kotlin.OptIn(ExperimentalContracts::class)
    fun EchoMediaItem?.isReplayableContext(): Boolean {
        contract { returns(true) implies (this@isReplayableContext is EchoMediaItem.Lists) }
        return this is EchoMediaItem.Lists && this !is Radio
    }

    // Marker on a seed's context that means "display-only radio label, not a real radio to generate".
    // PlayerRadio strips a context carrying this before calling extension.radio(), so radio GENERATION
    // is unchanged (it still receives null exactly as before) — this exists purely so the now-playing
    // header can read "Playing from <track> Radio" from the first second of a bare-track/Radio-History
    // seed, instead of only once the real auto-radio kicks in on the next track.
    const val LABEL_ONLY_RADIO = "label_only_radio"

    // A stand-in Radio context whose title matches what the real track-radio shows one track later
    // (Deezer's asTrackRadio uses "<title> Radio"). Cover mirrors the seed track. Marked LABEL_ONLY_RADIO
    // so it only ever labels the header and never alters which radio is generated.
    fun trackRadioPlaceholder(track: Track): Radio = Radio(
        id = track.id,
        title = "${track.title} Radio",
        cover = track.cover,
        extras = mapOf(LABEL_ONLY_RADIO to "true"),
    )

    fun build(
        app: App,
        downloads: List<Downloader.Info>,
        state: MediaState.Unloaded<Track>,
        context: EchoMediaItem?,
    ): MediaItem {
        val item = MediaItem.Builder()
        val metadata = state.toMetaData(bundleOf(), downloads, context, false, app)
        item.setMediaMetadata(metadata)
        item.setMediaId(state.item.id)
        item.setUri(state.item.id)
        return item.build()
    }

    fun buildLoaded(
        app: App,
        downloads: List<Downloader.Info>,
        mediaItem: MediaItem,
        state: MediaState.Loaded<Track>,
    ): MediaItem = with(mediaItem) {
        val item = buildUpon()
        val metadata = state.toMetaData(
            mediaMetadata.extras!!, downloads, context, true, app
        )
        item.setMediaMetadata(metadata)
        return item.build()
    }

    fun buildServer(mediaItem: MediaItem, index: Int): MediaItem = with(mediaItem) {
        val bundle = Bundle().apply {
            putAll(mediaMetadata.extras!!)
            putInt("serverIndex", index)
            putInt("retries", 0)
        }
        buildWithBundle(this, bundle)
    }

    fun buildSource(mediaItem: MediaItem, index: Int) = with(mediaItem) {
        val bundle = Bundle().apply {
            putAll(mediaMetadata.extras!!)
            putInt("sourceIndex", index)
            putInt("retries", 0)
        }
        buildWithBundle(this, bundle)
    }

    fun buildBackground(mediaItem: MediaItem, index: Int): MediaItem = with(mediaItem) {
        val bundle = Bundle().apply {
            putAll(mediaMetadata.extras!!)
            putInt("backgroundIndex", index)
        }
        buildWithBundle(this, bundle)
    }

    fun buildSubtitle(mediaItem: MediaItem, index: Int): MediaItem = with(mediaItem) {
        val bundle = Bundle().apply {
            putAll(mediaMetadata.extras!!)
            putInt("subtitleIndex", index)
        }
        buildWithBundle(this, bundle)
    }


    // ⚠⚠ [PARKED 2026-10-15] THIS RETRY CANNOT HEAL A STALE CACHED TRACK, AND THAT IS
    // THE ONE THING IT LOOKS LIKE IT SHOULD DO. loaded=false makes
    // StreamableMediaSource.prepareSourceInternal re-enter StreamableLoader.load FROM THE TOP, so the
    // whole resolve runs again - but StreamableLoader.loadTrack calls Cached.loadMedia with
    // preferCache = true, which serves the SAME cached MediaState.Loaded byte for byte. So the retry
    // re-resolves the STREAM and never the TRACK. A Track cached by an older extension version keeps
    // being handed back, every pass fails identically, maxSingleItemRetries is burned and the item is
    // skipped. Driven from PlayerEventListener.onPlayerError's generic tail branch.
    // ⚠⚠ THE ONE OBSERVED CASE, MECHANISM CONFIRMED 2026-10-15 by reading the extension at
    // both revisions (third-party: github.com/dutixlf/echo-combine-extension, cloned outside this
    // tree - do not vendor it). Recorded in full because the shape is what a future instance will
    // look like, and because it is NOT a Combine defect:
    //   - At 18b5da3, CombineExtension.loadStreamableMedia read `stream_ext_id` and FELL BACK TO THE
    //     METADATA EXTENSION when it was absent. Only streamables found by SEARCHING another
    //     extension carried the key; streamables taken from the metadata extension itself got a
    //     quality bump and no key at all, and the null-fallback made that work.
    //   - Commit 28bd5ed ("exclude metadata playback and match complete track versions", shipped in
    //     d8de78e, 2026-10-14) replaced that fallback with require(stream_ext_id matches a
    //     configured provider). The KEY NAME did not change - "stream_ext_id" is identical at both
    //     revisions - so this is not a rename; the TOLERANCE for its absence was removed, which was
    //     the whole point of that commit.
    //   - So a Track we cached while 18b5da3 was installed, WHOSE STREAM CAME FROM THE METADATA
    //     EXTENSION, has streamables with no `stream_ext_id`, and the new require() throws
    //     "Stream has no configured audio provider". PARTIAL by construction: tracks whose stream
    //     came from a searched extension carry the key and still play, which is why a report of this
    //     reads as "some tracks" rather than "all".
    // ⚠️ AND NOTHING OF OURS REFRESHES SUCH AN ENTRY - all four candidates checked:
    // preferCache has NO TTL by explicit policy (see the note at Cached.loadMedia); Cached.loadMedia's
    // self-heal tests `servers.isEmpty()` and these entries have FULL servers, just one missing extras
    // key, so it never fires; TRACKS_SCHEMA versions only the durable playlist/album stores, not the
    // kache media-state entries; and this retry re-serves the same state, which is the defect above.
    // WORKAROUND THAT WORKS TODAY: Clear cache. The kache lives in cacheDir (App.getCache), so
    // Android's Clear cache reaches it - unlike the durable stores in filesDir, which it cannot touch
    // (see CacheUtils.cacheDir).
    //
    // ⚠️ DECISION 2026-10-15: MUTED, STILL PARKED. A CLOSED QUESTION, not a queued task -
    // a one-off upstream tightening with a working Clear-cache workaround, against a change that sits
    // in the retry + buffering path, which is the most expensive place in the app to be wrong.
    // ⚠⚠ AND CONFIRMING THE MECHANISM DID NOT MEET THE BUILD TRIGGER - read this before
    // treating the detail above as new evidence. The trigger is a SECOND, UNRELATED report of a stale
    // cached Track. What happened on 2026-10-15 is that the FIRST report was explained, which makes
    // the note better and the case for building it no stronger. Still one report.
    //
    // THE FIX, RECORDED SO IT NEED NOT BE RE-DERIVED (option C):
    //   - THE RETRY PATH RESOLVES WITH preferCache = false. Thread the flag from here through the
    //     metadata bundle (alongside "loaded" and "retries") into StreamableLoader.loadTrack's
    //     Cached.loadMedia call. No new write is needed: loadMedia already overwrites the entry on a
    //     successful fresh load.
    //   - RE-ENTER AT StreamableLoader.load WITH loaded = false, which is exactly what this function
    //     already does. Do NOT wrap loadServer instead: serverIndex is chosen inside
    //     MediaItemUtils.buildLoaded (via PlayerService.selectServerIndex), so a freshly loaded track
    //     paired with the OLD index re-creates the out-of-range fault being fixed - see the parked
    //     note at StreamableLoader.loadServer.
    //   - TRIGGER ON NON-NETWORK FAILURES ONLY, and gate it OFF extensions whose loadTrack is LOCAL.
    //     For Offline a forced reload replaces a working cache hit with a slim track and throws; the
    //     four cases are enumerated at OfflineExtension.loadTrack.
    //   - ⚠️ PRICE THE RISK BEFORE BUILDING, AND IT IS NOT THE OFFLINE CASE. Clean
    //     offline is harmless: loadItem fails fast on DNS and Cached.loadMedia's getOrElse re-serves
    //     the same state, costing one wasted pass. The dangerous case is PRESENT-BUT-BROKEN, e.g. a
    //     captive portal - Deezer's connect 15s + read 10s then run INSIDE THE BUFFERING PATH against
    //     StreamableLoader.load's withTimeout(30_000), the 5s buffering watchdog, the 60s stuck
    //     detector and RESOLVE_GRACE_MS. Avoiding precisely that is why preferCache exists; read the
    //     note at Cached.loadMedia before writing any of this.
    //
    // OPTION D - PUTTING THE EXTENSION VERSION IN THE CACHE KEY - WAS CONSIDERED AND REJECTED, and it
    // is recorded because it is the obvious idea and it is the worse one. It guarantees a MISS on the
    // first play after every extension update, which is exactly the offline restore e9cf3b85 added
    // preferCache to protect (loadMedia's getOrElse fallback reads the same new key, so it does not
    // soften that); it is BLIND to server-side staleness, which is most of what actually goes stale;
    // and getMedia/loadMedia are public inline, making it a wide edit carrying the stale-inline hazard
    // documented at Cached.getMedia. The targeted precedent to copy instead is Cached.loadMedia's
    // servers.isEmpty() guard: one condition, failure-shaped, no age test.
    // ⚠️ IN FAIRNESS, THE OBSERVED CASE IS THE ONE D WOULD HAVE CAUGHT AND C WOULD NOT
    // CHEAPLY: an extension-version component in the kache key invalidates exactly these entries on
    // upgrade, with no failure needed to trigger it. The rejection above still stands on its other
    // grounds, but do not read it as "D has no case" - it has precisely this one.
    /**
     * Force a fresh resolve of this item WITHOUT touching the retry counter.
     *
     * ⚠⚠ `loaded = false` ALONE DOES NOT RE-RESOLVE, WHICH IS WHY THIS
     * BUMPS A COUNTER AT ALL. StreamableMediaSource.canUpdateMediaItem compares retries,
     * serverIndex, sourceIndex, backgroundIndex and subtitleIndex - NOT `loaded` - so an item
     * that differs only in `loaded` is updated IN PLACE on the existing source, with no fresh
     * prepare and therefore no resolve. Some compared field has to change.
     * ⚠⚠ AND IT MUST NOT BE `retries`, BECAUSE THAT FIELD IS AN ERROR
     * BUDGET. PlayerEventListener spends it: `mediaItem.retries >= maxSingleItemRetries` (= 1)
     * decides whether an HTTP 5xx is retried or the track is SKIPPED, and stuckDetail reports it.
     * A re-resolve we asked for is not an error, so spending that budget would make the next
     * genuine 5xx on the same track skip immediately. `reresolve` is compared by
     * canUpdateMediaItem and read by nothing else.
     * ⚠️ ONE CALLER: CastSessionController, at cast start, for a high-res
     * stream the receiver cannot be given. Keep it that way - a general "re-resolve this" is a
     * loaded gun on a path where every resolve costs a request and a token.
     */
    fun withReresolve(item: MediaItem): MediaItem {
        val bundle = Bundle().apply {
            putAll(item.mediaMetadata.extras!!)
            putBoolean("loaded", false)
            putInt("reresolve", getInt("reresolve") + 1)
        }
        return buildWithBundle(item, bundle)
    }

    fun withRetry(item: MediaItem): MediaItem {
        val bundle = Bundle().apply {
            putAll(item.mediaMetadata.extras!!)
            val retries = getInt("retries") + 1
            putBoolean("loaded", false)
            putInt("retries", retries)
        }
        return buildWithBundle(item, bundle)
    }

    private fun buildWithBundle(mediaItem: MediaItem, bundle: Bundle) = run {
        val item = mediaItem.buildUpon()
        val metadata =
            mediaItem.mediaMetadata.buildUpon().setExtras(bundle)
                .build()
        item.setMediaMetadata(metadata)
        item.build()
    }

    @Serializable
    data class Key(val trackId: String, val sourceIndex: Int, val extensionId: String)

    fun String.toKey() = runCatching {
        Base64.decode(this).toString(UTF_8).toData<Key>().getOrThrow()
    }

    fun buildForSource(
        mediaItem: MediaItem, index: Int, source: Streamable.Source?,
    ) = with(mediaItem) {
        val item = buildUpon()
        item.setUri(Base64.encode(Key(track.id, index, extensionId).toJson().toByteArray()))
        when (val decryption = (source as? Streamable.Source.Http)?.decryption) {
            null -> {}
            is Streamable.Decryption.Widevine -> {
                val drmRequest = decryption.license
                val config = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(drmRequest.url).setMultiSession(decryption.isMultiSession)
                    .setLicenseRequestHeaders(drmRequest.headers).build()
                item.setDrmConfiguration(config)
            }
        }
        item.build()
    }

    fun buildWithBackgroundAndSubtitle(
        mediaItem: MediaItem,
        background: Streamable.Media.Background?,
        subtitle: Streamable.Media.Subtitle?,
    ) = with(mediaItem) {
        val bundle = Bundle().apply {
            putAll(mediaMetadata.extras!!)
            putSerialized("background", background)
        }
        val item = buildUpon()
        item.setMediaMetadata(mediaMetadata.buildUpon().setExtras(bundle).build())
        item.setSubtitleConfigurations(
            if (subtitle == null) listOf()
            else listOf(
                MediaItem.SubtitleConfiguration.Builder(subtitle.url.toUri())
                    .setMimeType(subtitle.type.toMimeType())
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            )
        )
        item.build()
    }


    @OptIn(UnstableApi::class)
    private fun MediaState<Track>.toMetaData(
        bundle: Bundle,
        downloads: List<Downloader.Info>,
        context: EchoMediaItem? = bundle.getSerialized<EchoMediaItem>("context")?.getOrNull(),
        loaded: Boolean = bundle.getBoolean("loaded"),
        app: App,
        serverIndex: Int? = null,
        backgroundIndex: Int? = null,
        subtitleIndex: Int? = null,
    ) = with(item) {
        val isLiked = (this@toMetaData as? MediaState.Loaded<*>)?.isLiked == true
        MediaMetadata.Builder()
            .setTitle(title)
            .setAlbumTitle(album?.title)
            .setAlbumArtist(album?.artists?.joinToString(", ") { it.name })
            .setArtist(artists.joinToString(", ") { it.name })
            .setArtworkUri(cover?.toUriWithJson())
            .setUserRating(
                if (isLiked) ThumbRating(true) else ThumbRating()
            )
            .setExtras(Bundle().apply {
                putAll(bundle)
                // ══ PARKED MEMORY WORK — READ BEFORE "OPTIMISING" THIS BUNDLE (2026-09-07) ═══════════
                // Measured: ~20-30 KB per MediaItem (heap_used_mb_build 45-63 MB at 2,001 items). At the
                // 5,432-item queues one user normally carries, that is 110-160 MB retained by the timeline
                // alone, on a device with a 384 MB growth limit. Three candidates were scoped; only the
                // restoreCache TTL was taken. The other two are PARKED WITH REASONS so they are not
                // rediscovered from scratch — or, worse, taken in the wrong order.
                //
                // (2) PER-ITEM PAYLOAD — ⚠️ DOWNGRADED 2026-09-07 AFTER THE AUDIT. It was recorded here
                //     as "the largest number and the real lever". THE NUMBERS DO NOT SUPPORT THAT, and the
                //     claim is corrected rather than left standing.
                //
                //     MEASURED (modelled on DeezerParser.toTrack's shape with realistic field LENGTHS;
                //     compact JSON, UTF-16 in memory):
                //       user-set item   3053 chars ~ 6.0 KB   display fields 55%   remainder 2.6 KB
                //       restored item   1742 chars ~ 3.4 KB   display fields 88%   remainder 0.4 KB
                //       of the user-set remainder: extras 0.9 KB (TRACK_TOKEN dominates), streamables
                //       0.7 KB, description 0.2 KB, genres 0.1 KB
                //       per-item bundle beyond `state`: context 1.3 KB, unloadedCover 0.3 KB
                //
                //     ⚠️ THE RECONCILIATION FAILS, AND THAT IS THE FINDING. Modelled total is ~15 KB per
                //     item (Track + context + metadata). The DEVICE says 20-30 KB (heap_used_mb_build
                //     45-63 MB / restore_build_count 2001). So A THIRD TO A HALF OF THE REAL PER-ITEM COST
                //     IS NOT THE JSON AT ALL — it is Bundle overhead, the MediaItem/MediaMetadata object
                //     graphs, and the parcel machinery around them. No amount of field slimming reaches
                //     that half.
                //
                //     ⚠️ SO THE LEVER IS NOT "WHICH FIELDS THE TRACK CARRIES". It is HOW MANY SERIALIZED
                //     BLOBS AND BUNDLES EXIST PER ITEM. Two candidates follow from that framing, and only
                //     one survives:
                //       - context hoisting (1.3 KB x N, pure duplication of ONE shared object) — the only
                //         survivor, and it STILL needs a lifetime-managed process-level map, which is the
                //         exact shape that produced the restoreCache leak fixed in the same investigation.
                //       - not storing display data twice — considered and rejected, see below.
                //
                //     ⚠️ AND THE RISK FRAMING WAS WRONG. It was recorded as "answerable by reading: does
                //     anyone read this field". THAT HOLDS FOR APP-SIDE CONSUMERS ONLY. The stored Track is
                //     handed VERBATIM to extensions on three paths — PlayerCallback.applyRating ->
                //     likeItem(track), StreamableLoader -> loadStreamableMedia(app, ext, track, streamable),
                //     and TrackingListener -> TrackDetails(…, track, …) -> TrackerClient. Those receivers
                //     are separate APKs loaded by DexClassLoader; what they read is UNKNOWABLE BY READING.
                //     The standing extension-adjacent caution already held a change that HAD a clean
                //     single-writer proof; this one cannot produce one.
                //     EVIDENCE FROM OUR OWN HISTORY, not principle: partial ABI slimming has been live
                //     since 92af04f5 (2026-07-26), because toSlim strips extras from every persisted
                //     track. It silently killed Unified TRACKING for restored queues for six weeks
                //     (UnifiedExtension's four TrackerClient callbacks) and nobody noticed. "An extension
                //     receives a Track with an empty field where it used to have data" is a real,
                //     already-realised failure mode, and it is SILENT — unlike the R8 repackaging break,
                //     which was loud, total and is now guarded by verifyExtensionAbi.
                //
                //     ⚠️ APP-SIDE-ONLY VARIANT — CONSIDERED AND REJECTED 2026-09-07. The idea: stop
                //     storing display data twice by having the queue UI read MediaMetadata (title /
                //     artist / artworkUri, which toMetaData already derives) instead of deserializing
                //     `state` per row in PlayerTrackAdapter:333,357,358,372. The question that could have
                //     made it attractive was answered YES — MediaMetadata DOES stay in sync, because
                //     buildLoaded re-derives it from the loaded state — and it still does not save it:
                //       SAVES ~0.3-0.5 KB per item (only the MediaMetadata copy; the Track must still
                //         carry display fields for saveQueue and for the ABI paths above).
                //       COSTS two live regression risks in the file with six sessions of display defects:
                //         (a) cover flash on rotation before DiffUtil completes — unloadedCover's
                //             synchronous getCachedDrawable exists precisely for that window;
                //         (b) stale-cover self-correction, which today happens when bind() re-reads the
                //             Track, would instead depend on buildLoaded having replaced the timeline item
                //             AND the adapter seeing it — one more link in a chain that has already failed
                //             twice.
                //     Bad trade. Do not revive it on the "we already know MediaMetadata is fresh" argument;
                //     that was checked and is not the blocker.
                //
                //     OBSERVATION ONLY, NOT A THREAD TO PULL (2026-09-07): the user-set remainder is
                //     dominated by `extras` at 0.9 KB, and TRACK_TOKEN dominates that. Deezer's token is
                //     time-limited and DeezerTrackClient.loadTrack already self-heals when it is empty, so
                //     a token stored on a RESTORED queue is very likely dead weight. Recorded rather than
                //     acted on: that self-heal has a documented defect — it re-fetches by TOP-LEVEL id and
                //     overwrites grafted art — so anything that leans on it inherits that. Do not start
                //     here.
                //
                //     Asymmetry worth keeping regardless: RESTORED items are already slim (toSlim at save
                //     time), USER-SET items carry the full extension graph — so restored queues are near a
                //     floor already and any measurement taken on one understates the user-set case.
                //
                //     SIBLING PARKED ITEM, SAME Car/AA HEAP INVESTIGATION: the force-instantiation at
                //     AndroidAutoCallback's `Extension<*>.toMediaItem`, where building the AA browse root runs
                //     instance.value() for EVERY enabled extension purely to set a tile flag, pinning every
                //     extension graph for the process lifetime. That one is about HOW MANY GRAPHS ARE
                //     RETAINED; this one is about HOW BIG EACH QUEUE ITEM IS. Different levers, same heap —
                //     measure both before attributing a number to either.
                //
                // (3) CONTEXT HOISTING — 5,432 copies of ONE object, roughly 5-16 MB. NOT TAKEN because
                //     MediaItem is the unit Media3 carries into the timeline; there is no per-queue side
                //     channel, so hoisting needs a process-level map keyed by queue generation, with its
                //     own lifetime to manage. An unmanaged lifetime of exactly that shape is what produced
                //     the restoreCache leak this same investigation fixed.
                //
                // ⚠️ unloadedCover — WITHDRAWN, DO NOT PROPOSE AGAIN. It looks like pure duplication of
                // state.item.cover and is not: its three consumers (PlayerFragment, PlayerTrackAdapter x2)
                // read it via getCachedDrawable on a hot UI path, and reading the cover off `state` instead
                // would force a full getSerialized<MediaState<Track>> parse on every bind and every pager
                // scroll frame. It trades a few hundred bytes per item to avoid a decode. Keep it.
                putSerialized("unloadedCover", bundle.stateNullable?.item?.cover)
                putSerialized("state", this@toMetaData)
                putSerialized("context", context)
                putBoolean("loaded", loaded)
                putInt("subtitleIndex", subtitleIndex ?: 0.takeIf { subtitles.isNotEmpty() } ?: -1)
                putInt(
                    "backgroundIndex", backgroundIndex ?: 0.takeIf {
                        backgrounds.isNotEmpty() && app.settings.showBackground()
                    } ?: -1
                )
                val downloaded =
                    downloads.filter { it.download.trackId == id }
                        .mapNotNull { it.download.finalFile }
                // Read extensionId off the MediaState receiver directly. Bare `extensionId` here binds to
                // the nearest implicit receiver — this `apply` Bundle — resolving to `Bundle?.extensionId`,
                // which deserializes the whole `state` JSON just written above only to read one String
                // (N redundant full-state decodes per window/queue build). `this@toMetaData.extensionId`
                // is the same String (plain @Serializable member; byte-identical) with no decode.
                // ⚠️ ONE CALL, NOT TWO - KEPT NOW THE PROBE THAT FORCED IT HAS GONE.
                // selectServerIndex walks the server list AND reads SharedPreferences, and this
                // function runs inside .map over whole track lists, so a second call per item
                // would double both for a 300-item restore. The local reads identically at its one
                // use below, so the hoist costs nothing and is not worth inlining back.
                val chosenServerIndex =
                    serverIndex ?: selectServerIndex(app, this@toMetaData.extensionId, servers, downloaded)
                putInt(
                    "serverIndex",
                    chosenServerIndex
                )
                putSerialized("downloaded", downloaded)
            })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            // Publish the catalog duration so the session/AA (and lockscreen) can render the
            // progress bar total before prepare() resolves the stream duration. durationMs is a
            // metadata field, independent of player.getDuration(); invisible to the resume path.
            .apply { duration?.let { setDurationMs(it) } }
            .build()
    }


    private val Bundle?.stateNullable
        get() = this?.getSerialized<MediaState<Track>>("state")?.getOrNull()
    val Bundle?.state get() = requireNotNull(stateNullable)
    val Bundle?.track get() = state.item
    val Bundle?.isLoaded get() = this?.getBoolean("loaded") ?: false
    val Bundle?.extensionId get() = state.extensionId
    val Bundle?.context get() = this?.getSerialized<EchoMediaItem?>("context")?.getOrNull()
    val Bundle?.serverIndex get() = this?.getInt("serverIndex", -1) ?: -1
    val Bundle?.sourceIndex get() = this?.getInt("sourceIndex", -1) ?: -1
    val Bundle?.backgroundIndex get() = this?.getInt("backgroundIndex", -1) ?: -1
    val Bundle?.subtitleIndex get() = this?.getInt("subtitleIndex", -1) ?: -1
    val Bundle?.background
        get() = this?.getSerialized<Streamable.Media.Background?>("background")?.getOrNull()
    val Bundle?.retries get() = this?.getInt("retries") ?: 0

    // Bumped only by [withReresolve]; absent (0) on every item built normally. Compared by
    // StreamableMediaSource.canUpdateMediaItem and read nowhere else - see that function.
    val Bundle?.reresolve get() = this?.getInt("reresolve") ?: 0
    val Bundle?.unloadedCover
        get() = this?.getSerialized<ImageHolder?>("unloadedCover")?.getOrNull()
    val Bundle?.downloaded get() = this?.getSerialized<List<String>>("downloaded")?.getOrNull()

    val MediaItem.state get() = mediaMetadata.extras.state
    val MediaItem.track get() = mediaMetadata.extras.track
    val MediaItem.extensionId get() = mediaMetadata.extras.extensionId
    val MediaItem.context get() = mediaMetadata.extras.context
    val MediaItem.isLoaded get() = mediaMetadata.extras.isLoaded
    val MediaItem.serverIndex get() = mediaMetadata.extras.serverIndex
    val MediaItem.sourceIndex get() = mediaMetadata.extras.sourceIndex
    val MediaItem.backgroundIndex get() = mediaMetadata.extras.backgroundIndex
    val MediaItem.subtitleIndex get() = mediaMetadata.extras.subtitleIndex
    val MediaItem.background get() = mediaMetadata.extras.background
    val MediaMetadata.isLiked get() = (userRating as? ThumbRating)?.isThumbsUp == true
    val MediaItem.isLiked get() = mediaMetadata.isLiked
    val MediaItem.retries get() = mediaMetadata.extras.retries
    val MediaItem.reresolve get() = mediaMetadata.extras.reresolve
    val MediaItem.unloadedCover get() = mediaMetadata.extras.unloadedCover
    val MediaItem.downloaded get() = mediaMetadata.extras.downloaded

    private fun Streamable.SubtitleType.toMimeType() = when (this) {
        Streamable.SubtitleType.VTT -> MimeTypes.TEXT_VTT
        Streamable.SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
        Streamable.SubtitleType.ASS -> MimeTypes.TEXT_SSA
    }

    private fun ImageHolder.toUriWithJson(): Uri {
        val main = when (this) {
            is ImageHolder.ResourceUriImageHolder -> uri
            is ImageHolder.NetworkRequestImageHolder -> request.url
            is ImageHolder.ResourceIdImageHolder -> "res://$resId"
            is ImageHolder.HexColorImageHolder -> ""
        }.toUri()
        val json = toJson()
        return main.buildUpon().appendQueryParameter("actual_data", json).build()
    }

    const val SHOW_BACKGROUND = "show_background"
    fun SharedPreferences?.showBackground() = this?.getBoolean(SHOW_BACKGROUND, true) ?: true

    fun MediaItem.serverWithDownloads(
        context: Context,
    ) = track.servers + listOfNotNull(
        Streamable.server(
            "DOWNLOADED", Int.MAX_VALUE, context.getString(R.string.downloads)
        ).takeIf { !downloaded.isNullOrEmpty() }
    )
}
