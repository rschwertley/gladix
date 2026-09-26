package dev.brahmkshatriya.echo.ui.common

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class PagedSource<T : Any>(
    private val loaded: Result<PagedData<T>>?,
    private val cached: Result<PagedData<T>>? = null,
    /**
     * Whose extension this page data belongs to. When supplied, a load failure is converted to an
     * [dev.brahmkshatriya.echo.extensions.exceptions.AppException] before it reaches the UI.
     *
     * ⚠⚠ NULLABLE BECAUSE ONE CALLER CANNOT PAIR IT SAFELY YET, NOT BECAUSE IT IS OPTIONAL IN
     * SPIRIT. FeedData supplies it; LyricsViewModel does not, and passing null there preserves that
     * screen's behaviour byte-for-byte rather than half-fixing it. See the note on `transform`.
     */
    private val extension: Metadata? = null,
) : PagingSource<String, T>() {

    val flow = Pager(
        PagingConfig(
            pageSize = 10,
            // ⚠⚠ FeedAdapter DEPENDS ON THIS BEING false - DO NOT FLIP IT WITHOUT READING THAT
            // FILE FIRST. FeedAdapter.getItemViewType and isSectionHeader use PagingDataAdapter.peek
            // rather than getItem (an ANR fix - the span-group walk calls them O(position) times per
            // child during a fast-scroller drag). peek is equivalent to getItem ONLY while there are
            // no placeholders: with placeholders ON it returns null for an unloaded slot, and
            // getItemViewType's `?: 0` fallback resolves to FeedType.Enum.Header, which getSpanSize
            // maps to FULL WIDTH. Every unloaded row would render as a full-width header, silently.
            enablePlaceholders = false,
            prefetchDistance = 20,
            // Enable Paging3 page-dropping to bound deep-scroll growth (was unbounded → OOM contributor).
            // Must be >= pageSize + 2*prefetchDistance = 10 + 40 = 50; 100 keeps ~10 pages so the active
            // window + generous buffer never drops during normal browsing. Only far-offscreen pages drop, and
            // scrolling back re-fetches instantly from the extension PagedData.itemMap (already cached, no
            // network) — bounded memory, no correctness change.
            maxSize = 100
        )
    ) { this }.flow.flowOn(Dispatchers.IO)

    override val keyReuseSupported = true
    override fun getRefreshKey(state: PagingState<String, T>): String? {
        val pos = state.anchorPosition ?: return null
        val key = state.closestPageToPosition(pos)?.nextKey
        invalidate(key)
        return key
    }

    private fun invalidate(key: String?) {
        cached?.getOrNull()?.invalidate(key)
        loaded?.getOrNull()?.invalidate(key)
    }

    /**
     * ⚠⚠ withContext(IO) HERE IS NOT REDUNDANT WITH flowOn(Dispatchers.IO) ABOVE, AND THE
     * DIFFERENCE IS WHAT CAUSED AN ANR. `Pager.flow` is a Flow<PagingData<T>> - a flow of CONTAINERS -
     * so flowOn moves where those few containers are EMITTED. The page loading lives in each
     * PagingData's OWN inner flow of PageEvents, which the differ collects in ITS context
     * (AsyncPagingDataDiffer.submitData, on the UI lifecycleScope = Main). PageFetcherSnapshot then
     * calls this function from there. Nothing propagates a dispatcher across that boundary, which is
     * why Paging 3 has no fetchDispatcher (Paging 2's setFetchExecutor did) - the contract is that
     * PagingSource.load must be main-safe ITSELF.
     * FIELD EVIDENCE: an ANR on build 1062 (budget device, Android 11) with the main thread inside
     * Json.decodeFromString of a cached Page<Shelf>, stack reading
     * PageFetcherSnapshot.doInitialLoad -> PagedSource.load -> PagedData.loadPage. The same ANR was
     * closed on build 979 as "already fixed", almost certainly on the strength of the flowOn line -
     * which was already present and never covered this. Do not read flowOn as covering it again.
     *
     * ⚠️ IT COVERS MORE THAN THE CACHE DECODE. Everything a page load does moves off main -
     * the decode in Cached.getData AND the extension's own parsing inside loadPage. The chokepoint
     * wrap at Cached.getData/putData is the companion fix for callers that never come through paging;
     * neither makes the other unnecessary, and fixing only one is how this reopened between 979 and 1062.
     *
     * ⚠️ CANCELLATION IS UNCHANGED, WHICH IS EASY TO MISREAD. runCatching still catches
     * CancellationException inside this block, so a cancelled page load still becomes
     * LoadResult.Error exactly as before - see the note at transform() for why that is deliberate.
     * withContext sits OUTSIDE it and does not reorder that.
     *
     * ⚠️ THE getOrElse ARM LOST ITS `return`, AND HAD TO. It was a non-local return out of
     * load(); inside a suspend lambda that will not compile. It was already the arm's last expression,
     * so dropping the keyword changes nothing at runtime.
     */
    override suspend fun load(params: LoadParams<String>): LoadResult<String, T> =
        withContext(Dispatchers.IO) {
            val key = params.key
            runCatching {
                val page = loaded?.getOrThrow()?.loadPage(key) ?: throw LoadingException()
                LoadResult.Page(page.data, key, page.continuation)
            }.getOrElse { error ->
                val cachedPage = cached?.mapCatching { it.loadPage(key) }?.getOrNull()
                if (cachedPage == null || cachedPage.data.isEmpty())
                    LoadResult.Error(transform(error))
                else LoadResult.Page(cachedPage.data, key, cachedPage.continuation)
            }
        }

    /**
     * ⚠⚠ THE ONLY EXTENSION CALL PATH IN THE APP THAT DID NOT APPLY toAppException, AND THE
     * SYMPTOM WAS A RETRY BUTTON ON AN ERROR RETRYING CANNOT FIX. Every other extension call goes
     * through ExtensionUtils.get, which wraps failures via toAppException. `load` above did not, so a
     * raw ClientException.LoginRequired thrown from inside an extension's paging lambda reached
     * FeedLoadingAdapter.getStateViewType, missed its `is AppException.LoginRequired -> 3` arm
     * (a ClientException is not an AppException), fell to `else -> 2`, and rendered RETRY.
     * ⚠️ SO THIS IS NOT A NEW POLICY FOR THIRD-PARTY EXTENSIONS - IT REMOVES AN INCONSISTENCY.
     * A LoginRequired from any NON-paging path already renders as a sign-in affordance for every
     * extension; this gives the paging path the behaviour the rest of the app already has. (And where
     * an extension's LoginRequired is genuinely ambiguous, a sign-in prompt is at worst a wasted tap,
     * while Retry on a dead credential can never succeed.)
     * REACHABLE, TRACED 2026-09-22: DeezerArtistClient puts handleArlExpiration() inside a
     * PagedData.Continuous load lambda, which is exactly what `load` invokes via loadPage(key).
     * ⚠️ WHAT UNBLOCKED THIS: an August closure held that LoginRequired could not be routed to
     * the login shelf because it could not distinguish "user must sign in" from a recoverable stale
     * ARL. That is no longer true - see the four-site table at DeezerExtension.handleArlExpiration.
     * If a future extension reintroduces an ambiguous LoginRequired, THAT table is the thing to
     * re-check, not this line.
     * ⚠️ AND THE credentialsRejected FLAG DID NOT CAUSE THIS - IT MADE IT FAIL FAST. Before the
     * flag the same paging load produced the same Retry button after a network round trip; the flag
     * only removed the round trip. THE RETRY BUTTON WAS ALWAYS WRONG HERE. Do not revert the flag
     * chasing this symptom.
     * ⚠⚠ WHICH SURFACES THIS COVERS - AUDITED 2026-09-22 SO IT IS NOT RE-DERIVED. FeedLoadingAdapter
     * has SIX construction sites (scope: app/src/main/java, recursive), and only the two backed by a
     * PagedSource could ever see a RAW extension exception:
     *   FeedAdapter (header + footer)        <- PagedSource via FeedData        COVERED (metadata passed)
     *   LyricsItemAdapter (header + footer)  <- PagedSource via LyricsViewModel COVERED (metadata passed)
     *   LyricsFragment.lyricsErrorAdapter    <- Cached.loadLyrics, which uses extension.getAs
     *                                           -> ALREADY an AppException, never raw
     *   MediaMoreBottomSheet.loadingAdapter  <- itemResultFlow <- Cached.loadMedia -> loadItem,
     *                                           which uses getAs on EVERY branch -> already wrapped
     * The last two set LoadState.Error by hand from a Result, which LOOKS like the same hazard and is
     * not: their Results came through ExtensionUtils.get. So there is no third surface, and the reason
     * is the same one that makes this function necessary - `load` is the ONLY extension call path in
     * the app that does not already route through toAppException.
     * ⚠️ IF A SEVENTH FeedLoadingAdapter APPEARS, THE QUESTION TO ASK IS NOT "does it show
     * errors" BUT "where does its Result come from" - through getAs (safe) or straight off an
     * extension lambda (needs this).
     *
     * ⚠⚠ DO NOT "FIX" THIS INSTEAD BY WIDENING getStateViewType's `when` TO ACCEPT A RAW
     * ClientException.LoginRequired. FeedLoadingAdapter's LoginRequired holder does an unchecked
     * `error as AppException.LoginRequired` in bind(), guarded ONLY by that `when` - routing a raw
     * ClientException to holder 3 manufactures a ClassCastException there, not here. The comment at
     * that cast cites a field-proven instance of exactly this coupling. The transform is correct
     * PRECISELY BECAUSE it produces a genuine AppException.
     *
     * ⚠️ THE runCatching IS DELIBERATE AND NARROW: toAppException RETHROWS a plain
     * CancellationException by design, and today a cancelled page load becomes LoadResult.Error like
     * any other. Letting that rethrow escape would be a behaviour change nobody asked for, so it is
     * caught and the original returned - status quo preserved. TimeoutCancellationException is
     * checked BEFORE that arm inside toAppException, so it still wraps correctly.
     */
    private fun transform(error: Throwable): Throwable {
        val metadata = extension ?: return error
        return runCatching { error.toAppException(metadata) }.getOrElse { error }
    }

    companion object {
        fun <T : Any> empty() = PagingData.Companion.empty<T>(
            LoadStates(
                LoadState.Loading,
                LoadState.NotLoading(false),
                LoadState.NotLoading(true)
            )
        )
    }

    class LoadingException() : Exception("Loading PagedSource")
}