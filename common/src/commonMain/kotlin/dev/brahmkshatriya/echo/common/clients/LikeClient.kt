package dev.brahmkshatriya.echo.common.clients

import dev.brahmkshatriya.echo.common.models.EchoMediaItem

/**
 * Used to like or unlike an item. with the [EchoMediaItem.isLikeable] set to true.
 */
interface LikeClient {
    /**
     * Likes or unlikes an item.
     *
     * @param item the item to like or unlike.
     * @param shouldLike whether the item should be liked or unliked.
     */
    suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean)

    /**
     * Checks if an item is liked.
     *
     * @param item the item to check.
     * @return true if the item is liked, false otherwise.
     */
    suspend fun isItemLiked(item: EchoMediaItem): Boolean
}