package dev.brahmkshatriya.echo.ui.feed

import dev.brahmkshatriya.echo.common.models.Tab

/**
 * [2026-11-27] A TAB THAT SORTS AND SEARCHES ITSELF (the user's design, for a Library Songs tab too big to load whole, e.g.
 * 120,000+ songs paged from an on-device index). Opt-in, all through Tab.extras, so no :common change and no change for a tab
 * that declares neither key (Deezer and every tab today).
 *
 * THE TAB DECLARES
 * - [GLADIX_EXT_SORTS]: the sorts it can do, as comma-separated FeedSort NAMES (e.g. "Title,Artists,DateAdded"). Gladix's own
 *   names, not the extension's labels, so the saved sort per tab, the sort sheet, Reversed and the header chip all work as on any
 *   tab: the chip reads "Title", "Artists", "Recently added" (FeedSort.DateAdded's sort_date_added). Unknown names are ignored.
 *   The extension's default order is what it returns with no [GLADIX_SORT], and it must be the FIRST listed sort: a saved sort
 *   the tab does not list is not sent, and the header chip then names the first one (FeedData.extSourceData).
 * - [GLADIX_EXT_SORT_REVERSIBLE] = "1": the sheet offers Reversed. Without it the switch is hidden and Reversed is not sent.
 * - [GLADIX_EXT_SEARCH] = "1": the search box text goes to the extension. The box still shows only where the tab's own
 *   Feed.Buttons.showSearch says (the 1189 rule: search kept across tabs, applied only on tabs that show it).
 * - GLADIX_TRACKS_ON_TAP = "1" with [GLADIX_TAB_TRACKS_ID]: Play and Shuffle on this tab load the list by that id
 *   (FeedClickListener.onPlayClicked's Library arm, loadTracksOnTap), with the current sort and search.
 *
 * GLADIX SENDS, on a copy of the tab given to getPagedData (extParams): [GLADIX_EXT_V] = "1" on every load from the screen;
 * [GLADIX_SORT] = a declared FeedSort name, only when one is chosen; [GLADIX_REVERSED] = "1", only when on and declared;
 * [GLADIX_QUERY], only when there is a query and search is declared. A missing key means the default. A load with any of the
 * last three bypasses the disk cache (Cached.savingFeed), so a sorted or searched list never replaces the tab's saved first
 * page. Android Auto calls getPagedData with the plain tab (no keys at all), so it gets the default order, paged.
 *
 * WHAT GLADIX DOES NOT DO ON SUCH A TAB: its own sort and search over loaded rows (FeedData.getFeedSourceData's sort branch and
 * its MAX_SORT_SEARCH_ITEMS load). A sort or search change is a new load from page one (FeedData.extSourceData), and the
 * screen shows the extension's order as it comes (DisplayOrder.Source), which is also what a song tap pages through
 * (FeedClickListener.queueFromPage).
 *
 * NOT THROUGH UNIFIED (read 2026-11-27): Library shows Unified's own tabs (UnifiedExtension.loadLibraryFeed: All plus one plain
 * Tab(id, name) per extension), which carry none of these keys, so the contract never engages. The sub-extension's Songs tab is
 * loaded by UnifiedExtension.getFeedData only if it is its FIRST tab, as that extension's Library tab; a later tab is reachable
 * only from the "Tabs" row (show_tabs setting), as a page with no tab. Either way it takes the ordinary paths: Gladix's own sort
 * and search over the first MAX_SORT_SEARCH_ITEMS rows, Play queuing loaded rows, and a song tap starting Radio. Unified also
 * ignores the extras of the tab it is given, so the keys Gladix adds would not reach the sub-extension. Making it work needs
 * Unified to pass the sub-extension's tabs up as its own (a separate change, not made).
 * [CORRECTED 2026-11-27, same day] "Either way it takes the ordinary paths" no longer holds: UnifiedExtension.getFeedData now leaves
 * self-sorting tabs out, as the first tab loaded and from the Tabs row (the user's option b), and an extension whose tabs are all
 * self-sorting shows nothing there. So the 15,000-row load and the Radio-on-tap path cannot be reached through Unified; the list is
 * reachable only from the extension itself. The row stamping (Shelf.Category.withExtensionId, 1182) and RadioFallback are untouched.
 * DECIDED NOT TO BUILD option a (Unified passing the sub-extension's self-sorting tabs up as its own, so the tab works the same
 * inside Unified), because: it rewrites UnifiedExtension.loadLibraryFeed, calls every extension's loadLibraryFeed while building
 * the tab list (today only when a tab opens), needs a per-extension error catch so one failure cannot empty the Library, adds new
 * tab ids and new Android Auto Library nodes, and changes Unified's Library for every user of such an extension - all for a list
 * already reachable from the extension directly. REOPEN IT when a user browses this extension through Unified and wants its
 * Songs tab there.
 *
 * THE TAB'S EXTENSION MUST NOT SET Feed.Buttons.customTrackList (built eagerly by ButtonsAdapter and AA's saveDeclaredList) or
 * gladix_marks_tab (FeedData.runMarksPass loads the whole tab).
 */
const val GLADIX_EXT_SORTS = "gladix_ext_sorts"
const val GLADIX_EXT_SORT_REVERSIBLE = "gladix_ext_sort_reversible"
const val GLADIX_EXT_SEARCH = "gladix_ext_search"
const val GLADIX_TAB_TRACKS_ID = "gladix_tab_tracks_id"
const val GLADIX_EXT_V = "gladix_ext_v"
const val GLADIX_SORT = "gladix_sort"
const val GLADIX_REVERSED = "gladix_reversed"
const val GLADIX_QUERY = "gladix_query"

class ExtListing(val sorts: List<FeedSort>, val reversible: Boolean, val search: Boolean)

// Null for a tab that declares neither sorts nor search: every existing tab.
fun Tab?.extListing(): ExtListing? {
    val extras = this?.extras ?: return null
    val sorts = extras[GLADIX_EXT_SORTS]
    val search = extras[GLADIX_EXT_SEARCH] == "1"
    if (sorts == null && !search) return null
    return ExtListing(
        sorts?.split(',')?.mapNotNull { name -> FeedSort.entries.find { it.name == name.trim() } }.orEmpty(),
        extras[GLADIX_EXT_SORT_REVERSIBLE] == "1",
        search,
    )
}

fun ExtListing.extParams(state: FeedSort.State?, query: String?): Map<String, String> = buildMap {
    put(GLADIX_EXT_V, "1")
    state?.feedSort?.takeIf { it in sorts }?.let { put(GLADIX_SORT, it.name) }
    if (reversible && state?.reversed == true) put(GLADIX_REVERSED, "1")
    if (search && !query.isNullOrBlank()) put(GLADIX_QUERY, query)
}

// A sorted, reversed or searched load: not the tab's default list, so not for the disk cache.
fun Map<String, String>.isExtParamLoad() =
    containsKey(GLADIX_SORT) || containsKey(GLADIX_REVERSED) || containsKey(GLADIX_QUERY)
