package dev.brahmkshatriya.echo.ui.media

import android.os.Bundle
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.databinding.FragmentMediaBinding
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyGradient
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.icon
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.placeHolder
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.ui.playlist.delete.DeletePlaylistBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadWithThumb
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.UiUtils.configureAppBar
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class MediaFragment : Fragment(R.layout.fragment_media), MediaDetailsFragment.Parent {
    companion object {
        fun getBundle(extensionId: String, item: EchoMediaItem, loaded: Boolean) = Bundle().apply {
            putString("extensionId", extensionId)
            putSerialized("item", item)
            putBoolean("loaded", loaded)
        }
    }

    val args by lazy { requireArguments() }
    val extensionId by lazy { args.getString("extensionId")!! }
    val item by lazy { args.getSerialized<EchoMediaItem>("item")!!.getOrThrow() }
    val loaded by lazy { args.getBoolean("loaded") }

    override val fromPlayer = false
    override val feedId by lazy { item.id }
    override val showInitialButtons get() = item !is Artist

    override val viewModel by viewModel<MediaViewModel> {
        parametersOf(true, extensionId, item, loaded, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMediaBinding.bind(view)
        setupTransition(view)
        applyBackPressCallback()
        binding.appBarLayout.configureAppBar { offset ->
            binding.appbarOutline.alpha = offset
            binding.coverContainer.alpha = 1 - offset
        }
        // Landscape (nav rail) only: the CTL/AppBar header is otherwise rail-unaware, so its
        // content (cover, back button, collapsed + expanded title) sits behind the left rail.
        // Pad the AppBar start by the rail inset so the whole header shifts into the content
        // area, matching how the detail list already insets via `combined`. start-only:
        // fitsSystemWindows still owns the top status-bar inset (no duplication). Portrait has
        // isRail == false, so this observer is never registered and the header is untouched.
        val uiViewModel by activityViewModel<UiViewModel>()
        if (uiViewModel.isRail) observe(uiViewModel.combined) {
            binding.appBarLayout.updatePaddingRelative(start = it.start)
        }
        binding.toolBar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.toolBar.setOnMenuItemClickListener {
            val item = viewModel.itemResultFlow.value?.getOrNull()?.item ?: item
            MediaMoreBottomSheet.show(
                this, parentFragmentManager,
                id, extensionId, item, !viewModel.isRefreshing
            )
            true
        }
        val isTV = requireContext().isTv()
        if (isTV) binding.appBarLayout.setExpanded(false, false)

        observe(viewModel.itemResultFlow) { result ->
            val item = result?.getOrNull()?.item ?: item
            // ⚠⚠ BOTH LINES ARE REQUIRED, AND THE CTL ONE IS THE LOAD-BEARING HALF.
            // Setting only the toolbar's title WORKS ONCE AND THEN SILENTLY STOPS. Decoded from
            // material-1.14.0.aar, CollapsingToolbarLayout.updateTitleFromToolbarIfNeeded() copies
            // the toolbar's title into the CTL ONLY WHILE THE CTL'S OWN TITLE IS STILL EMPTY:
            //     if (isEmpty(collapsingTitleHelper.getText()) && !isEmpty(toolbarTitle))
            //         setTitle(toolbarTitle);
            // and it is called from onMeasure, so it latches on the FIRST measure pass. After that
            // the CTL draws its own copy and never re-reads the toolbar again.
            // THE BUG THAT COST, so the cost of dropping this line is concrete: itemResultFlow is a
            // StateFlow starting null, so the first pass writes the UNLOADED item's title - and for a
            // Gladix share link that is the `n=` stub. Opening
            // .../gladix/o/?e=deezer&t=artist&i=278&n=Placeholder loaded the right artist and showed
            // "Placeholder" forever. Verified on device 2026-09-23.
            // ⚠️ NOT A LINK-ONLY BUG. Any entry that opens this page with a stub whose title
            // differs from the loaded one hits it; links merely guarantee the difference. The item is
            // NOT being merged anywhere - Cached.loadMedia returns MediaState.Loaded(item = new) and
            // DeezerTrackClient.loadTrack returns `fresh` for a bare stub. Do not go looking for a
            // merge; the loss is entirely in the CTL latch.
            // ⚠️ THE TOOLBAR LINE STAYS: the CTL suppresses the toolbar's own title view, so it
            // is not what you see, but it remains the value updateTitleFromToolbarIfNeeded reads and
            // anything reading toolBar.title keeps working. Setting both costs nothing.
            // ⚠️ AND THIS CANNOT RESIZE THE HEADER - checked before shipping, because the
            // AppBarLayout and the CTL are both wrap_content and the title now changes AFTER first
            // layout. In onMeasure the only text-derived height term is
            // getExpandedTextFullSingleLineHeight(), a function of the TEXT APPEARANCE and not of the
            // content or its line count. The one line-count term, extraMultilineTitleHeight, is gated
            // on extraMultilineHeightEnabled, which DEFAULTS FALSE (TypedArray.getBoolean(idx, false))
            // and is set nowhere here - not in fragment_media.xml, not in EchoCollapsingBar, not in
            // TopCollapsingBar. So header height is independent of the title and no jump is possible.
            // That flag is also the mechanism behind the recorded behaviour that a multi-line title
            // OVERLAPS THE COVER rather than growing the header - unchanged by this, but newly
            // reachable, since a long real title can now replace a short stub and wrap to maxLines=2.
            // ⚠️ AND IT UPDATES THE COLLAPSED TITLE TOO, which matters because TV collapses the
            // app bar on entry (isTV -> setExpanded(false)). There is ONE CollapsingTextHelper:
            // setTitle() writes collapsingTitleHelper.setText(), and draw() renders that same helper
            // interpolated by the expansion fraction. Collapsed and expanded are two renderings of
            // one string, not two strings.
            binding.toolBar.title = item.title.trim()
            binding.collapsingToolbar.title = item.title.trim()
            // ⚠️ NO `else` BRANCH, AND THAT IS SAFE ONLY BECAUSE THIS VIEW IS NOT RECYCLED. The artist
            // branch mutates `radius` and `matchConstraintMaxWidth` and nothing ever resets them. That
            // holds today because MediaFragment binds ONE coverContainer per page instance: an album page
            // gets a fresh view that was never made circular. IT WOULD STOP HOLDING the moment an artist
            // page and an album page share a fragment instance — the album would inherit the artist's
            // circle and 240dp cap, and it would present as "album covers are round sometimes", found by
            // looking rather than by any error.
            // NOT HYPOTHETICAL AS A SHAPE: while this lived in MediaHeaderAdapter.Success (2026-09-05 to
            // 2026-09-07) it DID carry an else branch resetting both, because a ViewHolder IS recycled.
            // That branch was correct there and is unnecessary here; it was dropped with the 2026-09-07
            // restore rather than lost. If this logic ever moves into a recycled holder again, the reset
            // must move with it.
            if (item is Artist) binding.coverContainer.run {
                val maxWidth = 240.dpToPx(context)
                radius = maxWidth.toFloat()
                updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintMaxWidth = maxWidth
                }
            }
            item.cover.loadInto(binding.cover, null, item.placeHolder)
            val gradientScope = viewLifecycleOwner.lifecycleScope
            item.background.loadWithThumb(view) { gradientScope.launch { applyGradient(view, it) } }
        }
        parentFragmentManager.setFragmentResultListener("reload", this) { _, data ->
            if (data.getString("id") == item.id) viewModel.refreshTracks()
        }
        parentFragmentManager.setFragmentResultListener("delete", this) { _, data ->
            val playlist = item as? Playlist ?: return@setFragmentResultListener
            DeletePlaylistBottomSheet.show(
                requireActivity(), extensionId, playlist, !viewModel.isRefreshing
            )
        }
        parentFragmentManager.setFragmentResultListener("deleted", this) { _, data ->
            if (data.getString("id") == item.id) parentFragmentManager.popBackStack()
        }
    }
}