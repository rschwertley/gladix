package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerHomeFeedClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val parser: DeezerParser
) {

    fun loadHomeFeed(shelf: String): Feed<Shelf> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.page("home")
        logSections("home", jsonObject)

        runCatching { withTimeout(5000) { api.page("channels/home-pipe") } }
            .onSuccess { logSections("channels/home-pipe", it) }
            .onFailure { println("GladixDeezer PAGE[channels/home-pipe] ERROR: ${it.message}") }

        val homePageResults = jsonObject["results"]?.jsonObject ?: JsonObject(emptyMap())
        val homeSections = homePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())

        supervisorScope {
            homeSections.mapNotNull { section ->
                val obj = section.asObjectOrNull() ?: return@mapNotNull null
                val id = obj.optString("module_id") ?: return@mapNotNull null
                val title = obj.optString("title") ?: return@mapNotNull null

                when (id) {
                    in CATEGORY_MODULE_ID -> async(dispatcher) {
                        runCatching {
                            parser.run {
                                section.toShelfCategoryList(title, shelf) { target ->
                                    deezerExtension.channelFeed(target)
                                }
                            }
                        }.getOrNull()
                    }
                    else -> async(dispatcher) {
                        runCatching {
                            parser.run {
                                section.toShelfItemsList(title)
                            }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }.toFeed()

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.optString(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        private fun logSections(label: String, page: JsonObject) {
            val sections = page["results"]?.jsonObject?.get("sections")?.jsonArray ?: JsonArray(emptyList())
            val summary = sections.joinToString(", ") { section ->
                val obj = section.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "?"
                val layout = obj["layout"]?.jsonPrimitive?.contentOrNull ?: "?"
                val moduleId = obj["module_id"]?.jsonPrimitive?.contentOrNull ?: "?"
                val itemCount = obj["items"]?.jsonArray?.size ?: 0
                "$title/$layout/$moduleId/items=$itemCount"
            }
            println("GladixDeezer PAGE[$label] sections: $summary")
        }

        private val dispatcher = Dispatchers.Default

        private val CATEGORY_MODULE_ID = setOf(
            // Free Users
            "868606eb-4afc-4e1a-b4e4-75b30da34ac8",
            // Premium Users
            "4f6321c0-21f5-474f-8156-9f6dd6222d7c"
        )
    }
}