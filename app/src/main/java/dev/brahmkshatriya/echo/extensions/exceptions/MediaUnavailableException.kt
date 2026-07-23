package dev.brahmkshatriya.echo.extensions.exceptions

/**
 * Thrown when a requested media item no longer exists on the extension's backend. Some extensions
 * (e.g. Deezer) return a BLANK item — an empty id — for deleted content instead of raising an error,
 * so the loadMedia wrong-item guard would otherwise surface a raw IllegalStateException. This is a
 * NORMAL condition (favorited album/track removed upstream), not a bug. The [message] is a localized,
 * user-facing string; ExceptionUtils.getFinalTitle renders it via its `?: throwable.message` fallback,
 * so it needs no dedicated getTitle branch and is not wrapped as an AppException.
 */
class MediaUnavailableException(override val message: String) : Exception()
