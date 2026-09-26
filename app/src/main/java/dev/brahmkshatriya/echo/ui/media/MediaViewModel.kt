package dev.brahmkshatriya.echo.ui.media

import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadMedia
import kotlinx.coroutines.Dispatchers
import dev.brahmkshatriya.echo.extensions.exceptions.ExtensionNotFoundException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class MediaViewModel(
    extensionLoader: ExtensionLoader,
    downloader: Downloader,
    val app: App,
    loadFeeds: Boolean,
    val extensionId: String,
    val item: EchoMediaItem,
    val loaded: Boolean,
) : MediaDetailsViewModel(
    downloader, app, loadFeeds,
    extensionLoader.music.map { list -> list.find { it.id == extensionId } },
    extensionLoader
) {

    override fun getItem(): Triple<String, EchoMediaItem, Boolean> {
        val result = itemResultFlow.value?.getOrNull()?.item
        return Triple(
            extensionId,
            result ?: item,
            loaded || result != null
        )
    }

    init {
        var force = false
        viewModelScope.launch(Dispatchers.IO) {
            listOf(extensionFlow, refreshFlow).merge().collectLatest {
                itemResultFlow.value = null
                cacheResultFlow.value = null
                cacheResultFlow.value = Cached.getMedia<EchoMediaItem>(app, extensionId, item.id)
                    .getOrNull()?.let { Result.success(it) }
                // ⚠⚠ A NULL HERE HAS TWO MEANINGS AND THEY DEMAND OPPOSITE HANDLING. It used
                // to return silently for both, which left itemResultFlow null forever - and
                // MediaDetailsViewModel.isRefreshing IS `itemResultFlow.value == null`, so the page
                // SPUN FOR EVER. Not an error, not a no-op: a spinner that never resolves, which reads
                // as a slow network and so was never reported.
                //   music EMPTY      -> the extension list has not loaded yet. Returning is CORRECT;
                //                      this collector re-runs when it populates (extensionFlow is
                //                      music.map{find}, so a new list re-emits).
                //   music NON-EMPTY  -> the extension genuinely is not installed. Surface it.
                // ⚠️ WHY IT MATTERS NOW: Gladix share links carry an extension id, and sharing
                // one to someone who has Gladix but NOT that extension is the NORMAL outcome of
                // sharing, not an edge case. This is also the path the Deezer link Opener's hardcoded
                // "deezerApp" id would have taken had it ever shipped in this app.
                // ⚠️ GUARDED HERE RATHER THAN AT THE LINK PARSER, DELIBERATELY: this is where
                // the extension is resolved, so EVERY entry point benefits - https links, echo:// URIs,
                // launcher shortcuts for a since-removed extension. A parser-side check would also have
                // to await the list itself and then open a fragment across a suspension point, which
                // risks a transaction after onSaveInstanceState.
                // ⚠️ THE MESSAGE PATH EXISTS; WHAT IS UNTRACED IS WHETHER THIS SURFACE USES
                // IT. ExtensionNotFoundException already has a rendering - getFinalTitle maps it to
                // the removed-extension string - and it was made a TYPED exception (rather than a
                // bare one) specifically so its causes could classify. But that work was done for
                // the QUEUE AND PLAYBACK surfaces; whether the MEDIA DETAIL page renders it or just
                // shows an empty state has not been checked on device. isRefreshing does at least
                // become false, so the spinner stops either way.
                // ⚠⚠ AND FOR A UNIFIED ITEM THIS GUARD IS NEVER REACHED - THE FAILURE LANDS
                // EARLIER, ON A PATH THAT HAS ALREADY BEEN INVESTIGATED ONCE. A shared link builds
                // a stub Track(id, name) with NO extras, and UnifiedExtension routes by
                // extras[EXTENSION_ID] (loadStreamableMedia proxies to the underlying extension
                // through it), so `Map.extensionId` throws ExtensionNotFoundException(null) first.
                // PRECEDENT, July 2026: the same shape was investigated as a real bug -
                // "getExtensionId 'Extension id not found' during backfillQueue -> loadRadio",
                // diagnosed as "a radio item reached loadRadio WITHOUT its EXTENSION_ID stamp
                // (missing stamp on the item, NOT an unknown id)". A Gladix link constructs
                // exactly such an item, so this is the SECOND instance, not a new risk.
                // ⚠️ NOTE WHICH PATH THAT IS: the MISSING-STAMP one (UnifiedExtension's
                // Map.extensionId), not the stamped-but-uninstalled one (List<Extension<*>>.get,
                // still throwing bare and parked). Do not conflate them when reading either.
                // THE FIX IS IN THE LINK, NOT THE MESSAGE: emit the SUB-extension id rather than
                // "unified" - see the prerequisite at MediaDetailsViewModel.share.
                val extension = extensionFlow.value ?: run {
                    if (extensionLoader.music.value.isNotEmpty()) itemResultFlow.value =
                        Result.failure(ExtensionNotFoundException(extensionId))
                    return@collectLatest
                }
                itemResultFlow.value = loadMedia(
                    app, extension, MediaState.Unloaded(extension.id, item)
                )
                force = true
            }
        }
    }
}