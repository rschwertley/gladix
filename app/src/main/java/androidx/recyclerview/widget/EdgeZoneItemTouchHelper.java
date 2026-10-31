package androidx.recyclerview.widget;

import androidx.annotation.NonNull;

/**
 * ItemTouchHelper whose drag auto-scroll starts from the FINGER being in an edge zone, not the dragged
 * tile's edge crossing the list's padded edge. Used by QueueFragment.touchHelper and
 * PlaylistTrackAdapter.getTouchHelperAndListener (2026-10-30).
 * <p>
 * WHY: the stock trigger (scrollIfNecessary, ItemTouchHelper.java:745-806, recyclerview 1.4.0) fires
 * upward only once the tile's top passes the padded top, so how close the finger must get depends on
 * where the row was gripped. Its speed, Callback.interpolateOutOfBoundsScroll (:2175-2197), is ramped
 * by t^5 over 2 s (sDragScrollInterpolator, :1411): about 1 px/frame for the first second, which reads
 * on device as "the list doesn't scroll". The ramp's timer also resets on any frame that does not scroll.
 * Here the speed comes from how deep the finger is in the zone: 2dp/frame on entry up to 20dp/frame at
 * the zone's outer edge or beyond. 64dp and 2-20dp are starting values, to be tuned on device.
 * <p>
 * ⚠⚠ DEPENDS ON recyclerview 1.4.0 INTERNALS, WHICH IS WHY IT LIVES IN THIS PACKAGE. scrollIfNecessary
 * is package-private, so only a subclass in androidx.recyclerview.widget can override it. It reads
 * these package-private fields of ItemTouchHelper:
 *   mSelected (:179), mInitialTouchY (:187), mDy (:204), mRecyclerView (:252).
 * mInitialTouchY + mDy is the finger's current y in the list's coordinates (updateDxDy, :1179-1197).
 * The override is called from mScrollRunnable (:259-269), which on a true return calls moveIfNecessary
 * and re-posts itself, so rows keep swapping under a still finger.
 * ANY recyclerview UPGRADE MUST RE-CHECK all of the above: a rename fails the build, but a change of
 * meaning (e.g. mDy no longer the raw finger delta, or mScrollRunnable no longer calling this) would not.
 * <p>
 * ⚠️ R8: kept by rule 8 in app/proguard-rules.pro and anchored in verifyExtensionAbi, so a release build
 * fails if this class or ItemTouchHelper leaves this package.
 * <p>
 * Known edge: picking up a row that is still settling from a previous drop offsets mInitialTouchY by
 * that animation's position (onInterceptTouchEvent, :330-332), so the finger estimate is off by it.
 */
public class EdgeZoneItemTouchHelper extends ItemTouchHelper {

    private static final float ZONE_DP = 64f;
    private static final float MIN_STEP_DP = 2f;
    private static final float MAX_STEP_DP = 20f;

    public EdgeZoneItemTouchHelper(@NonNull Callback callback) {
        super(callback);
    }

    @Override
    boolean scrollIfNecessary() {
        if (mSelected == null || mRecyclerView == null) return super.scrollIfNecessary();
        RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
        if (lm == null || !lm.canScrollVertically()) return super.scrollIfNecessary();
        float density = mRecyclerView.getResources().getDisplayMetrics().density;
        float zone = ZONE_DP * density;
        float finger = mInitialTouchY + mDy;
        float top = mRecyclerView.getPaddingTop() + zone;
        float bottom = mRecyclerView.getHeight() - mRecyclerView.getPaddingBottom() - zone;
        // Gated on the drag's direction like the stock trigger, so a row picked up inside a zone does not
        // start scrolling until it is moved toward that edge.
        float depth;
        if (mDy < 0 && finger < top) depth = finger - top;
        else if (mDy > 0 && finger > bottom) depth = finger - bottom;
        else return false;
        float ratio = Math.min(1f, Math.abs(depth) / zone);
        int step = (int) (Math.signum(depth)
                * (MIN_STEP_DP + (MAX_STEP_DP - MIN_STEP_DP) * ratio) * density);
        if (step == 0) return false;
        mRecyclerView.scrollBy(0, step);
        return true;
    }
}
