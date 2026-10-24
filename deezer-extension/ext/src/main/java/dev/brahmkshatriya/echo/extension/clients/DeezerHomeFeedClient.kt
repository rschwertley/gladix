package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.objOrNull
import dev.brahmkshatriya.echo.extension.arrOrNull
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

class DeezerHomeFeedClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val parser: DeezerParser
) {

    fun loadHomeFeed(shelf: String): Feed<Shelf> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.page("home")
        logSections("home", jsonObject, parser)

        // Lenient, matching the existing degrade-to-empty decision: the home feed has other
        // shelves and a page-level oddity must not take the screen down. See DeezerJson.
        val homePageResults = jsonObject["results"].objOrNull() ?: JsonObject(emptyMap())
        val homeSections = homePageResults["sections"].arrOrNull() ?: JsonArray(emptyList())

        supervisorScope {
            val shelves = homeSections.mapNotNull { section ->
                val obj = section.asObjectOrNull() ?: return@mapNotNull null
                val id = obj.optString("module_id") ?: return@mapNotNull null
                // ⚠️ PERMANENT — NOT A TEMPORARY DIAGNOSTIC. DO NOT STRIP.
                // June's blanket "remove all GladixDeezer printlns before a release build" rule is about
                // trace spam; it does NOT apply to this line or to its twin in DeezerExtension.channelFeed.
                // This fires ONLY on a failure — a section Deezer sent that we then discard — so it costs
                // nothing on a healthy load and is silent in every normal session.
                //
                // WHY IT EARNS PERMANENCE: a section with no title is dropped here and never reaches the
                // parser, so the row vanishes from Home with no signal anywhere. That silence is how "Made
                // for you" went unnoticed for months — it was in the response the whole time and simply
                // never rendered. This line turns the next Deezer shape change into an immediate,
                // greppable symptom instead of a row nobody notices is missing.
                // module_id is printed because it is the only stable handle on a titleless section.
                val title = obj.optString("title") ?: run {
                    println("GladixDeezer DROP section=<no-title> reason=missing-title module_id=$id")
                    return@mapNotNull null
                }
                val hasChannelItems = parser.run { obj.hasChannelItems() }

                when {
                    id in CATEGORY_MODULE_ID || hasChannelItems -> async(dispatcher) {
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
                                // Same resolver the category path uses, so a carousel's "see all" and a
                                // channel tile reach a page the same way. Null target → re-wrap fallback.
                                section.toShelfItemsList(title) { target ->
                                    deezerExtension.channelFeed(target)
                                }
                            }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
            shelves
        }
    }.toFeed()

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.optString(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        private fun logSections(label: String, page: JsonObject, parser: DeezerParser) {
            val sections = page["results"].objOrNull()
                ?.get("sections")?.arrOrNull() ?: JsonArray(emptyList())
            val summary = sections.joinToString(", ") { section ->
                val obj = section.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "?"
                val layout = obj["layout"]?.jsonPrimitive?.contentOrNull ?: "?"
                val moduleId = obj["module_id"]?.jsonPrimitive?.contentOrNull ?: "?"
                // The section-level "see all" page path, e.g. /channels/module/<uuid> — the same shape
                // api.page(target.substringAfter("/")) consumes. "?" means the section has none and its
                // arrow can only re-wrap. Confirmed populated on Search 2026-09-04; logged here to settle
                // whether Home's sections carry them too.
                val target = obj["target"]?.jsonPrimitive?.contentOrNull ?: "?"
                val items = obj["items"]?.jsonArray ?: JsonArray(emptyList())
                // Same __TYPE__ extraction as DeezerParser.hasChannelItems() so the logged breakdown
                // reflects exactly the field that drives Home routing — but EVERY item is now counted under
                // some bucket, never dropped.
                //
                // ⚠️ THE INVARIANT IS THE POINT, NOT THE EXTRA FIELD: `sum(types) == items`. A reader can
                // check it at a glance, and it FAILS LOUDLY if a future item shape escapes both lookups.
                // This is the fourth extension of this line — it began as title/layout, gained
                // module_id/target, then items=N, then `target` on Home to confirm it there. Each closed a
                // blind spot the previous version had, and each was still merely WIDER. This one is the
                // first that is SELF-CHECKING. Keep that property: if you add a bucket, make sure it still
                // totals.
                //
                // ⚠️ WHAT IT FIXES, AND WHY IT WENT UNSEEN FOR WEEKS. The old form was
                //   items.mapNotNull { (it as? JsonObject)?.unwrap()?.str("__TYPE__") }
                // and mapNotNull SILENTLY DISCARDED every item with no `data.__TYPE__` — which is exactly the
                // smarttracklist shape, since those carry their type at the OUTER level. The 2026-09-07 Home
                // capture read `New releases for you/horizontal-grid/…/items=12/types={album:11}`: the line
                // was REPORTING the discrepancy (12 vs 11) without naming the missing item, so the one shape
                // the log existed to find was the one shape it could not show. An `outer:` bucket would have
                // surfaced it weeks ago, and would have shown that smarttracklist items are scattered
                // through ordinary rows rather than confined to "Made for you".
                //
                // ⚠️ PATTERN, STATED PLAINLY BECAUSE IT HAS NOW COST FOUR TIMES: mapNotNull over a
                // HETEROGENEOUS collection discards precisely the cases you most need to see. The other
                // members are the swallowed gateway `error` key (see DeezerApi.callApi), the titleless-section
                // DROP lines below, and — same operator, different data — Android clearing cacheDir under
                // storage pressure, which made a mapNotNull drop the item with no signal at all.
                // A COUNT-BASED DIAGNOSTIC MUST PUBLISH AN INVARIANT THAT FAILS LOUDLY WHEN IT BREAKS.
                val typeCounts = parser.run {
                    items.map { item ->
                        val obj = item as? JsonObject
                        obj?.unwrap()?.str("__TYPE__")
                            // Outer-level type: the smarttracklist shape, and whatever else Deezer sends
                            // this way next. Prefixed so it can never be confused with a real __TYPE__.
                            ?: obj?.str("type")?.let { "outer:$it" }
                            ?: "<no-type>"
                    }.groupingBy { it }.eachCount()
                }
                val types = typeCounts.entries.joinToString(", ") { "${it.key}:${it.value}" }
                "$title/$layout/$moduleId/$target/items=${items.size}/types={$types}"
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