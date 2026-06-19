package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

class DeezerSearchClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val scope: CoroutineScope, private val history: Boolean, private val parser: DeezerParser) {

    @Volatile
    private var oldSearch: Triple<String, List<Shelf>, JsonObject?>? = null

    private fun JsonArray?.toQueryList(key: String, historyFlag: Boolean) =
        this?.mapNotNull { item ->
            item.jsonObject[key]?.jsonPrimitive?.content?.let { QuickSearchItem.Query(it, historyFlag) }
        } ?: emptyList()

    suspend fun quickSearch(query: String): List<QuickSearchItem.Query> {
        deezerExtension.handleArlExpiration()
        return if (query.isBlank()) {
            val jsonObject = api.getSearchHistory()
            val resultObject = jsonObject["results"]!!.jsonObject
            val searchObject = resultObject["SEARCH_HISTORY"]?.jsonObject
            val dataArray = searchObject?.get("data")?.jsonArray
            val trendingObject = resultObject["TRENDING_QUERIES"]?.jsonObject
            val dataTrendingArray = trendingObject?.get("data")?.jsonArray
            dataArray.toQueryList("query", true) + dataTrendingArray.toQueryList("QUERY", false)
        } else {
            runCatching {
                val jsonObject = api.searchSuggestions(query)
                val resultObject = jsonObject["results"]?.jsonObject
                val suggestionArray = resultObject?.get("SUGGESTION")?.jsonArray
                suggestionArray.toQueryList("QUERY", false)
            }.getOrElse {
                emptyList()
            }
        }
    }

    suspend fun loadSearchFeed(query: String, shelf: String): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return browseFeed(shelf).toFeed() }

        if (history) {
            scope.launch { runCatching { api.setSearchHistory(query) } }
        }

        return Feed(loadSearchFeedTabs(query)) { tab ->
            if (tab?.id == "TOP_RESULT") return@Feed emptyList<Shelf>().toFeedData()

            val cached = oldSearch?.takeIf { it.first == query }

            if (tab?.id == "All") {
                return@Feed cached?.second?.toFeedData() ?: emptyList<Shelf>().toFeedData()
            }

            val resultObject = cached?.third
                ?: api.search(query)["results"]?.jsonObject

            val dataArray = resultObject?.get(tab?.id ?: "")?.jsonObject?.get("data")?.jsonArray

            return@Feed dataArray?.mapNotNull { item ->
                parser.run { item.jsonObject.toEchoMediaItem()?.toShelf() }
            }.orEmpty().toFeedData()
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun browseFeed(shelf: String): List<Shelf> {
        deezerExtension.handleArlExpiration()
        api.updateCountry()
        val jsonObject = api.page("channels")
        val data = jsonObject["results"]?.jsonObject?.get("DATA")?.jsonObject ?: return emptyList()
        val modules = data["MODULES"]?.jsonArray ?: JsonArray(emptyList())

        val listType = if ("grid" in shelf) Shelf.Lists.Type.Grid else Shelf.Lists.Type.Linear

        return modules.mapNotNull { module ->
            val moduleObj = module.jsonObject
            val moduleTitle = moduleObj.str("TITLE") ?: return@mapNotNull null
            val items = moduleObj["ITEMS"]?.jsonArray ?: JsonArray(emptyList())

            val categories = items.mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj.str("TARGET_ID") ?: return@mapNotNull null
                val title = obj.str("TITLE") ?: return@mapNotNull null
                val md5 = obj.str("PICTURE_MD5")
                val picType = obj.str("PICTURE_TYPE")
                val backgroundColor = obj.str("BACKGROUND_COLOR")
                parser.run {
                    Shelf.Category(
                        id = id,
                        title = title,
                        image = getCover(md5, picType),
                        backgroundColor = backgroundColor,
                        feed = Feed(emptyList()) { deezerExtension.channelFeed(id).toFeedData() }
                    )
                }
            }
            categories.takeIf { it.isNotEmpty() }?.let {
                Shelf.Lists.Categories(
                    id = moduleTitle,
                    title = moduleTitle,
                    list = it,
                    type = listType,
                    more = it.toFeed()
                )
            }
        }
    }

    suspend fun loadSearchFeedTabs(query: String): List<Tab> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return emptyList() }

        val jsonObject = api.search(query)
        val resultObject = jsonObject["results"]?.jsonObject
        val orderObject = resultObject?.get("ORDER")?.jsonArray

        val tabs = orderObject?.mapNotNull { tab ->
            val tabId = tab.jsonPrimitive.content
            if (tabId !in SKIP_TAB_IDS) {
                Tab(
                    tabId,
                    tabId.lowercase()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
            } else {
                null
            }
        } ?: emptyList()

        val allShelves = tabs.mapNotNull { tab ->
            val name = tab.id
            val tabObject = resultObject?.get(name)?.jsonObject
            val dataArray = tabObject?.get("data")?.jsonArray
            parser.run {
                dataArray?.toShelfItemsList(
                    name.lowercase()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
            }
        }
        oldSearch = Triple(query, allShelves, resultObject)
        return listOf(Tab("All", "All")) + tabs
    }

    companion object {
        private val SKIP_TAB_IDS =
            setOf("TOP_RESULT", "FLOW_CONFIG", "LIVESTREAM", "RADIO", "LYRICS", "CHANNEL", "USER")
    }
}