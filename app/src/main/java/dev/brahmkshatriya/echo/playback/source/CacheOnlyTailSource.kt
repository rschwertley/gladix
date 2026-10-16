package dev.brahmkshatriya.echo.playback.source

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import android.content.Context as AndroidContext
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.playback.MediaItemUtils.toKey
import dev.brahmkshatriya.echo.playback.source.StreamableDataSource.Companion.uri

/**
 * The crossfade sidecar's media source and its readiness gate.
 *
 * ⚠⚠ [CORRECTED 2026-10-15] "NO UPSTREAM" WAS HALF WRONG, AND THE HALF THAT WAS WRONG
 * STOPPED THE CROSSFADE HAPPENING AT ALL. An upstream is now wired; the WRITE SINK is still
 * null. Why: the gate requires every byte committed, and on device the cache was only 73% and
 * 93% committed on two consecutive tracks (2026-10-15, cachedBytes 4154161/5663346 and
 * 6527021/6986186, both with bufferedEqDuration=true and loading=false), so every gate read
 * cacheReason=incomplete and the user heard no crossfade. With an upstream the holes are
 * fetched instead of fatal.
 * ⚠️ AND THE COST NOTE BELOW OVER-STATED ITSELF: upstream is opened ONLY for bytes
 * that are missing (CacheDataSource.openNextSource, 1.11.0, :734-751 reads a cached span
 * directly), so a fully-committed tail still costs zero network - the common case. Only the
 * holes pay, and they pay a ranged GET rather than a whole second stream.
 * ⚠️ THE CLASS NAME IS NOW A MISNOMER and is kept only to hold the diff down -
 * renaming reaches PlayerService and CrossfadeController's constructor. It is cache-FIRST, not
 * cache-ONLY.
 * ORIGINAL REASONING, STILL LOAD-BEARING FOR THE WRITE SINK: The sidecar replays the
 * TAIL of a track the main player is already streaming, so every byte it needs has already been
 * fetched and decrypted once. Giving it an upstream would mean a SECOND stream open per crossfade -
 * for Deezer that is RawDataSource.open's runBlocking { HEAD getContentLength + ranged GET +
 * Blowfish prime } on the loader thread, against a shared-account CDN. Reading committed cache
 * bytes costs none of that.
 * ⚠️ [AMENDED 2026-10-15] THE PlaceholderDataSource PATH IS NO LONGER TAKEN, and it is
 * recorded because it is what the symptom looked like: with no upstream factory media3
 * substitutes PlaceholderDataSource (CacheDataSource.java:558, 1.11.0), whose open() throws
 * IOException("PlaceholderDataSource cannot be opened"). That is retriable rather than fatal,
 * so a hole degraded instead of crashing - but it degraded by STARVING THE TAIL mid-ramp,
 * which is the most likely reading of "the old song loops / plays a wrong section". The real
 * upstream removes the starvation rather than retrying it.
 * ⚠⚠ READ-ONLY STILL, AND THIS HALF WAS RIGHT - DO NOT ADD A WRITE SINK. SimpleCache is
 * single-writer per key, and the main player's CacheDataSink owns this key.
 * ⚠️ THE NULL SINK ALSO KEEPS US OUT OF THE HOLE-LOCK HAZARD, which was checked
 * before wiring the upstream and is the reason this stays null rather than becoming a second
 * writer "for symmetry". With a null sink, CacheDataSource.openNextSource takes its :767-771
 * arm: it calls cache.releaseHoleSpan(nextSpan) IMMEDIATELY and sets nextSpan = null, so the
 * sidecar never holds a hole-span lock across any I/O. That matters because both players run
 * flags = 0, hence blockOnCache = false (:542) and startReadWriteNonBlocking (:723) - and a
 * caller that gets null there BYPASSES THE CACHE WITHOUT WRITING (:728-733) and then suppresses
 * re-checking it for MIN_READ_BEFORE_CHECKING_CACHE = 100 KB (:403, :774-777). A sidecar holding
 * hole locks could therefore have PUNCHED the very holes it then failed on. It cannot: the lock
 * is released inside the same call, and wiring the upstream does not change that arm at all
 * (:765's `cacheWriteDataSource != null` is false either way).
 *
 * ⚠⚠ THE RESOLVER IS SHARED WITH THE MAIN PLAYER'S CHAIN BY CONSTRUCTION - it is handed the
 * SAME `state.servers` map. That is what makes the sidecar resolve to the identical URL, which is
 * what makes the cache key identical: the default key is the DataSpec uri (CacheKeyFactory.DEFAULT),
 * and [StreamableDataSource.uri] derives it from the resolved source - the signed CDN URL for Http,
 * "raw://<id.hashCode()>" for Raw. A SECOND resolve would mint a different signed URL, hence a
 * different key, hence a guaranteed cache miss. Never resolve for the sidecar.
 */
