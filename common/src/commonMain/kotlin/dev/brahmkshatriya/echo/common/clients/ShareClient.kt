package dev.brahmkshatriya.echo.common.clients

import dev.brahmkshatriya.echo.common.models.EchoMediaItem

/**
 * Used for getting a link to share media items with [EchoMediaItem.isShareable] set to true.
 */
// ⚠⚠ THIS INTERFACE CARRIES TWO DIFFERENT CLAIMS AND ONLY STATES ONE. Its presence means
// "I can mint a web URL for my items". Its ABSENCE is read, throughout the app, as "my items are
// unreachable elsewhere" - and those are not the same thing:
//   a NETWORK extension with no share support is RESOLVABLE-BUT-URL-LESS. Another install of the
//     same extension could open its items by id perfectly well; it simply has no public URL to mint.
//   OfflineExtension is NOT RESOLVABLE AT ALL. Its items are local files on one device, so no id it
//     produces means anything to anyone else, with or without a URL.
// ⚠️ THE ACCURATE MODEL WOULD BE A SECOND CAPABILITY - something meaning "my items are
// resolvable by id" - and it is NOT EXPRESSIBLE TODAY: adding it is a common-module ABI change plus
// per-extension adoption, the same cost already declined for a URL-to-item client.
// WHERE THIS BITES: the Gladix share-link action needs "resolvable", not "has a URL", and has to
// use this interface as a proxy for it. See the note at MediaDetailsViewModel.share.
interface ShareClient {
    /**
     * When the user wants to share the given media item
     *
     * @param item The media item to share
     * @return url of the shared item
     */
    suspend fun onShare(item: EchoMediaItem): String
}