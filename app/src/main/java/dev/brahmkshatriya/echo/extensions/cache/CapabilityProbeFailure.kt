package dev.brahmkshatriya.echo.extensions.cache

import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.ui.common.ErrorCategory
import dev.brahmkshatriya.echo.ui.common.classify
import java.io.InterruptedIOException
import java.net.SocketException
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
        val message = "Capability probe " + probe + " failed for extension " + extensionId +
            " - the media state was built with it unknown"
        // [2026-11-12] A Deezer HTTP 403 refusal gets its own type, so it groups as its own issue (see CapabilityProbeRefused).
        // Every other cause records CapabilityProbeFailed exactly as before. Same once-per-extension slot, taken above.
        runCatching {
            FirebaseCrashlytics.getInstance().recordException(
                if (isDeezerRefusal(throwable)) CapabilityProbeRefused(message, throwable)
                else CapabilityProbeFailed(message, throwable)
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
    // [2026-11-13] SocketException added HERE ONLY (Crashlytics 0a70517c: a reset during Deezer's TLS handshake under the liked fetch,
    // the user's decision): for this report a dropped socket is the connection. classify keeps it Generic on purpose (a mid-stream
    // reset is a per-track transient for PlayerEventListener's retry-then-skip; ErrorCategoryTest's drift guard). Internal for
    // ErrorCategoryTest.
    internal fun isPlainNetworkFailure(throwable: Throwable): Boolean = when (classify(throwable)) {
        ErrorCategory.Network -> true
        ErrorCategory.LoginOrAuth -> false
        ErrorCategory.Generic -> generateSequence(throwable) { it.cause }.take(MAX_CHAIN)
            .any { it is SSLHandshakeException || it is InterruptedIOException || it is SocketException }
    }

    private const val MAX_CHAIN = 8

    // [2026-11-12] Byte-identical to DeezerExtension's LIKED_FETCH_REFUSED_MESSAGE; the type is not reachable from :app (renamed by
    // the extension's R8, loaded by its own class loader), so the text is the contract, as with App.DEEZER_AUTH_REJECTED_MESSAGE.
    // Changing either text silently sends the 403 back into CapabilityProbeFailed's issue.
    private const val DEEZER_REFUSED_MESSAGE = "Deezer refused the liked-list request (HTTP 403)"

    private fun isDeezerRefusal(throwable: Throwable): Boolean =
        generateSequence(throwable) { it.cause }.take(MAX_CHAIN).any { it.message == DEEZER_REFUSED_MESSAGE }

    class CapabilityProbeFailed(message: String, cause: Throwable) : Exception(message, cause)

    /**
     * [2026-11-12] Deezer refusing the liked list with an HTTP 403 (e8a9e550, 1178): a third-party refusal, kept apart so the
     * CapabilityProbeFailed issue can still be read for real causes. A SIBLING of CapabilityProbeFailed, not a subclass: in an
     * unminified build a subclass's stack would start with the shared base <init> frame, the HealthMonitor family-split defect
     * (see HealthMonitor.report). Its name survives R8 through proguard-rules.pro's `-keepnames class * extends
     * java.lang.Throwable`, which also stops it being merged with CapabilityProbeFailed. What Crashlytics keys on is theirs; this
     * changes the class name it sees, and both are recorded from the same frame.
     */
    class CapabilityProbeRefused(message: String, cause: Throwable) : Exception(message, cause)
}
