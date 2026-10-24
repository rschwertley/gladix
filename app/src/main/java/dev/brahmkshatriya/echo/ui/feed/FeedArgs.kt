package dev.brahmkshatriya.echo.ui.feed

/**
 * ⚠⚠ ROUTING IDS FOR A SEE-ALL FEED, AND THE PRECEDENCE IS THE WHOLE POINT. Fragment
 * arguments survive process death through FragmentManager saved state; the ACTIVITY-SCOPED
 * FeedFragment.VM does not. So after a kill - the common one is being killed while the screen is
 * locked - the activity VM is a fresh instance holding nulls while the arguments still carry the
 * ids, and the arguments therefore have to win.
 *
 * ⚠️ THE ACTIVITY-VM FALLBACK IS NOT DEAD CODE: it covers back-stack entries created
 * BEFORE the ids were added to the bundle, which survive an app update inside a live task. Dropping
 * it would break exactly those, invisibly.
 *
 * ⚠️ PURE JVM, DELIBERATELY. The decision is two null-coalesces and it is the part worth
 * being sure about, so it lives where it can be reasoned about without a Fragment, an Activity or a
 * saved-state bundle in the way. FeedFragment keeps the Android half.
 *
 * ⚠️ WHAT THIS DOES NOT RESTORE, SO THE LIMIT IS NOT REDISCOVERED: the Feed itself.
 * Feed<Shelf> carries lambdas and cannot be parcelled, so FeedFragment.VM.feed is null after a
 * kill and the LOADER path fails. What the ids buy is the CACHED path - FeedData's `cached` lambda
 * reads Cached.getFeedShelf(app, extensionId, feedId), and FeedData.dataFlow's consumers take
 * `loaded ?: cached` - so the screen comes back from the disk cache rather than blank. A live
 * refresh needs the user to pull to refresh.
 *
 * Adapted from Evoe (github.com/Doggo785/evoe), 27eac710, UPL.
 */
object FeedArgs {

    /** Arguments win; the activity VM fills in for pre-fix back-stack entries. */
    fun resolveFeedIds(
        argExtensionId: String?,
        argFeedId: String?,
        activityExtensionId: String?,
        activityFeedId: String?,
    ): Pair<String?, String?> = Pair(
        argExtensionId?.takeIf { it.isNotEmpty() } ?: activityExtensionId,
        argFeedId?.takeIf { it.isNotEmpty() } ?: activityFeedId,
    )
}
