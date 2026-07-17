package dev.brahmkshatriya.echo.ui.common

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.brahmkshatriya.echo.common.helpers.PagedData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

class PagedSource<T : Any>(
    private val loaded: Result<PagedData<T>>?,
    private val cached: Result<PagedData<T>>? = null
) : PagingSource<String, T>() {

    val flow = Pager(
        PagingConfig(
            pageSize = 10,
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

    override suspend fun load(params: LoadParams<String>): LoadResult<String, T> {
        val key = params.key
        return runCatching {
            val page = loaded?.getOrThrow()?.loadPage(key) ?: throw LoadingException()
            LoadResult.Page(page.data, key, page.continuation)
        }.getOrElse { error ->
            val cachedPage = cached?.mapCatching { it.loadPage(key) }?.getOrNull()
            return if (cachedPage == null || cachedPage.data.isEmpty()) LoadResult.Error(error)
            else LoadResult.Page(cachedPage.data, key, cachedPage.continuation)
        }
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