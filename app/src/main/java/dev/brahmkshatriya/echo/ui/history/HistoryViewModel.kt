package dev.brahmkshatriya.echo.ui.history

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.history.db.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class HistoryViewModel(
    private val app: App,
    private val repository: HistoryRepository,
    val extensionLoader: ExtensionLoader,
) : ViewModel() {

    enum class SortOption(@StringRes val title: Int) {
        ByDate(R.string.sort_date),
        ByTitle(R.string.sort_title),
        ByArtist(R.string.artists),
    }

    data class SortState(
        val sortOption: SortOption = SortOption.ByDate,
        val reversed: Boolean = false,
        val save: Boolean = false,
    )

    private val prefs = app.getSharedPreferences("history_sort", Context.MODE_PRIVATE)

    private fun loadSortState(): SortState {
        if (!prefs.getBoolean("saved", false)) return SortState()
        val option = prefs.getString("sort_option", null)
            ?.let { runCatching { SortOption.valueOf(it) }.getOrNull() }
            ?: SortOption.ByDate
        val reversed = prefs.getBoolean("reversed", false)
        return SortState(option, reversed, save = true)
    }

    val sortState = MutableStateFlow(loadSortState())
    val extensionFilter = MutableStateFlow<String?>(null)
    val searchQuery = MutableStateFlow<String?>(null)

    fun applySortState(state: SortState) {
        sortState.value = state
        if (state.save) {
            prefs.edit()
                .putBoolean("saved", true)
                .putString("sort_option", state.sortOption.name)
                .putBoolean("reversed", state.reversed)
                .apply()
        } else {
            prefs.edit().clear().apply()
        }
    }

    val history = combine(
        repository.getHistory(),
        sortState,
        extensionFilter,
        searchQuery,
        extensionLoader.music,
    ) { list, sort, extFilter, query, extensions ->
        val extensionNames = extensions.associate { it.id to it.metadata.name }
        fun extName(id: String) = extensionNames[id] ?: id

        var items = list

        if (extFilter != null) {
            items = items.filter { it.extensionId == extFilter }
        }

        if (!query.isNullOrBlank()) {
            val q = query.lowercase()
            items = items.filter { entity ->
                val track = entity.track
                track?.title?.lowercase()?.contains(q) == true ||
                    track?.artists?.any { a -> a.name?.lowercase()?.contains(q) == true } == true
            }
        }

        val sorted = when (sort.sortOption) {
            SortOption.ByDate -> items
            SortOption.ByTitle -> items.sortedBy { it.track?.title?.lowercase() ?: "" }
            SortOption.ByArtist -> items.sortedBy {
                it.track?.artists?.firstOrNull()?.name?.lowercase() ?: ""
            }
        }
        val result = if (sort.reversed) sorted.reversed() else sorted

        if (sort.sortOption == SortOption.ByDate) {
            buildDateGrouped(result, sort.reversed, ::extName)
        } else {
            result.map { HistoryListItem.Item(it, extName(it.extensionId)) }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun buildDateGrouped(
        list: List<HistoryEntity>,
        reversed: Boolean,
        extName: (String) -> String,
    ): List<HistoryListItem> {
        val now = System.currentTimeMillis()
        val todayStart = startOfDay(now)
        val yesterdayStart = todayStart - TimeUnit.DAYS.toMillis(1)
        val weekStart = todayStart - TimeUnit.DAYS.toMillis(7)
        val monthStart = todayStart - TimeUnit.DAYS.toMillis(30)

        val today = list.filter { it.playedAt >= todayStart }
        val yesterday = list.filter { it.playedAt >= yesterdayStart && it.playedAt < todayStart }
        val thisWeek = list.filter { it.playedAt >= weekStart && it.playedAt < yesterdayStart }
        val thisMonth = list.filter { it.playedAt >= monthStart && it.playedAt < weekStart }
        val earlier = list.filter { it.playedAt < monthStart }

        fun MutableList<HistoryListItem>.addSection(label: String, items: List<HistoryEntity>) {
            if (items.isNotEmpty()) {
                add(HistoryListItem.Header(label))
                items.forEach { add(HistoryListItem.Item(it, extName(it.extensionId))) }
            }
        }

        return buildList {
            if (reversed) {
                addSection("Earlier", earlier)
                addSection("This Month", thisMonth)
                addSection("This Week", thisWeek)
                addSection("Yesterday", yesterday)
                addSection("Today", today)
            } else {
                addSection("Today", today)
                addSection("Yesterday", yesterday)
                addSection("This Week", thisWeek)
                addSection("This Month", thisMonth)
                addSection("Earlier", earlier)
            }
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshingFlow = _isRefreshing.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            delay(500)
            _isRefreshing.value = false
        }
    }

    fun clearHistory() = viewModelScope.launch { repository.clearHistory() }

    private fun startOfDay(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
