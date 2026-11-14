package dev.brahmkshatriya.echo.utils

import android.util.Log
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import java.lang.reflect.Field

/**
 * [2026-11-13] PagedData.loadAll AND Feed.loadAll WITH TWO STOPS, for every caller in the app (the user's decision: no
 * unbounded loadAll left). Crashlytics 700db7c5 (1177, YouTube Music): an OutOfMemoryError allocating ~345 MB in
 * ArrayList.addAll inside PagedData.Continuous.loadAllInternal. That loop ends only on a null continuation. Continuous keeps
 * every page it loaded in its itemMap, keyed by continuation, so an extension that hands back a continuation it has already
 * used (itself, or an earlier one) gets the cached page again with no request: the loop adds the same items over and over at
 * memory speed until the heap runs out (inference from the code; the report has no continuation values). PagedSource.nextKey
 * stops the self-repeat for the screen since 1177; loadAll had no stop. This walk stops on:
 * - A REPEATED CONTINUATION: any continuation already used by that source in this walk, which covers a cycle of any length,
 *   not only a page pointing at itself. That list ends where the repeat began, as the screen does.
 * - ITEM_CEILING items in total, far above the 2,000 queue cap (FeedClickListener.DECLARED_QUEUE_CAP): a list of distinct
 *   continuations that never ends would otherwise run out of memory more slowly.
 * Not on an empty page: an empty FIRST page with more pages is a real shape (PlayerCallback.playItem's empty check).
 *
 * OTHERWISE EXACTLY loadAll (the user's decision), which is why it follows loadAll's own structure (PagedData.kt in :common,
 * untouched for the extension ABI) rather than walking loadPage at the top:
 * - Single and Continuous: the pages loadAll reads, from a null continuation, through loadPage (the same cached page loader,
 *   under the same mutex, taken per page instead of for the whole walk).
 * - Concat: each source walked on its own from null, as Concat.loadAllInternal calls each source's loadAll. Walking the Concat
 *   through its own loadPage would hand the second and later sources "" as their first continuation (Concat.splitContinuation
 *   of "1_"), which is what the screen does and loadAll does not.
 * - Suspend: its inner PagedData, walked as above, as Suspend.loadAllInternal calls data().loadAll().
 * Concat's sources and Suspend's inner data are private in :common, so they are read by reflection. app/proguard-rules.pro keeps
 * every member of dev.brahmkshatriya.echo.common.** (`{ *; }`), private fields included, so the names survive R8. If a read
 * still fails (a :common change that renames them), the walk goes through the Concat's or Suspend's own loadPage instead:
 * still bounded, with the screen's "" for a Concat's later sources.
 * An early stop writes one logcat line (user-approved 2026-11-13: "logs once when it stops early"; logcat only, no Crashlytics).
 */
object BoundedLoadAll {
    const val ITEM_CEILING = 50_000

    /**
     * [2026-11-13] The streaming walk's runaway guard ([forEachPageBounded], the user's decision). A stream keeps only what its
     * consumer keeps (a 2,000-song sample), so the 50,000 that bounds a collected list would cut a big library short and make
     * the sample unfair; this only stops a list that never ends without repeating.
     */
    const val STREAM_CEILING = 500_000
    private const val TAG = "BoundedLoadAll"

    private val concatSources: Field? by lazy {
        runCatching { PagedData.Concat::class.java.getDeclaredField("sources").apply { isAccessible = true } }.getOrNull()
    }
    private val suspendData: Field? by lazy {
        runCatching { PagedData.Suspend::class.java.getDeclaredField("_data").apply { isAccessible = true } }.getOrNull()
    }

    suspend fun <T : Any> PagedData<T>.loadAllBounded(): List<T> {
        val list = mutableListOf<T>()
        walkData(this, Walk(ITEM_CEILING) { list.addAll(it); true })
        return list
    }

    // Feed.loadAll's tabs, one ceiling across all of them.
    suspend fun <T : Any> Feed<T>.loadAllBounded(): List<T> {
        val list = mutableListOf<T>()
        walkFeed(this, Walk(ITEM_CEILING) { list.addAll(it); true })
        return list
    }