@OptIn(UnstableApi::class)
class CacheOnlyTailSource(
    context: AndroidContext,
    private val cache: SimpleCache,
    private val servers: MutableMap<String, Result<Streamable.Media.Server>>,
) {

    private val resolver = StreamableResolver(context, servers)

    /**
     * ⚠️ A FRESH StreamableDataSource.Factory, NOT A SHARED ONE, and that is safe because
     * the factory is stateless - createDataSource() mints a new source per load. What must NOT be
     * duplicated is the RESOLVER, and it is not: [resolver] is built over the same `servers` map the
     * main player uses, so the resolved URL and therefore the cache key stay identical.
     */
    private val upstream = StreamableDataSource.Factory(context)

    /**
     * Progressive only, deliberately: the tail is a byte range of an already-open container.
     * Dash/Hls factories are not wired because a sidecar never plays a manifest-based stream - those
     * arrive as Http sources with their own chunk sources.
     * ⚠️ [AMENDED 2026-10-15] the old reason ended "and the gate below rejects them anyway
     * (no single whole-resource length to confirm)" - that is no longer true, because [Gate.ready] is
     * not a condition any more (see CrossfadeController.evaluateGate). Progressive-only is now the
     * ONLY thing keeping a manifest source out of the sidecar, so do not wire dash/hls here casually.
     */
    val factory: MediaSource.Factory by lazy {
        DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(
                CacheDataSource.Factory()
                    .setCache(cache)
                    // Upstream for holes; NO write sink. Only the second omission is load-bearing
                    // now - see the two class notes.
                    .setUpstreamDataSourceFactory(upstream)
                    .setCacheWriteDataSinkFactory(null),
                resolver
            )
        )
    }

    /** What the gate decided, with the two numbers it decided on. */
    data class Gate(
        val ready: Boolean,
        val reason: String,
        val cachedBytes: Long,
        val contentLength: Long,
    )

    /**
     * Is the WHOLE resource committed to the cache and readable right now?
     *
     * ⚠⚠ WHOLE RESOURCE, NOT THE TAIL RANGE, AND THAT IS NOT LAZINESS. getCachedBytes takes BYTE
     * positions while the tail is known in TIME, and a bytes<->time mapping is only exact for CBR -
     * wrong for VBR and for FLAC, which is most of the catalogue. Confirming the whole resource needs
     * no mapping at all. It is stricter than necessary and cannot produce a false READY.
     * ⚠️ IT READS ONLY COMMITTED SPANS, which is exactly the property wanted. SimpleCache
     * .getCachedBytes goes through the content index (SimpleCache.java:477 -> :467), and a span enters
     * that index in commitFile - so bytes still sitting in an open CacheDataSink fragment are
     * correctly invisible here. A fragment commits at 5 MB (CacheDataSink.DEFAULT_FRAGMENT_SIZE) or
     * when the DataSource closes, and the close happens at LOAD completion
     * (ProgressiveMediaPeriod.ExtractingLoadable.load's finally, :1356) - which for a track with less
     * than 50 s left is well before playback reaches the end.
     * ⚠⚠ [CORRECTED 2026-10-15] THAT LAST CLAUSE ASSUMED A 50 s BUFFER, AND THE BUFFER
     * HAD NOT BEEN 50 s SINCE 2026-06-06. "A track with less than 50 s left" is only the moment
     * of load completion if the loader keeps ~50 s buffered - which is media3's STREAMING
     * configuration. Since the 1.8.0 -> 1.10.1 bump (464d4d3f) DefaultLoadControl had been
     * classifying every one of our tracks as LOCAL playback, because our MediaItem uri is
     * MediaItemUtils.buildForSource's scheme-less Base64 - giving a 1 s minimum buffer and a
     * sticky 1..50 s band. Measured completion was dur-10 s to dur-31 s (six captures,
     * 2026-10-14/15), so on the dur-10.2 s case the final commit arrived AT THE FADE POINT and
     * this gate had ZERO margin, not "well before". Anything later than dur-10 s was a
     * guaranteed Abort.NOT_READY.
     * The sentence is true again as written: PlayerService.createExoplayer now installs an
     * explicit DefaultLoadControl whose generic values mirror onto the local ones, restoring
     * 50 s and putting completion back at ~dur-50 s - 15 s before arming even starts. READ THE
     * NOTE AT THAT SITE BEFORE CHANGING ANY BUFFER VALUE, because this gate's margin is
     * downstream of it: the whole resource must be committed before the fade begins, and what
     * decides when that happens is minBufferMs.
     * ⚠️ A `file://` SOURCE ALWAYS FAILS HERE and that is correct for this build: downloads bypass
     * the cache entirely (getFactory(source) sends them to the non-cache factories), so they need a
     * direct-file tail path instead. Deliberately not built yet.
     */
    fun gate(item: MediaItem): Gate {
        // ⚠⚠ [CORRECTED 2026-10-14] THIS USED TO READ servers[item.mediaId] AND
        // sources[item.serverIndex], AND serverIndex WAS THE WRONG INDEX SPACE. serverIndex indexes
        // the TRACK'S SERVERS (MediaItemUtils.toMetaData seeds it from selectServerIndex;
        // buildServer overrides it), while `sources` belongs to the ONE server already selected and
        // is indexed by sourceIndex (which is what StreamableMediaSource.prepareSourceInternal uses).
        // With a non-zero serverIndex against a single-source server, getOrNull returned null and the
        // gate reported noResolvedSource on a sidecar that had reached STATE_READY through this very
        // class - observed on the 2026-10-11 build, two gates, both
        // "sidecarReady=true cacheReason=noResolvedSource". It only ever worked when serverIndex
        // happened to be 0, which indexes sources[0] by accident.
        // ⚠⚠ SO THE GATE NOW USES THE RESOLVER'S OWN LOOKUP, BY CONSTRUCTION RATHER
        // THAN BY AGREEMENT. StreamableResolver.resolveDataSpec decodes the DataSpec uri with toKey
        // and reads servers[Key.trackId].sources[Key.sourceIndex]; the DataSpec uri comes from this
        // same item's localConfiguration, so decoding it here asks the identical question of the
        // identical map and cannot drift. That the capture's resolver hit while the gate missed is
        // also what proves Key.trackId == mediaId on real data - the id was never the divergence.
        // ⚠️ AND NOT item.sourceIndex EITHER, WHICH LOOKS LIKE THE ONE-WORD FIX AND IS
        // NOT. buildSource only runs in prepareSourceInternal's MULTI-source branch, so the extras
        // field is still -1 for every single-source track - the gate would have traded
        // noResolvedSource for a negative index. The uri's Key.sourceIndex is always the real one
        // (the single-source branch passes a literal 0 to buildForSource).
        val key = item.localConfiguration?.uri?.toString()?.toKey()?.getOrNull()
            ?: return Gate(false, "noKeyUri", 0, C.LENGTH_UNSET.toLong())
        val source = runCatching {
            servers[key.trackId]?.getOrNull()?.sources?.getOrNull(key.sourceIndex)
        }.getOrNull() ?: return Gate(false, "noResolvedSource", 0, C.LENGTH_UNSET.toLong())
        val cacheKey = source.uri.toString()
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        if (length == C.LENGTH_UNSET.toLong()) return Gate(false, "noContentLength", 0, length)
        val cached = cache.getCachedBytes(cacheKey, 0, length)
        return if (cached == length) Gate(true, "committed", cached, length)
        else Gate(false, "incomplete", cached, length)
    }
}
