package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date as EchoDate
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DeezerParser(private val session: DeezerSession) {


    /**
     * The SECTION overload — the receiver is the whole section object, not a bare data array.
     *
     * It carries a [Shelf.Lists.Items.more] and the other two overloads do not, which is deliberate and is
     * what scopes this change: only Home's carousels (DeezerHomeFeedClient.loadHomeFeed) and the channel
     * sub-pages (DeezerExtension.channelFeed) bind here. DeezerArtistClient and DeezerLibraryClient go
     * through the JsonArray/JsonObject overloads below and are untouched — they receive a bare array with
     * no section context, so they could not build a meaningful `more` even if it were wanted there.
     * DeezerSearchClient calls this too but discards the result and rebuilds it; see the note below.
     *
     * WHAT `more` DOES HERE: it re-wraps the SAME already-parsed list as a vertical page. No fetch, no
     * network, no second parse. The app renders the expand arrow purely on `more != null`
     * (HeaderViewHolder.bind), so this is what turns a sideways-swiping carousel into a page you can read
     * down — which is the entire point. It is NOT a "load more".
     *
     * MIRRORS DeezerSearchClient's else-branch construction exactly, so Home and Search behave
     * identically. The one field Search sets explicitly and this does not is `type = Type.Linear`; that is
     * already the default on Shelf.Lists.Items, so the two are the same shelf.
     *
     * ⚠️ DEPTH DEPENDS ON WHICH BRANCH THE ARROW TOOK, and the two differ.
     *
     * RE-WRAP (no target): terminates after one level, BY TYPE, not by a guard. The expansion emits
     * Shelf.Item, and FeedType.toFeedTypes maps Shelf.Item to Media/Video — only `is Shelf.Lists<*>`
     * produces a Header and only a Header renders an arrow. An expanded page contains no arrows.
     *
     * FETCH (target present): does NOT terminate by type. `block` resolves to channelFeed, which returns
     * Shelf.Lists built by THIS function — so the fetched page's rows carry arrows of their own, and any
     * of those with a target fetch again. Depth is bounded by DEEZER'S OWN GRAPH, not by us: a page stops
     * offering arrows when its sections stop carrying targets. That is a real bound in practice (a channel
     * page's rows are plain item lists) but it is not one we enforce, and a cycle in their targets would
     * loop. Left unguarded DELIBERATELY: a depth counter here would have to be threaded through
     * channelFeed and every caller for a case not observed, and the navigation stack is the user's exit.
     * If a loop is ever seen, the guard belongs at channelFeed — one place, with the target in hand.
     *
     * [OBSERVED 2026-09-08 — THE "UNOBSERVED CASE" CAVEAT ABOVE NOW HAS A TRACED EXAMPLE, AND IT TERMINATES.]
     * The deepest chain reachable from Home was walked end to end on device:
     *   Home "New releases for you" (12 items)
     *     → arrow → /channels/new, a SIX-SECTION landing page (Fresh picks of the week / New releases for
     *       you / Popular / Dig Deeper / Rock / Movies & series)
     *     → its own "New releases for you" row (the same 12)
     *     → arrow → a page with ONE carousel, "New releases for you", 15+ items, first 12 identical
     *     → nothing further.
     * THE INNER SECTION'S TARGET POINTS AT THE MODULE, NOT BACK AT /channels/new. The self-referential
     * cycle this note worried about does not exist in Deezer's data on this path, and the chain ends by
     * TERMINATING CONDITION 1 — the last page's sections carry no target, so `more` takes its else branch
     * and emits Shelf.Item with no arrow.
     * SO NO DEPTH GUARD IS NEEDED, and the reasoning for leaving it unguarded is now evidence rather than
     * assumption. Four terminating conditions exist in this file and channelFeed between them: (1) section
     * has no target; (2) section parses to zero items and is dropped by filterNotNull; (3) the fetch fails
     * or returns empty and falls back to the re-wrap; (4) the fetch returns exactly ONE section and is
     * flattened to Shelf.Item. Only a MULTI-section page whose sections carry targets can descend at all,
     * and the one such page reachable from Home descends exactly two levels and stops.
     * ⚠️ IF A TRUE CYCLE IS EVER SEEN, none of this changes where the guard goes — channelFeed, one
     * place, with the target in hand. What changed is that "not observed" is now "observed and bounded".
     * ⚠️ AND THE FLATTENING MADE RECURSION *LESS* LIKELY, NOT MORE, which is worth stating because the
     * intuition runs the other way. Flattening IS terminating condition (4): it applies only to a
     * SINGLE-section fetch and converts that page's contents to Shelf.Item, which carry no `more`, so such
     * a page TERMINATES AT DEPTH 1 exactly as the arrow's original spec said it should. Module rows can no
     * longer recurse at all. The multi-section branch is unchanged, so it removes recursive paths without
     * adding any.
     *
     * ⚠️ THE ARROW NOW FETCHES WHEN THE SECTION CARRIES A `target`, AND RE-WRAPS WHEN IT DOES NOT.
     * Section targets are `/channels/module/<uuid>` — exactly the shape api.page(target.substringAfter("/"))
     * consumes, the same resolution toChannel/channelFeed already use for ITEM-level targets. Confirmed
     * populated on Search 2026-09-04 (six of eight sections; the two without are an unnamed horizontal-grid
     * and "Explore all"), which is why the re-wrap fallback stays rather than the arrow being hidden: a
     * vertical view of a long row is useful on its own and every row keeps one.
     *
     * COST: one page.get per TAP. The lambda does not run at feed-build time, so nothing is paid by a feed
     * that is never expanded — the same cost profile a channel tile already has.
     *
     * Historical note, kept because it explains the shape: Deezer caps its
     * carousels on the main screens, and where it shows an expand arrow that arrow returns MORE items than
     * the row displayed — so there is real content behind some of these sections that this does not reach.
     * The way in is the section-level `target` field (DeezerSearchClient's logSections already reads one
     * at its `target` line; nothing else in this extension consumes it). A fetching version would resolve
     * it the way toChannel/channelFeed already resolve an item-level target, via api.page(target). That is
     * deliberately NOT what this is: the re-wrap is the cheap version and was chosen first, on purpose.
     * If you come back to make the arrow fetch, the section `target` is where to look — and note that a
     * fetched page returning Lists would make the termination note above no longer hold.
     */
    // `block` was RETAINED BUT UNUSED from 2026-09-05 to 2026-09-07 while the fetch branch was off, with
    // an @Suppress("UNUSED_PARAMETER") here. Keeping the parameter (and channelFeed, and the target read)
    // is what made re-enabling a one-expression change rather than a re-plumb. It is used again; the
    // suppression is gone with it.
    /**
     * @param name the DISPLAY title. May be blank — see the shelf construction below.
     * @param id the STABLE identity. Defaults to [name] for callers that have nothing better, but a
     *   caller holding a section_id/module_id should pass it. ⚠️ NEVER KEY ON A DEEZER MODULE'S TITLE:
     *   module 46b377f1 was "Summer in slow-mo" and is now "Hello, sunshine" — same module, rotating
     *   label. The id reaches Shelf.Lists.Items.id, which is what Cached.getFeedShelf builds its cache
     *   key from and what two untitled sections of one page would otherwise collide on.
     *   `block` stays LAST so existing trailing-lambda call sites keep binding to it.
     */
    fun JsonElement.toShelfItemsList(
        name: String = "Unknown",
        id: String = name,
        block: (suspend (String) -> List<Shelf>)? = null,
    ): Shelf? {
        val rawItems = obj()["items"]?.jsonArray
        val items = rawItems?.mapObjects { it.toEchoMediaItem() }.orEmpty()
        // Section-level target, NOT the item-level one toChannel reads. Absent on some sections by design.
        val target = obj().str("target")

        // ── SILENT-DROP DIAGNOSTIC (2026-09-05, temporary, GladixDeezer). REMOVE WITH THE TRACE. ──
        // A section that parses to zero items returns null here and is then removed by the caller's
        // filterNotNull()/getOrNull() — the row simply is not on Home, with no signal anywhere. That is
        // exactly how "Made for you" (5 items, every one lacking __TYPE__ so every one parsing to null)
        // went unnoticed. Print WHY, plus the raw first item so the item shape can be classified:
        //   __TYPE__ present but nested where unwrap() does not reach -> fix unwrap, row works
        //   no __TYPE__ but identifiable another way                  -> fallback branch on what IS there
        //   nothing usable                                            -> dropping is right, silence is the bug
        // ⚠️ PRINTS RAW RESPONSE JSON, length-capped. Strip before any release build.
        if (items.isEmpty()) {
            val reason = when {
                rawItems == null -> "no-items-key"
                rawItems.isEmpty() -> "items-empty"
                else -> "no-item-parsed"
            }
            println(
                "GladixDeezer DROP section=\"$name\" reason=$reason raw=${rawItems?.size ?: 0} " +
                    "parsed=0 target=${target ?: "?"}"
            )
        }

        return items.takeIf { it.isNotEmpty() }?.let { list ->
            Shelf.Lists.Items(
                id = id,
                title = name,
                list = list,
                // ⚠️ FETCH BRANCH DISABLED 2026-09-05 — EVERY SECTION RE-WRAPS. Do not re-enable without
                // reading this. It was added by f03492fc and had ZERO working cases on device.
                //
                // WHY: a Home section's `target` is not one endpoint, it is FOUR FAMILIES, and channelFeed
                // assumes one shape (a page with results.sections). Captured on device 2026-09-05:
                //   /channels/module/<uuid>  Summer in slow-mo, Playlists you'll love, Recently you've
                //                            been loving, Playlists you love — fetched, page came back
                //                            section-shaped but yielded nothing. EMPTY rows. Body never
                //                            captured; nobody has seen what this endpoint returns.
                //   /channels/explore        Your top genres — read but NEVER USED: channel-item sections
                //                            route to toShelfCategoryList, whose `more` is cats.toFeed().
                //   /channels/new            New releases for you — untested.
                //   /artist/<id>             Since you like <artist> — an ARTIST endpoint with NO
                //                            "sections" key, so channelFeed's second `!!` throws. That is
                //                            the retraced crash: DeezerExtension.kt:379, confirmed against
                //                            build 1077's mapping (pg_map_id 9d7725eb…), frame nm0.a:127.
                //
                // The re-wrap path is not a fallback here, it is the whole behaviour: it opens the row's
                // existing items as a vertical page. That is the only thing the arrow has ever done
                // usefully — "Your top genres" showing 6 on Home and 12 expanded is this path working.
                //
                // ══ RE-ENABLED 2026-09-07, WITH A FALLBACK — AND THE FALLBACK IS WHY IT IS SAFE ══
                // The 2026-09-05 version (f03492fc) fetched and, when the fetch produced nothing, showed
                // NOTHING. That is why it had zero working cases: a family it could not parse became an
                // empty page, which is strictly worse than not fetching. This version fetches and falls
                // back to the re-wrap, so the WORST case equals the disabled behaviour exactly.
                //
                // WHAT THE 2026-09-07 CAPTURE CHANGED. channels/module/46b377f1 returns ONE section,
                // title=<null>, layout=grid, items=array[25], and ALL 25 parse with the existing code.
                // The items were never the problem — channelFeed required a section title and dropped the
                // whole section without one. That is fixed there. Per family now:
                //   /channels/module/<uuid>  WORKS. 25 items against the 12 Home shows, so the fetch is
                //                            worth having. Title falls back to the page title.
                //   /artist/<id>             NO "sections" KEY. channelFeed's guards return an empty list
                //                            and this falls back to the re-wrap — the row's own items,
                //                            i.e. today's behaviour. It can no longer throw.
                //   /channels/explore        NOT ON THIS PATH AT ALL: channel-item sections route to
                //                            toShelfCategoryList, whose `more` is cats.toFeed().
                //   /channels/new            NEVER CAPTURED. If it is page-shaped it works; if not it
                //                            degrades to the re-wrap. Bounded either way.
                //
                // runCatching is belt to the guards' braces: a throw from ANY family — including one
                // nobody has seen — becomes the re-wrap instead of an error page under the arrow.
                // ⚠️ The empty check is `isNotEmpty()`, not null-only: channelFeed returns an EMPTY LIST
                // for an unparseable page, not null, so a null-only check would show a blank page.
                // ⚠️ THE FALLBACK IS LOGGED, AND THAT IS NOT OPTIONAL. It exists to make a failure
                // invisible to the user — the row's own items appear whether the fetch worked or not — so
                // without a line here nobody can tell on device whether a family fetched or degraded.
                // Fires ONLY on the fallback path: a working family prints nothing. channelFeed prints the
                // REASON (no-results / no-sections / no-section-parsed); this prints that it happened and
                // which section it happened to, so the two compose into one story per target.
                // ⚠️ PATTERN, NAMED ONCE HERE RATHER THAN RE-DISCOVERED A FIFTH TIME:
                //   runCatching CATCHES Throwable. CancellationException IS a Throwable. So EVERY
                //   runCatching around suspending work LAUNDERS CANCELLATION INTO FAILURE.
                // The caller is then told its work failed when it was merely cancelled, and whatever the
                // failure path does — fall back, log, report an error, resume a continuation — runs against
                // a scope that is already going away. Four instances in this project:
                //   1. ImageUtils.tryWithSuspend        FIXED — rethrow ahead of the generic catch.
                //   2. PlayerBitmapLoader:31            FIXED — was laundering a cancellation into
                //                                       error("Failed to load bitmap…"), i.e. telling Media3
                //                                       the load FAILED when it was CANCELLED.
                //   3. CoroutineUtils.futureCatching    STILL CATCHING at 2026-09-07, deliberately — see the
                //                                       note there; a naive rethrow leaves the SettableFuture
                //                                       never completed and hangs Media3, so it needs the
                //                                       future CANCELLED, not the exception rethrown.
                //   4. this one                         FIXED 2026-09-07, below.
                // If you write runCatching around a suspend call, decide what cancellation should do BEFORE
                // you write the catch. "Nothing" is never the answer.
                more = if (block != null && !target.isNullOrBlank()) PagedData.Single<Shelf> {
                    val fetched = runCatching { block(target) }
                        .onFailure { e ->
                            // ⚠️ REthrow BEFORE THE LOG AS WELL AS BEFORE THE FALLBACK, and the log ordering
                            // is not incidental. This fetch is cancelled ROUTINELY, not exceptionally: the
                            // exception seen on device is kotlinx.coroutines' internal
                            // ChildCancelledException ("Child of the scoped flow was cancelled",
                            // flow/internal/FlowExceptions.kt:9, coroutines 1.11.0), thrown by
                            // ChannelFlowTransformLatest.flowCollect at flow/internal/Merge.kt:25 —
                            // transformLatest's cancel-the-PREVIOUS-transform path, verbatim. flowScope
                            // survives it because FlowCoroutine.childCancelled returns true for exactly
                            // this cause (flow/internal/FlowCoroutine.kt:53-56), so only the in-flight work
                            // dies. On this screen family that is the combineTransformLatest churn.
                            // CONSEQUENCE FOR ANYONE READING OLD LOGS: EVERY
                            // `reason=threw:ChildCancelledException` LINE RECORDED BEFORE 2026-09-07 IS A
                            // FALSE FAILURE REPORT. The fetch was not failing and Deezer was not at fault —
                            // the page was being replaced mid-flight and the 12-item re-wrap was served in
                            // place of the 25 items the fetch would have returned. Do not treat those lines
                            // as evidence of anything about /channels/module.
                            if (e is CancellationException) throw e
                            println(
                                "GladixDeezer FALLBACK target=$target section=\"$name\" " +
                                    "reason=threw:${e::class.simpleName} msg=${e.message?.take(120)}"
                            )
                        }
                        .getOrNull()
                    if (fetched.isNullOrEmpty()) {
                        // Only when it did NOT throw — a throw has already printed its own line above and
                        // saying "empty" as well would double-count one failure as two.
                        if (fetched != null) println(
                            "GladixDeezer FALLBACK target=$target section=\"$name\" reason=empty"
                        )
                        list.map { item -> item.toShelf() }
                    } else {
                        // ⚠️ FLATTEN A SINGLE-SECTION FETCH — THIS RESTORES THE ARROW'S DOCUMENTED DESIGN,
                        // it does not invent a new one. When the expand arrow shipped it was specified as
                        // "terminates after one level BY TYPE — the expansion emits Shelf.Item, and only
                        // Shelf.Lists produces a Header". The re-wrap arm above honours that exactly
                        // (list.map { it.toShelf() } -> Shelf.Item -> vertical rows). THE FETCH ARM
                        // SILENTLY VIOLATED IT: channelFeed returns Shelf.Lists.Items per section, so a
                        // tapped arrow rendered Header + HORIZONTAL CAROUSEL — a page that reproduced the
                        // row the user had just tapped, only with more items in it (15+ vs 12 on device,
                        // 2026-09-08). Same arrow, two presentations, and the fetch path picked the one
                        // that looks like nothing happened.
                        // PRECEDENT — THIS IS THE ESTABLISHED PATTERN HERE, NOT A NEW JUDGEMENT. One session
                        // made TWO of these conversions at once:
                        //   • an artists shelf replaced with flat Shelf.Item per artist, for "no nested
                        //     horizontal scroll, consistent with how artists render elsewhere";
                        //   • getShelves() changed from mapNotNull to flatMap so buildTopTracksShelf()
                        //     returns a Shelf.Category PLUS individual Shelf.Item per track.
                        // and that note ends "albums and Related Artists carousels unchanged". THAT IS THIS
                        // RULE ALREADY: flatten what should be a list, leave genuine carousels alone. The
                        // singleOrNull test below is the same decision expressed structurally.
                        //
                        // ⚠️ ONE RECORDED DOWNSIDE OF THOSE CONVERSIONS, AND WHY IT DOES NOT TRANSFER HERE.
                        // A later session found the flat Shelf.Item rows "difficult to scroll upward —
                        // gestures were frequently swallowed" on the PLAYER'S INFO TAB. That tab lives in a
                        // BOTTOM SHEET, and BottomSheetBehavior competes for vertical drags with its content:
                        // it cooperates with exactly ONE scrolling child (findScrollingChild, a depth-first
                        // search for the first nested-scrolling-enabled view) and treats drags it does not
                        // route there as sheet drags. AN EXPANDED FEED PAGE HAS NO BOTTOM SHEET IN ITS
                        // PARENT CHAIN — the arrow opens FeedFragment, which is
                        // R.layout.fragment_generic_collapsable: CoordinatorLayout > AppBarLayout >
                        // CollapsingToolbarLayout + FragmentContainerView. So that mechanism cannot apply.
                        // What DOES exist there is the same CLASS of consumer — that CollapsingToolbarLayout
                        // eats the first header-height of every upward drag — but it is unchanged by this
                        // fix: it consumed exactly the same scroll when the arrow rendered carousels. And
                        // flattening REMOVES inner horizontal RecyclerViews, i.e. removes a competitor for
                        // drags, which pushes the opposite way from the recorded symptom.
                        // IF GESTURES ARE EVER SWALLOWED ON ONE OF THESE PAGES, the suspect is not the row
                        // type: it is the fast scroller's OnItemTouchListener, which latches by registration
                        // order (see FastScrollerHelper) and is row-type-independent.
                        //
                        // THE RULE IS STRUCTURAL, NOT TEXTUAL:
                        //   exactly ONE Shelf.Lists.Items -> flatten to its items, matching the re-wrap
                        //   MORE than one              -> keep them as sections, headers and all
                        // One section means "the arrow found the same row, fuller". Several means it found
                        // a PAGE, which is a legitimately different thing and needs its headers to be
                        // legible. Anything that is not a single Shelf.Lists.Items (a Category, a bare
                        // Shelf.Item) is passed through untouched — `as?` makes that the default.
                        //
                        // ⚠️ NO TITLE COMPARISON, DELIBERATELY, AND THE DUPLICATE HEADER GOES AWAY AS A
                        // CONSEQUENCE. The page previously rendered its name twice — page header, then a
                        // section header of the same name — because channelFeed gives an untitled section
                        // the page title. Suppressing that by comparing the two strings WOULD ROT: module
                        // labels rotate, and module_id 46b377f1-fbd5-4e04-979e-177b5df21183 has been
                        // "Summer in slow-mo", "Hello, sunshine" and (2026-09-08) "Focus flows for summer
                        // work" — the same module, three names. Flattening removes the section header
                        // structurally, so there is nothing to compare and nothing to drift. channelFeed's
                        // title fallback STAYS: in a multi-section page an untitled section still needs a
                        // label, and there the page title is the best available.
                        //
                        // ⚠️ WHY HERE AND NOT IN channelFeed: channelFeed serves TWO intents. This one —
                        // "expand a row" — wants a flat list. The channel-tile / toShelfCategoryList callers
                        // want a browse PAGE and must keep their sections. The intent lives at the call
                        // site, so the adaptation does too; flattening inside channelFeed would silently
                        // reshape the tile pages as well.
                        //
                        // THE RECURSION FLATTENS TOO, AND THAT IS INTENDED (confirmed 2026-09-08): a
                        // channel page's own rows get fetching arrows via this same function, so a nested
                        // single-section page flattens as well. An arrow means "show me all of this"
                        // wherever it appears.
                        //
                        // [CORRECTED 2026-09-08] This read "/channels/new IS HANDLED, NOT ASSUMED — UNTESTED
                        // EITHER WAY". It is now tested. Device capture: tapping "New releases for you"
                        // opens Deezer's New Releases LANDING PAGE — Fresh picks of the week, New releases
                        // for you (the row tapped from), Popular, Dig Deeper, Rock, Movies & series. SEVERAL
                        // SECTIONS, so this rule keeps their headers and that page is unchanged by the fix.
                        // Working as designed; do not "fix" it into a flat list without reading the note
                        // below.
                        //
                        // ⚠️ THE ODDITY IS UPSTREAM OF THE SHAPE, AND IT IS NOT A BUG IN THIS FUNCTION. That
                        // row's `target` points at a LANDING PAGE THAT CONTAINS THE ROW, not at the row's
                        // own contents — unlike /channels/module/<uuid>, which returns exactly that one
                        // module. So for /channels/new the arrow navigates to somewhere containing the row
                        // rather than expanding it. Deezer chose the target; we render what it points at.
                        // CONSIDERED AND NOT TAKEN: a pre-fetch rule ("only fetch when the target is a
                        // /channels/module/<uuid>"). The positive half is sound — a module IS one row by
                        // definition — but the COMPLEMENT is a claim about every other target family, and
                        // only four have ever been seen. It would silently suppress fetching for any
                        // single-row family nobody has captured, and suppress the FALLBACK logging with it,
                        // since no attempt would be made. That is the same shape as the AA producer-side
                        // filter and the SUPPORT-map misread, both of which cost builds this month.
                        // The section count is the RELIABLE discriminator and it is already measured here,
                        // post-fetch — which is why the rule below keys on it rather than on the target.
                        //
                        // ALSO CONSIDERED AND DROPPED 2026-09-08: making the arrow mean ONE thing everywhere
                        // by RE-WRAPPING on the multi-section branch instead of showing the fetched page.
                        // That would have given uniform "expand this row" semantics with no guessing from
                        // target strings, at the cost of one wasted page.get per landing page. Dropped on
                        // three device findings, not on preference:
                        //   • the landing page has GENUINE CONTENT worth reaching — Fresh picks of the week,
                        //     Popular, Dig Deeper, Rock, Movies & series are not on Home;
                        //   • the descent TERMINATES (see the traced chain at the depth note above), so the
                        //     open-ended navigation it would have prevented does not occur;
                        //   • it would make the multi-section branch below DEAD CODE. That branch is live and
                        //     exercised, which is the clearest signal that keeping it is right.
                        // Taking it would trade a working navigation path for consistency. Do not revive it
                        // without a case where the descent does NOT terminate.
                        val single = fetched.singleOrNull() as? Shelf.Lists.Items
                        if (single != null) single.list.map { it.toShelf() } else fetched
                    }
                }.toFeed()
                else PagedData.Single<Shelf> { list.map { item -> item.toShelf() } }.toFeed()
            )
        }
    }

    fun JsonObject.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val item = toEchoMediaItem() ?: return null
        return Shelf.Lists.Items(id = name, title = name, list = listOf(item))
    }

    fun JsonArray.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val items = mapObjects { it.toEchoMediaItem() }
        return items.takeIf { it.isNotEmpty() }?.let {
            Shelf.Lists.Items(id = name, title = name, list = it)
        }
    }

    inline fun JsonElement.toShelfCategoryList(
        name: String = "Unknown",
        shelf: String,
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Lists.Categories {
        val arr = obj()["items"]?.jsonArray ?: return Shelf.Lists.Categories(name, name, emptyList())
        val listType = if ("grid" in shelf) Shelf.Lists.Type.Grid else Shelf.Lists.Type.Linear
        val cats = arr.mapNotNull { it.jsonObject.toShelfCategory(block) }
        return Shelf.Lists.Categories(
            id = name,
            title = name,
            // Cap only the vertical card-grid preview; scrollable carousels (Linear) show all
            // items, matching the original uncapped toShelfItemsList behavior (12+).
            list = if (listType == Shelf.Lists.Type.Grid) cats.take(6) else cats,
            type = listType,
            more = cats.toFeed()
        )
    }

    inline fun JsonObject.toShelfCategory(
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Category? {
        val data = unwrap()
        val type = data.str("__TYPE__") ?: return null
        return when {
            "channel" in type -> toChannel(block)
            else -> toEchoMediaItem()?.toMediaCategory()
        }
    }

    // Routes a Home section to the category path when its items are genuine channels
    // (genre/hub tiles). Mirrors the "channel" in __TYPE__ test used by toShelfCategory
    // so routing and per-item handling stay consistent.
    fun JsonObject.hasChannelItems(): Boolean =
        this["items"]?.jsonArray?.any {
            "channel" in ((it as? JsonObject)?.unwrap()?.str("__TYPE__").orEmpty())
        } ?: false

    fun EchoMediaItem.toMediaCategory(): Shelf.Category = Shelf.Category(
        id = id,
        title = title,
        feed = Feed(emptyList()) { listOf(toShelf()).toFeedData() },
        subtitle = subtitle,
        image = cover,
    )

    inline fun JsonObject.toChannel(
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Category {
        val data = unwrap()
        val title = data.str("title").orEmpty()
        val target = str("target").orEmpty()
        return Shelf.Category(
            id = title,
            title = title,
            feed = Feed(emptyList()) { block(target).toFeedData() }
        )
    }

    fun JsonObject.toEchoMediaItem(): EchoMediaItem? {
        val data = unwrap()
        // ⚠️ NARROW FALLBACK, MATCHED TO ONE VALUE — do not generalise to "any outer type key".
        // Home's "Made for you" items carry their type at the OUTER level as `type`, not as `data.__TYPE__`,
        // so unwrap() descends into `data`, finds no __TYPE__, and every item returned null — five playable
        // daily mixes silently dropped, and the row never rendered at all (2026-09-05 capture).
        // Accepting ANY outer `type` value would be unsafe: "channel" is one of them, and the `"channel" in
        // t` arm below would then match items that DeezerHomeFeedClient.hasChannelItems had already routed
        // to toShelfCategoryList — two paths claiming the same item. So this matches "smarttracklist" only.
        // unwrap() is deliberately UNTOUCHED: it is shared with hasChannelItems, toShelfCategory and
        // toChannel, all of which must keep reading __TYPE__.
        val outerSmart = str("type")?.takeIf { it == "smarttracklist" }
        if (data.str("__TYPE__") == null && outerSmart != null) return toSmartTracklist()
        return when (val t = data.str("__TYPE__")) {
            null -> null
            else -> when {
                "playlist" in t -> toPlaylist()
                "album" in t -> toAlbum()
                "song" in t -> toTrack()
                "artist" in t -> toArtist(isShelfItem = true)
                "show" in t -> toShow()
                "episode" in t -> toEpisode()
                "flow" in t -> toRadio()
                else -> null
            }
        }
    }

    fun JsonObject.toShow(): Album = unwrap().let { data ->
        val md5 = data.str("SHOW_ART_MD5")
        Album(
            id = data.str("SHOW_ID").orEmpty(),
            type = Album.Type.Show,
            title = data.str("SHOW_NAME").orEmpty(),
            cover = getCover(md5, "talk"),
            trackCount = obj()["EPISODES"]?.jsonObject?.int("total")?.toLong(),
            artists = emptyList(),
            description = data.str("SHOW_DESCRIPTION").orEmpty(),
            extras = mapOf("__TYPE__" to "show")
        )
    }

    fun JsonObject.toEpisode(bookmark: Map<String?, Long?> = emptyMap()): Track = unwrap().let { data ->
        val md5 = data.str("SHOW_ART_MD5")
        val title = data.str("EPISODE_TITLE").orEmpty()
        val id = data.str("EPISODE_ID").orEmpty()
        Track(
            id = id,
            title = title,
            type = Track.Type.Podcast,
            cover = getCover(md5, "talk"),
            duration = data.long("DURATION")?.times(1000),
            playedDuration = bookmark[id]?.times(1000),
            streamables = listOf(
                Streamable.server(
                    id = data.str("EPISODE_DIRECT_STREAM_URL").orEmpty(),
                    title = title,
                    quality = 12
                )
            ),
            extras = mapOf(
                "TRACK_TOKEN" to data.str("TRACK_TOKEN").orEmpty(),
                "FILESIZE_MP3_MISC" to (data.str("FILESIZE_MP3_MISC") ?: "0"),
                "TYPE" to "talk",
                "__TYPE__" to "show"
            )
        )
    }

    fun JsonObject.toAlbum(): Album = unwrap().let { data ->
        val md5 = data.str("ALB_PICTURE")
        val artistsArr = data.arr("ARTISTS").orEmpty()
        val trackCount = obj()["SONGS"]?.jsonObject?.int("total")
        val rd = data.str("ORIGINAL_RELEASE_DATE")?.toDate()
            ?: data.str("PHYSICAL_RELEASE_DATE")?.toDate()
        Album(
            id = data.str("ALB_ID").orEmpty(),
            title = data.str("ALB_TITLE").orEmpty(),
            cover = getCover(md5, "cover"),
            trackCount = trackCount?.toLong(),
            artists = artistsArr.map { artist ->
                val obj = artist.jsonObject
                Artist(
                    id = obj.str("ART_ID").orEmpty(),
                    name = obj.str("ART_NAME").orEmpty(),
                    cover = getCover(obj.str("ART_PICTURE"), "artist")
                )
            },
            releaseDate = rd,
            description = str("description").orEmpty(),
            subtitle = str("subtitle")
                ?: when {
                    trackCount != null && rd != null -> "$trackCount Songs • $rd"
                    trackCount != null -> "$trackCount Songs"
                    else -> rd?.toString()
                }
        )
    }

    fun JsonObject.toArtistFromRestApi(): Artist {
        return Artist(
            id = str("id").orEmpty(),
            name = str("name").orEmpty(),
            cover = (str("picture_medium") ?: str("picture"))
                ?.takeIf { it.isNotEmpty() }?.toImageHolder(),
            extras = mapOf("followers" to (str("nb_fan") ?: "0"))
        )
    }

    fun JsonObject.toAlbumFromRestApi(artist: Artist): Album {
        val releaseDate = str("release_date")?.toDate()
        return Album(
            id = str("id").orEmpty(),
            title = str("title").orEmpty(),
            cover = (str("cover_medium") ?: str("cover"))?.takeIf { it.isNotEmpty() }?.toImageHolder(),
            artists = listOf(artist),
            releaseDate = releaseDate,
            subtitle = releaseDate?.toString()
        )
    }

    fun JsonObject.toArtist(isShelfItem: Boolean = false): Artist {
        val artistData = when {
            isShelfItem && this["data"] == null -> this
            this["DATA"]?.jsonObject?.get("ART_BANNER") == null -> this["DATA"]?.jsonObject ?: this["data"]?.jsonObject ?: this
            else -> this["data"]?.jsonObject ?: this
        }
        val md5 = artistData.str("ART_PICTURE")
        val bio = if (this["BIO"] is JsonObject) {
            val b = this["BIO"]!!.jsonObject
            val p1 = b.str("BIO").orEmpty().replace("<br />", "").replace("\\n", "")
            val p2 = b.str("RESUME").orEmpty().replace("<p>", "").replace("</p>", "")
            p1 + p2
        } else ""
        return Artist(
            id = artistData.str("ART_ID").orEmpty(),
            name = artistData.str("ART_NAME").orEmpty(),
            cover = getCover(md5, "artist"),
            bio = bio,
            subtitle = str("subtitle"),
            extras = mapOf("followers" to (artistData.int("NB_FAN")?.toString() ?: "0"))
        )
    }

    fun JsonObject.toTrack(): Track {
        val data = unwrap()
        val md5 = data.str("ALB_PICTURE")
        val artistsArr = data.arr("ARTISTS")
        val version = data.str("VERSION")
        return Track(
            id = data.str("SNG_ID").orEmpty(),
            title = buildString {
                append(data.str("SNG_TITLE").orEmpty())
                if (!version.isNullOrEmpty()) append(" ").append(version)
            },
            cover = getCover(md5, "cover"),
            duration = data.long("DURATION")?.times(1000),
            releaseDate = data.str("PHYSICAL_RELEASE_DATE")?.toDate()
                ?: data.str("DIGITAL_RELEASE_DATE")?.toDate(),
            artists = parseArtists(artistsArr, data),
            album = Album(
                id = data.str("ALB_ID").orEmpty(),
                title = data.str("ALB_TITLE").orEmpty(),
                cover = getCover(md5, "cover")
            ),
            albumOrderNumber = data.long("TRACK_NUMBER"),
            albumDiscNumber = data.long("DISK_NUMBER"),
            isrc = data.str("ISRC"),
            isExplicit = data.str("EXPLICIT_LYRICS") == "1",
            // ⚠⚠ `isPlayable` IS MISSING HERE, AND THAT IS THE ENTIRE "GREY OUT UNAVAILABLE
            // TRACKS" JOB. It is A FIELD TO POPULATE, NOT A FEATURE TO BUILD - record kept permanently
            // because the task NAME makes it sound like the opposite, and it would be re-scoped as UI work.
            // EVERYTHING DOWNSTREAM ALREADY EXISTS AND ALREADY WORKS:
            //   common's Track carries `isPlayable: Playable = Playable.Yes` with Yes / RegionLocked /
            //     Unreleased / No(reason) - polymorphic, already in the ABI, no version-skew question;
            //   MediaViewHolder, MediaGridViewHolder, ShelfViewHolder and both video holders all grey out
            //     on `isPlayable != Playable.Yes`;
            //   ShelfViewHolder reroutes the TAP to onMediaClicked instead of playing;
            //   MediaHeaderAdapter hides the play button;
            //   StreamableLoader throws TrackUnavailableException, with a documented silent-skip and a
            //     consecutiveUnavailableSkips breaker (max 3) behind it;
            //   TestExtension already sets Playable.Unreleased, so the path is exercised.
            // Deezer tracks therefore default to Playable.Yes and render as ordinary rows - which is why
            // Deezer's own app greys these and ours does not.
            // ⚠⚠ CLOSED 2026-09-12: THERE IS NO PARSE-TIME FIELD TO SET IT FROM, SO GREYING
            // OUT IN ADVANCE IS NOT ACHIEVABLE HERE. This cost three captures; do not re-open it without
            // reading all four sections below.
            //
            // WHY NOT: AVAILABILITY IS DECIDED AT TOKEN EXCHANGE, NOT IN THE CATALOGUE RECORD. Three
            // stream-time failure branches in DeezerTrackClient.createStreamableForQuality prove where the
            // decision lives:
            //   :40  "License token has no sufficient rights on requested media" -> step FLAC -> 320 -> 128
            //   :43  all qualities exhausted -> throw Exception("Track not available on server")
            //   :49  "Track token has no sufficient rights on requested media" OR mediaIsEmpty -> FALLBACK
            // ⚠⚠ AND THAT CHAIN IS SHIPPED DESIGN, NOT OUR READING OF IT. An earlier session
            // built it deliberately - "Fixed quality fallback: FLAC -> 320 -> 128kbps (was FLAC -> 128,
            // skipping 320)" and "Replaced ClientException.LoginRequired catch-all with Exception('Track not
            // available on server')". A PER-QUALITY STEPDOWN WOULD BE POINTLESS IF A CATALOGUE FIELD
            // ANSWERED THE QUESTION. Same record adds the ACCOUNT dimension: "dzp (shared server) tracks
            // confirmed not affected - all three qualities go through shared server on tested account" -
            // same track, different account, different outcome. No per-track field can express that.
            //
            // FOUR DEAD CANDIDATES - DO NOT RE-TEST THESE:
            //   TRACK_TOKEN  empty means the track LOST ITS EXTRAS (HistoryEntity.toSlim empties the map),
            //                i.e. provenance, not availability. See the note at loadTrack's self-heal gate.
            //   FILESIZE     an id from a wholly unplayable album (11764421, "Since I Fell For You") had
            //                REAL filesize values and does not play.
            //   RIGHTS       a track with RIGHTS={} PLAYS (69106336, "All Day and All of the Night",
            //                verified on device). An empty rights object cannot mean "no rights".
            //   STATUS       same record: STATUS=3 and it plays.
            // RIGHTS and AVAILABLE_COUNTRIES are INPUTS to a decision made elsewhere; FILESIZE and STATUS
            // describe the catalogue entry. None of them is the verdict.
            //
            // THE COMPLETE KEY SET OF A GATEWAY SONG RECORD, so "did anyone check the other fields" has an
            // answer: ALB_ID ALB_PICTURE ALB_TITLE ARTISTS ART_ID ART_NAME DATE_ADD DURATION
            // EXPLICIT_TRACK_CONTENT FILESIZE FILESIZE_AAC_64 FILESIZE_FLAC FILESIZE_FLAC_24
            // FILESIZE_MP3_128 FILESIZE_MP3_256 FILESIZE_MP3_320 FILESIZE_MP3_64 FILESIZE_MP4_RA1
            // FILESIZE_MP4_RA2 FILESIZE_MP4_RA3 GAIN HIERARCHICAL_TITLE ISRC LYRICS_ID MD5_ORIGIN MEDIA
            // MEDIA_VERSION RANK_SNG RIGHTS SMARTRADIO SNG_CONTRIBUTORS SNG_ID SNG_TITLE STATUS TRACK_TOKEN
            // TRACK_TOKEN_EXPIRE TYPE UPLOAD_ID USER_ID VERSION __TYPE__
            // Identifiers, titles, artist fields, eleven filesizes, ISRC, MD5_ORIGIN, the token and its
            // expiry, ranking and contributor metadata. MEDIA is the only other glanceable one and it is
            // covered by the same argument: the media array arrives from the MEDIA ENDPOINT at request time,
            // not from this record. Nothing plausible remains.
            //
            // ⚠️ THE DESIGN ERROR, WHICH IS THE TRANSFERABLE PART: the probe that produced those
            // three captures filled two slots keyed on FILESIZE zero/non-zero, and the slot LABELS were then
            // read as "playable"/"unplayable". The label depended on the hypothesis under test, so it
            // asserted the very thing being measured. Opening a playlist filled both slots from whichever
            // records parsed first, giving arbitrary pairs that could not answer a question about specific
            // named tracks. IF A PROBE'S LABEL DEPENDS ON THE HYPOTHESIS, IT CANNOT TEST THE HYPOTHESIS -
            // label from something independently verified (a named id) instead.
            //
            // PARKED, SEPARATELY AND NOT PART OF THIS CLOSURE: remember a track that FAILED to play and mark
            // it unplayable for the session, so the second tap is not as blind as the first. The failure is
            // already detected in the right place - StreamableLoader throws TrackUnavailableException - and
            // Playable.No(reason) exists to carry it. ⚠️ COST, STATED: this is NEW MACHINERY, not
            // a field to populate. There is no per-track unplayable store, and MediaItemUtils.buildLoaded
            // replaces `state` wholesale, so a mark would not survive the next resolution. Scope it as its
            // own item or not at all.
            extras = buildMap {
                put("FALLBACK_ID", data["FALLBACK"]?.jsonObject?.str("SNG_ID").orEmpty())
                put("TRACK_TOKEN", data.str("TRACK_TOKEN").orEmpty())
                // ⚠⚠ `?: "0"` COLLAPSES "KEY MISSING" AND "VALUE ZERO" INTO ONE STRING, AND
                // WHOEVER BUILDS isPlayable MUST READ THIS BEFORE CHOOSING FILESIZE AS THE AXIS.
                // Today's only consumer is DeezerTrackClient's `isMp3Misc = extras[...] != "0"`, whose
                // failure mode on a missing key is BENIGN - it picks the quality ladder instead of the
                // MP3-only streamable, and streaming still works.
                // ⚠️ isPlayable WOULD MAKE THE SAME AMBIGUITY USER-VISIBLE: a record that simply
                // does not carry the key would read as zero and GREY OUT A PLAYABLE TRACK. Same expression,
                // same defect, far worse consequence - which is the whole reason to distinguish
                // absent from zero HERE rather than at the consumer.
                // If filesize does become the axis, this needs a null-vs-"0" distinction (a nullable String,
                // or a separate "key present" flag) and the RIGHTS/SNG_STATUS/STATUS route should be ruled
                // out first - an explicit status field sidesteps the ambiguity entirely.
                put("FILESIZE_MP3_MISC", data.str("FILESIZE_MP3_MISC") ?: "0")
                put("TYPE", "cover")
                put("GAIN", data.str("GAIN") ?: "0")
                put("loved", data.str("LOVE_STATUS") ?: "0")
                val contributors = data["SNG_CONTRIBUTORS"] as? JsonObject
                contributors?.forEach { (role, names) ->
                    val arr = names as? JsonArray ?: return@forEach
                    val nameList = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() ?: (it as? JsonObject)?.str("name") }.joinToString("\n")
                    if (nameList.isNotEmpty()) put("CONTRIB_${if (role == "writer") "AUTHOR" else role.uppercase()}", nameList)
                }
            }
        )
    }

    /**
     * A Deezer "smarttracklist" — the daily mixes in Home's "Made for you" row.
     *
     * MODELLED AS A PLAYLIST, NOT A RADIO, on device evidence: tapping one in Deezer's own app opens a
     * FIXED track list with a track count and a total duration. A radio is an endless stream with neither.
     *
     * ⚠️ ITS ID IS A SMARTTRACKLIST ID, NOT A PLAYLIST ID — api.playlist()/api.playlistSongs() cannot
     * resolve it. The `smarttracklist` extra is what routes track loading back to the right endpoint; see
     * DeezerPlaylistClient.loadTracks. Same pattern as extras["radio"] driving DeezerRadioClient.kind().
     *
     * ⚠️ EXPIRY: these regenerate daily (data.EXPIRATION_DATE, ~05:00 next day) and the expiry is carried
     * into extras so Cached can refuse to durably cache them — see the note there.
     *
     * OPEN QUESTION, left deliberately unanswered: is "inspired-by-1" a SLOT that refills daily, or a
     * SNAPSHOT id that stops resolving after EXPIRATION_DATE? WHAT DISTINGUISHES THEM: play one, wait past
     * the expiry, then re-open the same persisted item. A slot returns a DIFFERENT track list with no
     * error; a snapshot fails the track load. The id is in extras either way, so this is answerable without
     * another payload capture — do not re-derive the question.
     *
     * COVER: data.COVER{TYPE, MD5} feeds getCover directly and yields a usable CDN image, so the four
     * artist md5s in `pictures` and COVER_COMPOSITION (THREE_TAPERED_CIRCLES) are ignored — that is
     * Deezer's client-side collage template, and the 2026-08-06 4-grid deferral stays deferred.
     */
    private fun JsonObject.toSmartTracklist(): Playlist? {
        val data = unwrap()
        val id = data.str("SMARTTRACKLIST_ID") ?: return null
        val cover = data["COVER"]?.jsonObject
        return Playlist(
            id = id,
            // Outer first, inner as fallback: the captured Home item carries the display strings at the
            // OUTER level (title/subtitle, already localised), while the gateway's own uppercase keys sit
            // in data. Reading both costs nothing and means a shape change on either side degrades the
            // subtitle rather than blanking the row's name.
            title = str("title") ?: data.str("TITLE").orEmpty(),
            isEditable = false,
            isPrivate = false,
            cover = getCover(cover?.str("MD5"), cover?.str("TYPE")),
            subtitle = str("subtitle") ?: data.str("SUBTITLE"),
            extras = buildMap {
                put("smarttracklist", id)
                data.str("EXPIRATION_DATE")?.let { put("expires", it) }
            },
            // ⚠️ RADIO OFF, and this is not a preference. DeezerRadioClient.radio(item) seeds a Playlist
            // radio with api.playlist(item).randomTracksFromSongs — a playlist call on an id that is not a
            // playlist — so the seed list comes back empty and it fails at `?: error("No Radio")`. Leaving
            // the default true would put a "Radio" action on the item that can only ever error.
            isRadioSupported = false,
            isSaveable = false,
            isShareable = false,
        )
    }

    fun JsonObject.toPlaylist(): Playlist = unwrap().let { data ->
        val type = data.str("PICTURE_TYPE").orEmpty()
        val md5 = data.str("PLAYLIST_PICTURE").orEmpty()
        val parentUser = data.str("PARENT_USER_ID")
        val tracks = when(this["SONGS"]) {
            is JsonArray -> {
                int("NB_SONG")
            }
            else -> this["SONGS"]?.jsonObject?.int("total")
        }
        val created = data.str("DATE_ADD")?.toDate()
        // Show/sort by the last-modified date (DATE_MOD, Deezer's "Updated"), falling back to the
        // added/created date (DATE_ADD) when absent. The fallback is REQUIRED, not cosmetic: confirmed on
        // real payloads that OWNED playlists carry DATE_MOD but FOLLOWED/favorited ones do not (they only
        // have DATE_ADD/DATE_FAVORITE), so without it a followed playlist would show a blank date. DATE_MOD
        // is present in both the library-list (pageProfile) and detail (pagePlaylist) payloads, so this one
        // spot fixes both screens.
        val modified = data.str("DATE_MOD")?.toDate()
        val date = modified ?: created
        Playlist(
            id = data.str("PLAYLIST_ID").orEmpty(),
            title = data.str("TITLE").orEmpty(),
            cover = getCover(md5, type),
            description = data.str("DESCRIPTION").orEmpty(),
            subtitle = str("subtitle") ?: when {
                tracks != null && date != null -> "$tracks Songs • $date"
                tracks != null -> "$tracks Songs"
                else -> date?.toString()
            },
            isEditable = parentUser?.contains(session.credentials.userId) == true,
            trackCount = tracks?.toLong(),
            duration = data.long("DURATION")?.times(1000),
            creationDate = date
        )
    }

    private fun JsonObject.toRadio(): Radio {
        val data = unwrap()
        val image = this["pictures"]?.jsonArray?.firstOrNull()?.jsonObject
        val md5 = image?.str("md5")
        val type = image?.str("type")
        val rawTitle = data.str("title").orEmpty()
        val title = if (rawTitle.endsWith("Flow")) rawTitle else "$rawTitle Flow"
        return Radio(
            id = data.str("id").orEmpty(),
            title = title,
            cover = getCover(md5, type),
            extras = mapOf("radio" to "flow")
        )
    }

    private fun getCover(md5: String?, type: String?): ImageHolder? {
        if (md5.isNullOrEmpty() || type.isNullOrEmpty()) return null
        // Mirrors the SettingSlider default in DeezerExtension.getSettingItems; both were 240 until
        // 2026-09-24. This is the only read of the key.
        val size = session.settings?.getInt("image_quality") ?: 480
        val url = "https://cdn-images.dzcdn.net/images/$type/$md5/${size}x${size}-000000-80-0-0.jpg"
        return url.toImageHolder()
    }

    private fun String.toDate(): EchoDate {
        // "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss"
        val y = substringBefore("-").toInt()
        val m = substringAfter("-").substringBeforeLast("-").toInt()
        val d = substringAfterLast("-").substringBefore(" ").toInt()
        return EchoDate(year = y, month = m, day = d)
    }

    private fun parseArtists(arr: JsonArray?, data: JsonObject): List<Artist> {
        return if (!arr.isNullOrEmpty()) {
            arr.mapNotNull {
                val o = it.jsonObject
                Artist(
                    id = o.str("ART_ID").orEmpty(),
                    name = o.str("ART_NAME").orEmpty(),
                    cover = getCover(o.str("ART_PICTURE"), "artist")
                )
            }
        } else {
            listOf(
                Artist(
                    id = data.str("ART_ID").orEmpty(),
                    name = data.str("ART_NAME").orEmpty()
                )
            )
        }
    }

    fun JsonElement.obj(): JsonObject = jsonObject
    fun JsonObject.unwrap(): JsonObject = this["data"]?.jsonObject ?: this["DATA"]?.jsonObject ?: this

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()
    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()
    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()
    private fun JsonObject.arr(key: String): JsonArray? = this[key]?.jsonArray

    private fun JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()

    private inline fun <T> JsonArray.mapObjects(transform: (JsonObject) -> T?): List<T> =
        mapNotNull { runCatching { transform(it.jsonObject) }.getOrNull() }
}