    /**
     * [2026-11-13] THE SAME WALK AS loadAllBounded, ONE PAGE AT A TIME, so the whole list is never held: [onPage] gets each
     * page as it is read and returns false to stop reading (Crashlytics 63ec9068, an OOM loading a whole OpenGonic tree; the
     * user's decision). Same stops (a repeated continuation, [STREAM_CEILING]) and the same Concat and Suspend handling. A
     * PagedData.Single, which is what an extension that builds its whole list first returns, arrives as one page: the stream
     * can save nothing the extension already holds, but it never makes a second list of it.
     */
    suspend fun <T : Any> Feed<T>.forEachPageBounded(onPage: suspend (List<T>) -> Boolean) =
        walkFeed(this, Walk(STREAM_CEILING, onPage))

    suspend fun <T : Any> PagedData<T>.forEachPageBounded(onPage: suspend (List<T>) -> Boolean) {
        walkData(this, Walk(STREAM_CEILING, onPage))
    }

    /** One walk's state: what the ceiling still allows, what was taken, and where each page goes (false = stop reading). */
    private class Walk<T>(var left: Int, val onPage: suspend (List<T>) -> Boolean) {
        var taken = 0
    }

    private suspend fun <T : Any> walkFeed(feed: Feed<T>, walk: Walk<T>) {
        if (feed.tabs.isEmpty()) {
            walkData(feed.pagedDataOfFirst(), walk)
            return
        }
        for (tab in feed.notSortTabs) if (!walkData(feed.getPagedData(tab).pagedData, walk)) break
    }

    /** Hands [data]'s pages to [walk]. False once the walk must end: the ceiling reached (logged), or the consumer stopped. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> walkData(data: PagedData<T>, walk: Walk<T>): Boolean {
        when (data) {
            is PagedData.Concat -> {
                val sources = runCatching { concatSources?.get(data) as? Array<PagedData<T>> }.getOrNull()
                if (sources != null) {
                    for (source in sources) if (!walkData(source, walk)) return false
                    return true
                }
            }

            is PagedData.Suspend -> {
                val inner = innerOf(data)
                if (inner != null) return walkData(inner, walk)
            }

            else -> {}
        }
        return walkPages(data, walk)
    }

    // Suspend's inner data, created as Suspend.data() creates it: its first loadPage runs the getter once and keeps the result,
    // which loadAll would have done too; that first page is cached by the inner data, so the walk below does not load it twice.
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> innerOf(data: PagedData.Suspend<T>): PagedData<T>? {
        val field = suspendData ?: return null
        val known = runCatching { field.get(data) as? PagedData<T> }.getOrNull()
        if (known != null) return known
        data.loadPage(null)
        return runCatching { field.get(data) as? PagedData<T> }.getOrNull()
    }

    private suspend fun <T : Any> walkPages(data: PagedData<T>, walk: Walk<T>): Boolean {
        val used = hashSetOf<String>()
        var pages = 0
        var key: String? = null
        while (true) {
            val page = data.loadPage(key)
            pages++
            // [2026-11-13] A COPY, TAKEN BEFORE ANYTHING ELSE CAN RUN. PagedData.Single hands back its own `items` list as the page
            // (Single.loadListInternal -> loadList, :common) and its invalidate()/clear() empties that same list. This walk never
            // invalidates, and a load-on-tap feed is a fresh loadTracks result no screen holds; but a folder's own feed can be on
            // screen, where PagedSource.getRefreshKey invalidates it on the main thread while this runs on IO. The copy is of
            // references only, so the consumer always iterates a stable list.
            val snapshot = page.data.toList()
            val over = snapshot.size > walk.left || (snapshot.size == walk.left && page.continuation != null)
            val items = if (snapshot.size > walk.left) snapshot.take(walk.left) else snapshot
            walk.left -= items.size
            walk.taken += items.size
            if (!walk.onPage(items)) return false
            if (over) {
                stopped("ceiling", walk.taken, pages, data)
                return false
            }
            val next = page.continuation ?: return true
            // Ends this source only: a Concat's later sources and a Feed's later tabs still load, as they would under loadAll.
            if (!used.add(next)) {
                stopped("repeat", walk.taken, pages, data)
                return true
            }
            key = next
        }
    }

    private fun stopped(reason: String, items: Int, pages: Int, data: PagedData<*>) {
        Log.w(TAG, "stopped reason=$reason items=$items pages=$pages source=${data::class.java.simpleName}")
    }
}
