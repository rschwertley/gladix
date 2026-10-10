package dev.brahmkshatriya.echo.common.helpers

/**
 * Thrown when a requested media item no longer exists on the extension's backend. Some extensions
 * (e.g. Deezer) return a BLANK item — an empty id — for deleted content instead of raising an error,
 * so the loadMedia wrong-item guard would otherwise surface a raw IllegalStateException. This is a
 * NORMAL condition (favorited album/track removed upstream), not a bug. The [message] is a localized,
 * user-facing string; ExceptionUtils.getFinalTitle renders it via its `?: throwable.message` fallback,
 * so it needs no dedicated getTitle branch and is not wrapped as an AppException.
 *
 * ⚠⚠ IT LIVES IN :common BECAUSE IT IS A TYPE EXTENSIONS THROW, NOT ONLY ONE THE APP THROWS.
 * Moved here from the app's `extensions.exceptions` package on 2026-10-10 so Deezer's
 * all-qualities-exhausted throw can carry a TYPE instead of the bare Exception it carried before.
 * PlayerEventListener.skipFamilyOf classifies this as the Unavailable skip family; while the type was
 * unreachable from the extension, the leaf reaching skipFamilyOf was a plain `java.lang.Exception`
 * with no identity to classify, so every exhausted Deezer track landed in the RESIDUAL Error family
 * (Crashlytics 1fc0baac on build 1119) — among genuinely unanalysed faults, which is the one thing
 * that family exists not to hold.
 *
 * ⚠️ THE PACKAGE IS LOAD-BEARING, NOT COSMETIC. proguard-rules.pro rule 1 keeps
 * `dev.brahmkshatriya.echo.common.**`, so this name survives R8 un-renamed and is the SAME class on
 * both sides of the extension classloader boundary. A type kept in `app` cannot be named by an
 * extension at all, and a type an extension bundled itself would not be `is`-equal to ours here.
 *
 * [cause] is optional and defaults to null, so the single-argument call shape used before the move is
 * unchanged. Overriding `cause` as a val rather than calling `initCause` follows the pattern
 * AppException already uses — see AppException.NotSupported and AppException.Other.
 */
class MediaUnavailableException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception()
