package dev.brahmkshatriya.echo.extensions.cache

import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.ui.common.ErrorCategory
import dev.brahmkshatriya.echo.ui.common.classify
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException

/**
 * ⚠⚠ THE REPORT FOR A CAPABILITY PROBE THAT FAILED AND WAS DEGRADED TO "UNKNOWN".
 * [Cached.probeOrNull] stops a failing isItemSaved / isFollowing / getFollowersCount / isItemLiked /
 * isItemHidden from failing the whole media load - which on the playback path means failing a
 * track's prepare (1146). That is the right behaviour and it is also SILENT, so the failure has to
 * land somewhere: this is that somewhere.
 *
 * ⚠⚠ CRASHLYTICS ONLY, NEVER throwableFlow, AND THAT IS THE POINT OF THE SPLIT.
 * A probe failure is usually a property of the SESSION, not of the track - a refused gateway call,
 * a dead token - so a queue of tracks produces the same failure over and over. Through throwableFlow
 * that is a snackbar per track, on a screen where nothing is wrong from the user's side and there is
 * nothing for them to do. The user already sees the honest outcome: an unfilled heart.
 *
 * ⚠️ ONCE PER EXTENSION PER PROCESS. Keyed on the extension id alone rather than on
 * (extension, probe): a dead session fails every probe it reaches, and five reports saying the same
 * thing is the flood this exists to prevent. The first one carries the cause chain, which is the part
 * that identifies the failure.
 * ⚠️ NO CUSTOM KEYS, DELIBERATELY. Crashlytics custom keys are sticky per process -
 * App.kt's collector writes `deezer_gateway` on EVERY report precisely so a skipped write cannot
 * leave the previous one's value attached to an unrelated error (doc note 4 in CrashKeys). A key
 * written only here would be exactly that trap: set on this report, never cleared, read on the next.
 * Everything needed is in the message and the cause chain instead - and the chain is enough, because
 * DeezerGatewayException carries method/errorText on toString() and ExtensionUtils.get has already
 * wrapped the original in AppException naming the extension.
 *
 * REMOVAL: none. This is the standing report for a degraded probe, not a temporary capture.
 */
object CapabilityProbeFailure {

    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun record(extensionId: String, probe: String, throwable: Throwable) {
        // [2026-11-11] A PLAIN NETWORK FAILURE IS NOT REPORTED (1177: SocketTimeoutException in the TLS handshake under Deezer's
        // liked fetch). It is the user's connection, so there is nothing to act on; and every liked-list failure groups into one
        // Crashlytics issue, so muting that issue to quiet these would also hide the real causes it exists to catch.
        // Checked BEFORE the once-per-process slot is taken, so a dropped network failure does not use up the report a later
        // real failure in the same process would make. The media state, probesDegraded and Deezer's 60 s cooldown are untouched:
        // this only decides whether Crashlytics hears about it.
        if (isPlainNetworkFailure(throwable)) return
        if (!reported.add(extensionId)) return
        if (!BuildConfig.HAS_FIREBASE) return
        runCatching {
            FirebaseCrashlytics.getInstance().recordException(
                CapabilityProbeFailed(
                    "Capability probe " + probe + " failed for extension " + extensionId +
                        " - the media state was built with it unknown",
                    throwable
                )
            )
        }
    }

    /**
     * ErrorCategory.classify is the classifier: it is the shared one (AA tile, snackbar parity via ErrorCategoryTest), walks the
     * whole cause chain through AppException, and its Network arm already holds the timeout, DNS, refused and no-route types.
     * PlayerEventListener.skipFamilyOf groups the same transport types but is private and keyed on a PlaybackException.
     * The two types added HERE ONLY: a TLS handshake that failed, and OkHttp's call-timeout InterruptedIOException. They are kept
     * out of classify on purpose, since changing it would change the AA and snackbar wording, which must move with getTitle.
     * LoginOrAuth found first keeps reporting, as classify returns at the first node it recognises.
     * Deezer's LikedFetchFailed carries a message-less copy of its network cause so these type checks can see it (scrubbedForReport).
     */
    private fun isPlainNetworkFailure(throwable: Throwable): Boolean = when (classify(throwable)) {
        ErrorCategory.Network -> true
        ErrorCategory.LoginOrAuth -> false
        ErrorCategory.Generic -> generateSequence(throwable) { it.cause }.take(MAX_CHAIN)
            .any { it is SSLHandshakeException || it is InterruptedIOException }
    }

    private const val MAX_CHAIN = 8

    class CapabilityProbeFailed(message: String, cause: Throwable) : Exception(message, cause)
}
