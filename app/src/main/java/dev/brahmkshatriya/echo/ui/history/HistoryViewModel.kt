package dev.brahmkshatriya.echo.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.history.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class HistoryViewModel(
    private val repository: HistoryRepository,
    private val extensionLoader: ExtensionLoader,
) : ViewModel() {

    val history = repository.getHistory()
        .map { list ->
            val now = System.currentTimeMillis()
            val todayStart = startOfDay(now)
            val yesterdayStart = todayStart - TimeUnit.DAYS.toMillis(1)
            val weekStart = todayStart - TimeUnit.DAYS.toMillis(7)
            val monthStart = todayStart - TimeUnit.DAYS.toMillis(30)

            val extensionNames = extensionLoader.music.value
                .associate { it.id to it.metadata.name }
            fun extName(id: String) = extensionNames[id] ?: id

            val today = list.filter { it.playedAt >= todayStart }
            val yesterday = list.filter { it.playedAt >= yesterdayStart && it.playedAt < todayStart }
            val thisWeek = list.filter { it.playedAt >= weekStart && it.playedAt < yesterdayStart }
            val thisMonth = list.filter { it.playedAt >= monthStart && it.playedAt < weekStart }
            val earlier = list.filter { it.playedAt < monthStart }

            buildList {
                if (today.isNotEmpty()) {
                    add(HistoryListItem.Header("Today"))
                    today.forEach { add(HistoryListItem.Item(it, extName(it.extensionId))) }
                }
                if (yesterday.isNotEmpty()) {
                    add(HistoryListItem.Header("Yesterday"))
                    yesterday.forEach { add(HistoryListItem.Item(it, extName(it.extensionId))) }
                }
                if (thisWeek.isNotEmpty()) {
                    add(HistoryListItem.Header("This Week"))
                    thisWeek.forEach { add(HistoryListItem.Item(it, extName(it.extensionId))) }
                }
                if (thisMonth.isNotEmpty()) {
                    add(HistoryListItem.Header("This Month"))
                    thisMonth.forEach { add(HistoryListItem.Item(it, extName(it.extensionId))) }
                }
                if (earlier.isNotEmpty()) {
                    add(HistoryListItem.Header("Earlier"))
                    earlier.forEach { add(HistoryListItem.Item(it, extName(it.extensionId))) }
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
