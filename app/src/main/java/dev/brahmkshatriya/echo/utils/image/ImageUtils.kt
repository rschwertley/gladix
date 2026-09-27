package dev.brahmkshatriya.echo.utils.image

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.lifecycle.findViewTreeLifecycleOwner
import coil3.Image
import coil3.asDrawable
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.lifecycle
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import coil3.transform.Transformation
import dev.brahmkshatriya.echo.common.models.ImageHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

object ImageUtils {

    private fun <T> tryWith(print: Boolean = false, block: () -> T): T? {
        return try {
            block()
        } catch (e: Throwable) {
            if (print) e.printStackTrace()
            null
        }
    }

    // CancellationException MUST propagate. Swallowing it (the old `catch (e: Throwable)` alone) breaks
    // structured concurrency: the suspension point returns null *normally* in an already-cancelled
    // coroutine, so the caller's tail keeps running after its scope died. That is exactly how
    // PlayerTvFragment.configureColors reached requireContext() on a fragment whose lifecycleScope had
    // already been cancelled by activity recreation (Fragment.performDestroy → DESTROYED → scope cancel
    // precedes performDetach → mHost = null, so the throw proves the resume happened post-cancellation).
    //
    // It also makes null MEANINGFUL again: callers can now treat null as "no image / load failed" — the
    // case that legitimately paints a default — without conflating it with "we were cancelled", which
    // previously let a dying fragment write nulls into activity-scoped state (uiViewModel.playerColors)
    // that outlives it.
    //
    // Secondary benefit: the old path also ran e.printStackTrace() on every cancellation (print defaults
    // to true and neither caller overrides it), so routine cancellations — feed scrolling, flow transforms
    // — spammed System.err with JobCancellationException lines carrying no stack (kotlinx sets an empty
    // stack unless kotlinx.coroutines.debug is on). That noise goes away.
    //
    // Deliberately NOT applied to the non-suspend tryWith above: no coroutine, nothing to cancel.
    private suspend fun <T> tryWithSuspend(print: Boolean = true, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (print) e.printStackTrace()
            null
        }
    }

    private fun View.enqueue(builder: ImageRequest.Builder) =
        context.imageLoader.enqueue(builder.build())

    fun ImageHolder?.loadInto(
        imageView: ImageView, placeholder: Int? = null, errorDrawable: Int? = null
    ) = tryWith {
        val request = createRequest(imageView.context, placeholder, errorDrawable)
        request.target(imageView)
        imageView.enqueue(request)
    }

    // Rounds the DECODED bitmap on its ACTUAL edges after scaling the cover DOWN to FIT (see
    // FitRoundedCornersTransformation) — nothing is cropped, and the rounding lands on the cover itself
    // rather than the view rect. (A ShapeableImageView shape only rounds the view rect, so a
    // fitCenter/letterboxed cover would keep square corners with the shape rounding empty letterbox space;
    // and Coil's own RoundedCornersTransformation fills-and-crops. This does neither.) Used by the TV
    // full-screen cover (radius matches @style/TvCoverShape).
    fun ImageHolder?.loadRoundedInto(
        imageView: ImageView, cornerRadiusDp: Float, placeholder: Int? = null
    ) = tryWith {
        val radiusPx = cornerRadiusDp * imageView.resources.displayMetrics.density
        val request = createRequest(
            imageView.context, placeholder, null, FitRoundedCornersTransformation(radiusPx)
        )
        request.target(imageView)
        imageView.enqueue(request)
    }

    fun ImageHolder.getCachedDrawable(context: Context): Drawable? {
        val key = diskId ?: return null
        return context.imageLoader.diskCache?.openSnapshot(key)?.use {
            Drawable.createFromPath(it.data.toFile().absolutePath)
        }
    }

    fun <T : View> ImageHolder?.loadWithThumb(
        view: T, thumbnail: Drawable? = null,
        error: Int? = null,
        // Invoked ONLY for a real terminal outcome from Coil (success or error), never for the
        // synchronous thumbnail pre-set below. `onDrawable` cannot tell the two apart — it fires for
        // both — which is exactly how a caller ends up recording "this load completed" on the strength
        // of a cached thumbnail or a placeholder. A caller that keeps completion state (PlayerTrackAdapter
        // gates its cover block on lastBoundMediaId and its retry on coverDrawable) must key that state
        // off THIS callback. Null for every other call site, which keeps them byte-identical.
        // Note a cancelled request invokes NEITHER: RealImageLoader.execute passes request.target on the
        // success and error branches but not on the CancellationException branch, so a cancelled load
        // leaves the view untouched and no bookkeeping is recorded — which is the correct outcome, since
        // the caller's retry path must stay armed.
        onDelivered: (T.(Drawable?) -> Unit)? = null,
        // TRACE (2026-08-29, temporary). Reports the Coil DataSource of a terminal outcome -
        // MEMORY_CACHE / MEMORY / DISK / NETWORK, or "ERROR". Null at every other call site, so the
        // request built below is byte-identical for them: `listener` is only attached when non-null.
        // Exists because the target lambdas receive an Image, not an ImageResult, so the DataSource is
        // otherwise unreachable from a caller. REMOVE WITH THE TRACE.
        // ⚠️ CONDITION UNRESOLVED (2026-09-12), NOT "waiting on something specific". Reviewed in
        // the temporary-logging inventory and left in: NOBODY CURRENTLY KNOWS whether this trace is still
        // wanted - the investigation it belonged to is not identified in the record, and no capture this
        // month has used it. Recorded as unresolved rather than as KEEP, because implying it waits on a
        // named condition would be false. Whoever next touches image loading should decide it; until then
        // it is neither safe to strip blind nor legitimately "pending".
        // Applies equally to warmIssued / warmDone below.
        onSource: ((String) -> Unit)? = null,
        onDrawable: T.(Drawable?) -> Unit
    ) = tryWith(true) {
        tryWith(false) { onDrawable(view, thumbnail) }
        val request = createRequest(view.context, null, error)
        if (onSource != null) request.listener(
            onSuccess = { _, result -> tryWith(false) { onSource(result.dataSource.name) } },
            onError = { _, _ -> tryWith(false) { onSource("ERROR") } }
        )
        fun setDrawable(image: Image?) {
            val drawable = image?.asDrawable(view.resources)
            tryWith(false) { onDrawable(view, drawable) }
            tryWith(false) { onDelivered?.invoke(view, drawable) }
        }
        request.target({}, ::setDrawable, ::setDrawable)
        // ⚠️ NO PER-VIEW COALESCING. This applies to EVERY loadWithThumb call site in the app.
        // Coil disposes the previous request for a view in ViewTargetRequestDelegate.start(), via
        // target.view.requestManager.setRequest(this) - that is what makes "last request wins" true for a
        // normal ImageView load. Our lambda target takes the LifecycleRequestDelegate branch instead,
        // which never touches requestManager. So multiple requests can target the SAME ImageView at once,
        // none cancels another, and DELIVERY ORDER IS NOT GUARANTEED: whichever finishes last paints.
        // This was an unweighed cost of choosing the lambda target for SizeResolver.ORIGINAL and to skip
        // ViewTargetRequestDelegate.assertActive()'s isAttachedToWindow check (see below) - both of which
        // we do want. Callers that reuse a view across items MUST guard their own paint callback against
        // a superseded load; PlayerTrackAdapter does this with pendingMediaId. If you add a call site that
        // paints into a recycled or reused view, it needs the same guard.
        //
        // This load is ENQUEUED and its target is a lambda, NOT a ViewTarget, so
        // RequestService.requestDelegate takes the `request.lifecycle ?: findLifecycle()` branch and
        // findLifecycle resolves from the request CONTEXT (view.context -> the Activity).
        // RealImageLoader.execute then awaitStarted()s on that Lifecycle for every enqueued request, so
        // a cover enqueued while the Activity is STOPPED (screen off) does not execute at all until
        // ON_START — deferrals of several minutes were measured. Callers must not treat "enqueued" as
        // "will arrive soon"; that is what onDelivered above exists for.
        // The lambda target also means resolveSizeResolver() returns SizeResolver.ORIGINAL rather than
        // ViewSizeResolver(view), so these loads do NOT stall on a view that measures 0x0 (GONE or
        // not yet laid out) the way a ViewTarget load would.
        view.enqueue(request)
    }

    private val circleCrop = CircleCropTransformation()
    private val squareCrop = SquareCropTransformation()
    fun <T : View> ImageHolder?.loadAsCircle(
        view: T,
        placeholder: Int? = null,
        error: Int? = null,
        onDrawable: (Drawable?) -> Unit
    ) = tryWith {
        val request = createRequest(view.context, placeholder, error, circleCrop)
        fun setDrawable(image: Image?) {
            val drawable = image?.asDrawable(view.resources)
            tryWith(false) { onDrawable(drawable) }
        }
        request.target(::setDrawable, ::setDrawable, ::setDrawable)
        view.enqueue(request)
    }

    suspend fun ImageHolder?.loadDrawable(
        context: Context
    ) = tryWithSuspend {
        // Headless (no view target): run Coil's interceptor chain off Main. execute() otherwise
        // coordinates on Dispatchers.Main.immediate by default (RealImageLoader wraps in async(main) +
        // withContext(EmptyCoroutineContext)), so decode/transform/cache land on Main. View-target loads
        // (loadInto/enqueue) are untouched and keep their Main callbacks.
        val request = createRequest(context, null, null)
            .interceptorCoroutineContext(Dispatchers.IO)
        context.imageLoader.execute(request.build()).image?.asDrawable(context.resources)
    }

    suspend fun ImageHolder?.loadAsCircleDrawable(
        context: Context
    ) = tryWithSuspend {
        // Headless: keep Coil's chain off Main (see loadDrawable). This is the AppShortcuts path.
        val request = createRequest(context, null, null, circleCrop)
            .interceptorCoroutineContext(Dispatchers.IO)
        context.imageLoader.execute(request.build()).image?.asDrawable(context.resources)
    }

    fun ImageView.loadBlurred(drawable: Drawable?, radius: Float, onLoaded: (() -> Unit)? = null) = tryWith {
        if (drawable == null) { setImageDrawable(null); return@tryWith }
        val builder = ImageRequest.Builder(context)
            .data(drawable)
            .transformations(BlurTransformation(context, radius))
            .lifecycle(findViewTreeLifecycleOwner())
            .target({}, {}) { image ->
                setImageDrawable(image.asDrawable(resources))
                onLoaded?.invoke()
            }
        enqueue(builder)
    }

    // Blur a cover by IDENTITY (ImageHolder) straight into the view — the background equivalent of the mini
    // bar's identity load. Unlike loadBlurred(Drawable), this doesn't need a pre-resolved page drawable, so
    // the Ken Burns background is no longer coupled to an attached ViewPager holder (which is null/detached
    // after a screen-off auto-advance). No crop transform, matching the existing full-bleed blurred look.
    fun ImageView.loadBlurred(cover: ImageHolder?, radius: Float) = tryWith {
        if (cover == null) { setImageDrawable(null); return@tryWith }
        val builder = ImageRequest.Builder(context)
        createRequest(cover, builder)
        builder.transformations(BlurTransformation(context, radius))
            .lifecycle(findViewTreeLifecycleOwner())
            .target({}, {}) { image -> setImageDrawable(image.asDrawable(resources)) }
        enqueue(builder)
    }

    private val ImageHolder.diskId
        get() = when (this) {
            is ImageHolder.NetworkRequestImageHolder -> request.toString().hashCode().toString()
            else -> null
        }

    private fun createRequest(
        imageHolder: ImageHolder,
        builder: ImageRequest.Builder,
    ) = imageHolder.run {
        builder.diskCacheKey(diskId)
        when (this) {
            is ImageHolder.ResourceUriImageHolder -> builder.data(uri)
            is ImageHolder.NetworkRequestImageHolder -> {
                val headerBuilder = NetworkHeaders.Builder()
                request.headers.forEach { (key, value) ->
                    headerBuilder[key] = value
                }
                builder.httpHeaders(headerBuilder.build())
                builder.data(request.url)
            }

            is ImageHolder.ResourceIdImageHolder -> builder.data(resId)
            is ImageHolder.HexColorImageHolder -> builder.data(hex.toColorInt().toDrawable())
        }
    }

    // Warm the memory cache for a cover, by IDENTITY, with NO target. Nothing is painted; the only effect
    // is that the decoded bitmap is in Coil's memory cache when somebody else asks for it.
    //
    // WHY IT MATCHES: the request is built by the SAME private createRequest(context, null, null) that
    // loadWithThumb uses (:119 passes a null error at both PlayerTrackAdapter call sites), so data,
    // diskCacheKey, headers and transformations are identical. With no target, Coil resolves Size.ORIGINAL,
    // which is also what loadWithThumb's LAMBDA target resolves - so the memory-cache keys agree. That key
    // agreement IS the mechanism; if createRequest ever diverges per call site, this stops working silently.
    //
    // ⚠️ PASS AN ACTIVITY/FRAGMENT CONTEXT, NOT applicationContext. Coil resolves
    // `request.lifecycle ?: findLifecycle(context)`; an Activity context parks this request on the Activity
    // lifecycle exactly like every other load in this file (see the note at :140), so it does NOT execute
    // while the screen is off - it sits AT THE GATE and is released at ON_START. That is deliberate, and it
    // is the property being tested. An applicationContext would escape the gate and decode while
    // backgrounded, which buys an earlier warm but costs decoding when the system is trying to reclaim, and
    // a request that is no longer cancelled when the Activity dies. It would also stop reproducing the
    // configuration that was actually observed to work.
    // TRACE (2026-08-29, temporary). Two counters, because "the warm did not help" has three causes and
    // the wake line could not tell them apart:
    //   warmIssued == 0            -> NEVER RAN. The collector did not reach warmMemoryCache at all.
    //   warmIssued > warmDone      -> RAN AND PARKED. Enqueued, still waiting on the lifecycle gate (or
    //                                 in flight). enqueue() resolves findLifecycle = true
    //                                 (RealImageLoader:98), so an Activity context parks until ON_START.
    //   warmDone == warmIssued > 0 -> RAN AND COMPLETED. The entry should be in the memory cache, so a
    //                                 NETWORK read at bind is a KEY MISMATCH, not a missing entry.
    // Absolute counters rather than per-wake deltas: they only ever grow, so any two consecutive wake
    // lines give the delta, and there is no reset to get wrong. REMOVE WITH THE TRACE.
    val warmIssued = AtomicInteger(0)
    val warmDone = AtomicInteger(0)

    fun ImageHolder?.warmMemoryCache(context: Context) = tryWith {
        if (this == null) return@tryWith
        val request = createRequest(context, null, null)
        request.listener(
            onSuccess = { _, _ -> warmDone.incrementAndGet() },
            onError = { _, _ -> warmDone.incrementAndGet() }
        )
        warmIssued.incrementAndGet()
        context.imageLoader.enqueue(request.build())
    }

    private fun ImageHolder?.createRequest(
        context: Context,
        placeholder: Int?,
        errorDrawable: Int?,
        vararg transformations: Transformation
    ): ImageRequest.Builder {
        val builder = ImageRequest.Builder(context)
        var error = errorDrawable
        if (error == null) error = placeholder

        if (this == null) {
            if (error != null) builder.data(error)
            return builder
        }
        createRequest(this, builder)
        placeholder?.let { builder.placeholder(it) }
        error?.let { builder.error(it) }
        val list = if (crop) listOf(squareCrop, *transformations) else transformations.toList()
        if (list.isNotEmpty()) builder.transformations(list)
        return builder
    }
}