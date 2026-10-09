package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DeezerSearchClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val scope: CoroutineScope, private val history: Boolean, private val parser: DeezerParser) {

    // ⚠⚠ QUERY-KEYED LRU, REPLACING A SINGLE SLOT - AND THE SINGLE SLOT'S FAILURE WAS SILENT
    // EMPTINESS, NOT STALENESS. It was `Triple(query, shelves, results)` guarded by `takeIf { it.first
    // == query }`, so any second Deezer search between loadSearchFeed and getPagedData overwrote it and
    // the lookup missed. Three paths can do that concurrently, none serialised:
    //   StreamableMediaSource:80 -> StreamableLoader:51 queue PRELOAD of the next item while one plays
    //   Cached:370                 any other track resolution (detail page, AA browse, radio append)
    //   SearchFragment:102         the user typing while any of the above runs
    // StreamableLoader.load is a bare `withContext(Dispatchers.IO)` with no mutex and one shared
    // instance (StreamableMediaSource:213), so two resolutions really can be in flight at once.
    // ⚠️ WHY A MISS WAS WORSE THAN A REFETCH: the "All" branch had no fallback and returned an
    // EMPTY list, while named tabs re-ran api.search. So a clash did not make search slow, it made All
    // silently empty - and All is the only tab a wrapper extension reads (it takes tabs.firstOrNull()).
    // ⚠️ SHAPE COPIED FROM PlayerRadio's stationSeeds: LinkedHashMap, access order maintained by
    // remove-then-reinsert, eviction from the front. Capped because each value holds a whole pageSearch
    // response (tens of KB) - 4 covers one preload plus one user search plus the item being resolved,
    // which is the concurrency actually observed above; unbounded would be a real cost on a browse
    // session, for the same reason recorded at Cached's album-payload note.
    // Keyed on the QUERY alone: all tabs derive from one response, so a query+tab key would store the
    // same payload several times.
    private val searchCache = LinkedHashMap<String, Pair<List<Shelf>, JsonObject?>>()

    private fun cachedSearch(query: String): Pair<List<Shelf>, JsonObject?>? =
        synchronized(searchCache) {
            searchCache.remove(query)?.also { searchCache[query] = it }
        }

    private fun cacheSearch(query: String, shelves: List<Shelf>, results: JsonObject?) {
        synchronized(searchCache) {
            searchCache.remove(query)
            searchCache[query] = shelves to results
            while (searchCache.size > SEARCH_CACHE_CAP) {
                searchCache.remove(searchCache.keys.first())
            }
        }
    }

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

    suspend fun loadSearchFeed(
        query: String, shelf: String, isUserInitiated: Boolean = true
    ): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return browseFeed(shelf).toFeed() }

        // ⚠⚠ TWO CONDITIONS, AND THEY MEAN DIFFERENT THINGS — DO NOT COLLAPSE THEM.
        //   `history`         — the USER'S SETTING. Master switch: off means never record, ever.
        //   `isUserInitiated` — WHETHER THIS PARTICULAR SEARCH WAS A GESTURE. Narrows WHICH searches count.
        // This write goes to the user's Deezer ACCOUNT (user.addEntryInSearchHistory), not to a local list,
        // so it is visible in Deezer's own app and is not clearable per-entry from here.
        // WHAT WENT WRONG WITHOUT THE SECOND CONDITION (device, 2026-09-09): the app's endless-queue radio
        // fallback searches the catalogue once per Last.fm candidate — up to fifteen per exhausted station,
        // matched or not — and every one became a "Recent" entry, pushing the user's own searches out.
        // Nothing was wrong with this code; it simply could not tell the two apart.
        if (history && isUserInitiated) {
            scope.launch { runCatching { api.setSearchHistory(query) } }
        }

        return Feed(loadSearchFeedTabs(query)) { tab ->
            if (tab?.id == "TOP_RESULT") return@Feed emptyList<Shelf>().toFeedData()

            // ⚠⚠ BOTH MISSES REBUILD THROUGH loadSearchFeedTabs, NOT THROUGH api.search - AND THAT
            // IS THE WHOLE POINT. loadSearchFeedTabs is the one place that splices pipe's tracks into
            // results["TRACK"] (see withPipeTracks), so a fallback that called api.search directly would
            // hand back GATEWAY-ONLY results: the Tracks tab would quietly lose its pipe rows on any cache
            // miss, which is the defect this whole path exists to fix, re-created by its own fallback.
            // ⚠️ THE NAMED-TAB BRANCH HAD EXACTLY THAT BUG - it read
            // `?: api.search(query)["results"]` - so it is fixed here too, not only the All branch.
            // ⚠️ CALLING loadSearchFeedTabs REBUILDS THE TABS AND THROWS THEM AWAY, and that cost is
            // accepted deliberately: it is one wasted list construction on a path that is already doing a
            // network round trip, and it guarantees ONE code path rather than two that must be kept in
            // step. Its side effect of re-populating the cache is wanted - the next tab in the same feed
            // then hits.
            val cached = cachedSearch(query)
                ?: run { loadSearchFeedTabs(query); cachedSearch(query) }

            if (tab?.id == "All") {
                return@Feed cached?.first.orEmpty().toFeedData()
            }

            val resultObject = cached?.second

            val dataArray = resultObject?.get(tab?.id ?: "")?.jsonObject?.get("data")?.jsonArray

            return@Feed dataArray?.mapNotNull { item ->
                parser.run { item.jsonObject.toEchoMediaItem()?.toShelf() }
            }.orEmpty().toFeedData()
        }
    }

    private fun JsonObject.toBrowseShelves(shelf: String): List<Shelf> {
        val browsePageResults = this["results"]!!.jsonObject
        val browseSections = browsePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())
        return browseSections.mapNotNull { section ->
            val id = section.jsonObject["module_id"]!!.jsonPrimitive.content
            if (id == GO_BEYOND_STREAMING_MODULE_ID) return@mapNotNull null
            val layout = section.jsonObject["layout"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = section.jsonObject["title"]?.jsonPrimitive?.content.orEmpty()
            // ⚠⚠ THE `title == "?"` CLAUSE IS UNRESOLVED - NOT PROVEN DEAD, NOT PROVEN
            // LOAD-BEARING - AND THE LOG CANNOT SETTLE IT BY CONSTRUCTION. logSections renders an ABSENT
            // field, a JSON-null field and a literal "?" ALL AS `?`, because it reads via contentOrNull
            // and falls back to `?: "?"`. So a `?` in a captured sections line is three payloads, not one.
            // ⚠️ AND `?` IN THE OTHER FIELDS IS THE SAME PLACEHOLDER, which is worth stating
            // because it looks like evidence and is not: layout/module_id/target all use `?: "?"` too, so
            // a line like `Explore all/grid/<id>/?` means TARGET WAS ABSENT - it is NOT proof that the
            // string "?" occurs anywhere in Deezer's payload.
            // WHAT THE 2026-09-12 CAPTURE DID NARROW: explore-tab returned SIX sections and yielded FIVE
            // shelves, so the `?`-titled section WAS dropped here. That rules out JSON null, because a
            // JSON-null title takes `.content` == the string "null" below - neither blank nor "?" - and
            // would have SURVIVED, rendering a shelf titled "null". Two cases remain and they map exactly
            // one-to-one onto the two clauses: ABSENT (caught by isBlank, so "?" is dead) or a LITERAL "?"
            // (caught by this clause, so it is load-bearing). The capture cannot separate them.
            // ⚠️ LATENT HAZARD, RECORDED THOUGH IT DID NOT FIRE: a JSON-null title still slips
            // through both clauses and renders a shelf named "null". `.content` on JsonNull is "null",
            // and `.orEmpty()` only covers the key being ABSENT.
            // ONE LINE WOULD SETTLE ALL OF IT - change logSections' `?: "?"` to a distinguishable
            // placeholder such as `?: "<absent>"`. Not done: the clause is harmless either way and this
            // is recorded rather than chased.
            if (title.isBlank() || title == "?") return@mapNotNull null
            when {
                id == EXPLORE_MODULE_ID || layout == "grid" -> {
                    parser.run {
                        section.toShelfCategoryList(title, shelf) { target ->
                           deezerExtension.channelFeed(target)
                        }
                    }.takeIf { it.list.isNotEmpty() }
                }

                else -> {
                    parser.run {
                        val secShelf =
                            section.toShelfItemsList(title) as? Shelf.Lists.Items
                                ?: return@run null
                        val list = secShelf.list
                        Shelf.Lists.Items(
                            id = secShelf.id,
                            title = secShelf.title,
                            subtitle = secShelf.subtitle,
                            type = Shelf.Lists.Type.Linear,
                            more = PagedData.Single<Shelf> {
                                list.map {
                                    it.toShelf()
                                }
                            }.toFeed(),
                            list = list
                        )
                    }
                }
            }
        }
    }

    private suspend fun browseFeed(shelf: String): List<Shelf> {
        // ⚠⚠ THE LOGIN CHECK AND THE COUNTRY PUSH ARE SPLIT, AND THE SPLIT IS THE WHOLE POINT.
        // handleArlExpiration keeps THIS EXACT try/catch - log then `throw e` - character for
        // character, so its propagation is unchanged: a ClientException.LoginRequired still reaches the
        // host and still produces the sign-in prompt, and the "Search ERROR" line it has logged since
        // 9d33d55c still appears. 1108's lockout came from changing how Deezer's login-check errors
        // propagate; nothing here touches that path.
        try {
            deezerExtension.handleArlExpiration()
        } catch (e: Exception) {
            println("GladixDeezer Search ERROR: ${e.message}")
            throw e
        }
        // ⚠⚠ THE COUNTRY PUSH IS BEST-EFFORT: IT IS A SIDE CALL AND MUST NOT BREAK THE LOAD.
        // It sets an account PREFERENCE and contributes nothing to the shelves built below, yet a
        // failure used to fail the whole browse feed.
        // ⚠️ AND THE RETHROW IT REPLACES WAS NEVER A DECISION - checked with git log -S before
        // changing it. Both calls were BARE until 9d33d55c (2026-06-20), "chore(room): migrate to Room
        // 3.0.0-rc01 + improve diagnostic logging": that commit added the try/catch to attach the
        // println, and `throw e` was there so the logging wrapper changed nothing. Propagation was the
        // ABSENCE of a catch, never a choice - every other edit in that hunk is logging.
        // ⚠️ WHY THIS MATTERS NOW: callApi throws DeezerGatewayException on a non-empty gateway
        // `error` object, and an unlisted RECOMMENDATION_COUNTRY is a candidate trigger - see
        // DeezerCountries.resolveApiCountry. If App.kt's deezer_gateway key ever reports
        // `gw=user.updateRecommendationCountry`, this is the call it means.
        // ⚠️ CancellationException IS RETHROWN: runCatching catches Throwable, and swallowing a
        // cancellation here would break coroutine cancellation for the whole feed load. Same guard the
        // module already uses at DeezerTrackClient.loadStreamableMedia.
        runCatching { api.updateCountry() }.onFailure {
            if (it is CancellationException) throw it
            println("GladixDeezer Search: updateCountry failed (continuing): ${it.message}")
        }

        val (searchHomePipeShelves, exploreTabShelves) = coroutineScope {
            val searchHomePipe = async {
                runCatching { withTimeout(5000) { api.page("channels/search-home-pipe") } }
                    .onSuccess { logSections("search-home-pipe", it) }
                    .onFailure { println("GladixDeezer PAGE[channels/search-home-pipe] ERROR: ${it.message}") }
                    .getOrNull()?.toBrowseShelves(shelf) ?: emptyList()
            }
            val exploreTab = async {
                runCatching { withTimeout(5000) { api.page("channels/explore/explore-tab") } }
                    .onSuccess { logSections("explore-tab", it) }
                    .onFailure { println("GladixDeezer PAGE[channels/explore/explore-tab] ERROR: ${it.message}") }
                    .getOrNull()?.toBrowseShelves(shelf) ?: emptyList()
            }
            searchHomePipe.await() to exploreTab.await()
        }

        // ⚠⚠ CLOSED 2026-09-12 AS LOAD-BEARING - channels/search-home-pipe STAYS, AND THE
        // "if it proves reliably empty, it can go too" NOTE IS RETIRED RATHER THAN LEFT STANDING.
        // MEASURED, one Search-tab open:
        //     BROWSE shelves: search-home-pipe=2 explore-tab=5
        // Two shelves is not merely non-zero, it is the RIGHT two. The sections line for the same fetch:
        //     PAGE[search-home-pipe] sections: Go beyond streaming/grid, Genres/grid, Categories/grid
        // THREE sections arrive, toBrowseShelves drops GO_BEYOND_STREAMING_MODULE_ID, Genres and
        // Categories survive - 3 -> 2 exactly as designed. So the survivors are precisely the two shelves
        // this endpoint was switched in FOR (the official Search tab's genre/category grid), which is a
        // stronger result than a count alone: it shows the FILTER is right, not just the arithmetic.
        // The temporary BROWSE probe that produced this has been removed, its removal condition met.
        // ⚠️ CLOSED 2026-09-12, DO NOT RE-OPEN: channels/explore/explore-tab IS NOT UNTESTED.
        // It was investigated twice with temporary logging and DELIBERATELY DEMOTED. Confirmed then: it
        // returns personalized content ("Dig deeper", "Evening chill") rather than the Genres/Categories
        // grid of Deezer's official Search tab, plus EXPLORE_MODULE_ID ("Explore all").
        // ⚠⚠ [CORRECTED 2026-09-12] THAT SESSION RECORDED "EXACTLY 5 SECTIONS". IT NOW RETURNS
        // SIX (?-titled, Celine Dion slideshow, Albums of the week, This week's freshest releases, Feeling
        // French?, Explore all) yielding five shelves. Not a defect - a personalized feed's section count
        // is Deezer's to change - but it is the SECOND count from that era to have moved, so
        // DO NOT KEY ANY REASONING ON A SECTION COUNT FROM THE RECORD; re-measure it. An inference built
        // on the stale 5 was made and corrected in the same exchange, which is how this was noticed.
        // The endpoint's CHARACTER - personalized rather than a genre grid - is what has held up, and that
        // is the part the demotion rests on. It is CORRECT PER ITS OWN CONTRACT; it is simply a personalized
        // browse feed rather than a genre grid. That is why channels/search-home-pipe became the primary
        // source and this one is appended BELOW it rather than removed. Ordering in the sum below is that
        // decision, not an accident - do not reorder it, and do not re-investigate this endpoint as though
        // its behaviour were unknown.
        return searchHomePipeShelves + exploreTabShelves
    }

    /**
     * pageSearch's `results` with the TRACK section's `data` replaced by pipe GraphQL's track results.
     *
     * ⚠⚠ EVERY FAILURE PATH RETURNS `results` UNCHANGED, WHICH IS THE WHOLE CONTRACT: search must
     * never get WORSE than it is today. JWT exchange down, pipe refusing, GraphQL `errors`, a node we
     * cannot map, zero usable rows - all of them fall back to the gateway's own TRACK section, i.e.
     * exactly current behaviour. The only way this changes what a user sees is by ADDING tracks.
     * ⚠️ CancellationException IS RETHROWN BEFORE THE FALLBACK. runCatching catches Throwable, so
     * swallowing it here would launder a cancelled search into "pipe found nothing" and then do the
     * gateway work anyway on a scope that is already going away. Fifth instance of that pattern in this
     * project; the roll-call is in DeezerParser's note.
     *
     * ⚠️ GATED ON `ORDER` CONTAINING "TRACK", AND ORDER IS NEVER MODIFIED. Tabs are built from
     * ORDER, so a TRACK id that is not in ORDER has no tab to appear in and splicing it would write rows
     * nobody can reach. STATED CONSEQUENCE: for a query where Deezer returns no TRACK section at all,
     * pipe results do not surface. Measured 2026-10-09 that the failing queries DO carry TRACK with an
     * empty data array, so this is an edge case rather than the main path - but if a report ever says
     * "pipe tracks missing for query X", check ORDER for X first.
     * ⚠️ The section OBJECT is rebuilt rather than mutated (JsonObject is immutable) and its
     * siblings are preserved, so anything else reading TRACK's `count`/`total` stays consistent.
     */
    private suspend fun withPipeTracks(results: JsonObject, query: String): JsonObject {
        val order = (results["ORDER"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: return results
        if (order.none { it.equals("TRACK", ignoreCase = true) }) return results
        val data = runCatching { api.searchTracksPipe(query) }
            .getOrElse { if (it is CancellationException) throw it else null }
            ?: return results
        if (data.isEmpty()) return results
        val existing = results["TRACK"] as? JsonObject
        val section = buildJsonObject {
            existing?.forEach { (key, value) -> if (key != "data") put(key, value) }
            put("data", data)
            put("count", data.size)
            put("total", data.size)
        }
        return JsonObject(results + ("TRACK" to section))
    }

    suspend fun loadSearchFeedTabs(query: String): List<Tab> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return emptyList() }

        val jsonObject = api.search(query)
        // ⚠⚠ THE PIPE SPLICE GOES HERE AND NOWHERE ELSE - THIS IS THE SINGLE POINT BOTH
        // CONSUMERS READ. `resultObject` feeds (1) `allShelves` below, which is what the "All" tab
        // serves, and (2) the cached response (searchCache's second component), which loadSearchFeed's
        // per-tab lambda reads for every named tab - and which that lambda also REBUILDS through this same
        // function on a cache miss, so there is exactly one splice site no matter how the data is reached.
        // Replacing TRACK's data here fixes both from one edit; doing it in the lambda instead would
        // leave "All" with no Tracks shelf - and "All" is the tab CombineExtension reads, because it takes
        // `feed.tabs.firstOrNull()` and ours is Tab("All", "All").
        val resultObject = jsonObject["results"]?.jsonObject?.let { withPipeTracks(it, query) }
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
        cacheSearch(query, allShelves, resultObject)
        return listOf(Tab("All", "All")) + tabs
    }

    companion object {
        private fun logSections(label: String, page: JsonObject) {
            val sections = page["results"]?.jsonObject?.get("sections")?.jsonArray ?: JsonArray(emptyList())
            val summary = sections.joinToString(", ") { section ->
                val obj = section.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "?"
                val layout = obj["layout"]?.jsonPrimitive?.contentOrNull ?: "?"
                val moduleId = obj["module_id"]?.jsonPrimitive?.contentOrNull ?: "?"
                val target = obj["target"]?.jsonPrimitive?.contentOrNull ?: "?"
                "$title/$layout/$moduleId/$target"
            }
            println("GladixDeezer PAGE[$label] sections: $summary")
        }

        // 4 covers one queue preload plus one user search plus the item being resolved - the
        // concurrency named at searchCache. Each entry holds a whole pageSearch response.
        private const val SEARCH_CACHE_CAP = 4

        private val SKIP_TAB_IDS =
            setOf("TOP_RESULT", "FLOW_CONFIG", "LIVESTREAM", "RADIO", "LYRICS", "CHANNEL", "USER")

        private const val EXPLORE_MODULE_ID = "8b2c6465-874d-4752-a978-1637ca0227b5"
        private const val GO_BEYOND_STREAMING_MODULE_ID = "20748fc9-bf55-41e6-a50f-2b26c0c8da48"
    }
}