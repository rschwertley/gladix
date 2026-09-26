package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.DeezerSession.DeezerCredentials
import dev.brahmkshatriya.echo.extension.api.DeezerAlbum
import dev.brahmkshatriya.echo.extension.api.DeezerArtist
import dev.brahmkshatriya.echo.extension.api.DeezerMedia
import dev.brahmkshatriya.echo.extension.api.DeezerPlaylist
import dev.brahmkshatriya.echo.extension.api.DeezerRadio
import dev.brahmkshatriya.echo.extension.api.DeezerSearch
import dev.brahmkshatriya.echo.extension.api.DeezerShow
import dev.brahmkshatriya.echo.extension.api.DeezerTrack
import dev.brahmkshatriya.echo.extension.api.DeezerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import okio.BufferedSource
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ⚠⚠ TWO STRINGS, DELIBERATELY: `message` IS FOR THE USER, `errorText` IS FOR THE LOG.
// Do not collapse them. The generic message is what reaches a snackbar, where "REQUEST_ERROR=Wrong
// parameters" would be noise; errorText is what makes a capture decidable, and dropping it would have
// left both gateway investigations exactly where they started. Precedent to avoid: ClientException
// .LoginRequired carries nothing, so it cannot distinguish "user must sign in" from "internal token went
// stale, no user action possible".
// `method` is on the exception rather than folded into the text so a future classifier can branch on it
// without re-parsing a sentence - the same reason ErrorCategory takes types rather than strings.
class DeezerGatewayException(
    val method: String,
    val errorText: String,
) : Exception("Deezer refused this request.") {
    override fun toString() = "DeezerGatewayException(method=$method, error=$errorText)"
}

/**
 * Deezer accepted the request and REFUSED THE CREDENTIALS: HTTP 200 whose body carries an explicit
 * `error`. Thrown only from getArlByEmail, only when Deezer said why.
 *
 * ⚠⚠ A TYPE, NOT A STRING MATCH ON "authenticate user failed", and that is the whole design.
 * Matching the sentence would break on any rewording by Deezer and would then SILENTLY STOP routing
 * to the login prompt - the user would be back to an unexplained playback error with nothing to
 * indicate the guard had stopped firing. Same caution as requireJsonObject in this file: test for
 * what we require (an explicit error from Deezer) rather than sniffing for what we do not want.
 * ⚠⚠ TWO STRINGS, DELIBERATELY: `message` IS FOR THE USER, `errorText` IS FOR THE LOG.
 * Do not collapse them. Same rule, and the same reason, as DeezerGatewayException twelve lines
 * above: the generic message is what reaches a snackbar, where Deezer's internal wording would be
 * noise, and errorText is what makes a report decidable. It rides on toString(), so it still
 * appears in the stack trace even though it is absent from the user-facing message.
 * ⚠️ THE MESSAGE STATES WHAT HAPPENED AND CLAIMS NOTHING ABOUT WHY, and that is a
 * constraint rather than a style choice - see the unverified note below. "Deezer did not accept
 * these credentials" is true of a wrong password, a suspended account, a forced reset and a
 * region block alike. DO NOT rewrite it to say "wrong password".
 *
 * ⚠️ [REVERSED 2026-09-22] THIS MESSAGE WAS DELIBERATELY BYTE-IDENTICAL TO THE PLAIN
 * Exception IT REPLACED, AND THE REVERSAL IS ALSO DELIBERATE - neither is an oversight.
 *   WHY IT WAS: the muted Crashlytics issue would keep its grouping and its history across the
 *     moment the behaviour changed, so the fix could be judged against the same issue that
 *     motivated it rather than against a fresh one with no past.
 *   WHY IT IS NOT ANY MORE: the fix is CONFIRMED FIRING IN THE FIELD (2026-09-22 - the typed
 *     exception was raised and the retry broke, exactly as designed), so the grouping has already
 *     done its job. Forking the issue now costs a history nobody needs again; a readable message
 *     is worth more. EXPECT A NEW ISSUE TO APPEAR - that is this change, not a new defect.
 * ⚠️ NOT thrown for "no access_token in response" / "no ARL in response". Those are SHAPE
 * surprises, not refusals - Deezer said nothing - so they stay plain Exceptions and stay retryable.
 *
 * ⚠⚠ THE LOAD-BEARING ASSUMPTION IS "AUTH ERRORS ARRIVE AS HTTP 200 WITH A POPULATED `error`
 * OBJECT", AND IT IS ESTABLISHED RATHER THAN ASSUMED HERE. Two independent supports:
 *   ⚠️ ESTABLISHED JUNE 2026, not inferred from this report: "Deezer signals auth via a
 *     populated error JSON object on HTTP 200, already handled by the CSRF/ARL-refresh path - proven
 *     safe because the existing isSuccessful-before-errorObj ordering works today, WHICH MEANS AUTH
 *     ERRORS MUST BE 200." That is a proof from working behaviour, not a reading of a sample.
 *   ⚠️ AND THIS FILE'S OWN CONTROL FLOW AGREES: getToken throws ClientException.LoginRequired
 *     on 403 and "Unexpected code" on every other non-2xx, so the string this type replaces was only
 *     ever reachable on a 200. Rate limits, captcha walls, endpoint changes and network failures
 *     cannot produce it.
 * ⚠️ WHAT IS *NOT* ESTABLISHED, AND MUST NOT BE READ AS SETTLED: the exhaustive account-side
 * MEANING of a given error string. "authenticate user failed" is assumed to be a wrong password, but
 * a suspended account, a forced password reset or a region block could plausibly share it -
 * user_auth.php is undocumented, and deezer-py and GitHub code search were both unreachable when this
 * was written, so nothing confirms it. It does not change the handling: every one of those variants
 * means "your stored credentials no longer work, sign in again". It would change a message that
 * tried to name the cause - so do not write one.
 */
class DeezerAuthRejectedException(
    val errorText: String,
) : Exception(
    // ⚠️ App.kt matches this EXACT text (DEEZER_AUTH_REJECTED_MESSAGE) to keep credential
    // refusals out of Crashlytics - the type is not reachable from :app. Changing it re-enables
    // reporting silently.
    "Deezer did not accept these credentials."
) {
    override fun toString() = "DeezerAuthRejectedException(error=$errorText)"
}

class DeezerApi(private val session: DeezerSession) {

    companion object {
        private val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            useArrayPolymorphism = true
        }

        private const val APP_API_KEY =
            "4VCYIJUCDLOUELGD1V8WBVYBNVDYOXEWSLLZDONGBBDFVXTZJRXPR29JRLQFO6ZE"

        private const val CLIENT_ID = "447462"

        private const val CLIENT_SECRET = "a83bf7f38ad2f137e444727cfc3775cf"
    }

    private val language: String
        get() = session.settings?.getString("lang") ?: Locale.getDefault().toLanguageTag()

    private val country: String
        get() = session.settings?.getString("country") ?: Locale.getDefault().country

    val langCode: String
        get() = language.substringBefore("-")

    private val credentials: DeezerCredentials
        get() = session.credentials

    private val arl: String
        get() = credentials.arl

    private val sid: String
        get() = credentials.sid

    private val token: String
        get() = credentials.token

    private val userId: String
        get() = credentials.userId

    private val licenseToken: String
        get() = credentials.licenseToken

    private val email: String
        get() = credentials.email

    private val pass: String
        get() = credentials.pass

    private fun createOkHttpClient(useProxy: Boolean, login: Boolean = false): OkHttpClient {
        val configuredProxy = session.settings
            ?.getString("proxy")
            .takeIf { !it.isNullOrEmpty() }
        return OkHttpClient.Builder().apply {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(10, TimeUnit.SECONDS)
            writeTimeout(15, TimeUnit.SECONDS)
            // API-only clients (proxy or login path) get a hard per-call ceiling so a stalled
            // Deezer endpoint cannot block stream preparation indefinitely. The no-proxy client
            // (clientNP) is also used for audio streaming where the body read takes minutes, so
            // callTimeout is intentionally omitted there.
            if (useProxy || login) callTimeout(25, TimeUnit.SECONDS)
            if (useProxy && configuredProxy != null) {
                val proxy = if (login && configuredProxy != "uk2.proxy.murglar.app") "uk1.proxy.murglar.app" else configuredProxy
                sslSocketFactory(createTrustAllSslSocketFactory(), createTrustAllTrustManager())
                hostnameVerifier { _, _ -> true }
                proxy(
                    Proxy(
                        Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(proxy, 3128)
                    )
                )
            }
        }.build()
    }

    private fun createTrustAllSslSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(createTrustAllTrustManager())
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    @Suppress("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun createTrustAllTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
    }

    val client: OkHttpClient  by lazy { createOkHttpClient(useProxy = true) }
    val clientLog: OkHttpClient by lazy { createOkHttpClient(useProxy = true , true) }
    val clientNP: OkHttpClient by lazy { createOkHttpClient(useProxy = false) }

    private val staticHeaders: Headers by lazy {
        Headers.Builder().apply {
            add("Accept", "*/*")
            add("Cache-Control", "max-age=0")
            add("Connection", "keep-alive")
            add("Content-Type", "application/json")
            add("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36")
            add("X-User-IP", "1.1.1.1")
            add("x-deezer-client-ip", "1.1.1.1")
        }.build()
    }

    private val staticAppHeaders: Headers by lazy {
        Headers.Builder().apply {
            add("Content-Type", "application/json")
            add("User-Agent", "Deezer/8.0.44.4 (Android; 12; Mobile; us) Google sdk_gphone64_x86_64")
        }.build()
    }

    private fun String.sanitizeHeader() = replace("\n", "").replace("\r", "").trim()

    private fun getHeaders(method: String? = ""): Headers {
        val safeArl = arl.sanitizeHeader()
        val safeSid = sid.sanitizeHeader()
        return staticHeaders.newBuilder().apply {
            if (method != "user.getArl") {
                add("Cookie", "arl=$safeArl; sid=$safeSid")
            } else {
                add("Cookie", "sid=$safeSid")
            }
            add("Accept-Language", "$language,*")
            add("Content-Language", language)
            add("x-deezer-user", userId.sanitizeHeader())
        }.build()
    }

    suspend fun callApi(
        method: String,
        paramsBuilder: JsonObjectBuilder.() -> Unit = {},
        gatewayInput: String? = "",
        np: Boolean = false
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https").host("www.deezer.com")
            .addPathSegments("ajax/gw-light.php")
            .addQueryParameter("method", method)
            .addQueryParameter("input", "3")
            .addQueryParameter("api_version", "1.0")
            .addQueryParameter("api_token", token)
            .apply {
                if (!gatewayInput.isNullOrEmpty()) {
                    addQueryParameter("gateway_input", gatewayInput)
                }
            }
            .build()

        val requestBody =  encodeJson(paramsBuilder).toRequestBody()
        val request = Request.Builder()
            .url(url)
            .apply {
                if (method != "user.getArl") {
                    post(requestBody)
                } else {
                    get()
                }
                headers(getHeaders(method))
            }
            .build()

        val clientB = if (np) clientNP else client

        clientB.newCall(request).await().use { response ->
            // Status gate BEFORE decode: an HTTP error with an empty/garbage body now surfaces as a
            // clean status error instead of a JsonDecodingException. The CSRF/errorObj/ARL-refresh
            // path below is unchanged - those errors are HTTP 200 with an error object, so they pass
            // this gate exactly as before.
            if (!response.isSuccessful) throw Exception("API call failed with status ${response.code}")
            val result = response.body.source().let {
                decodeJsonStream(it, response.code)
            }

            if (method == "deezer.getUserData") {
                response.headers.forEach {
                    if (it.second.startsWith("sid=")) {
                        session.updateCredentials(sid = it.second.substringAfter("sid=").substringBefore(";"))
                    }
                }
            }

            // ⚠️ THIS BRANCH IS A CSRF HANDLER, NOT A GENERAL ERROR HANDLER — AND THAT IS DELIBERATE.
            // It was written for VALID_TOKEN_REQUIRED specifically; the @Suppress("KotlinConstantConditions")
            // it once carried was added because `result["error"] is JsonObject` looked constant to the
            // compiler and was recorded as "genuinely reachable — Deezer returns JsonObject for CSRF
            // VALID_TOKEN_REQUIRED errors". It does its job correctly. The problem is that a SINGLE-PURPOSE
            // handler sits at the position where a GENERAL one is also needed, so the chokepoint READS as
            // guarded: every other gateway rejection falls past it and `result` is returned as if it were
            // data. Do not "finish" this branch — it is not unfinished. The general check goes BELOW it, and
            // below is load-bearing: the CSRF arm re-logins and re-enters callApi, and a general check
            // placed first would pre-empt that retry.
            val errorObj = result["error"] as? JsonObject
            if (errorObj != null) {
                if (errorObj["VALID_TOKEN_REQUIRED"]?.jsonPrimitive?.content?.contains("Invalid CSRF token") == true) {
                    if (email.isEmpty() && pass.isEmpty()) {
                        session.isArlExpired(true)
                        throw Exception("Please re-login (Best use User + Pass method)")
                    } else {
                        session.isArlExpired(false)
                        // ⚠⚠ THE ENCLOSING CSRF BRANCH IS PROTECTED BY TWO PRIOR SESSIONS - DO
                        // NOT DELETE IT ON THE COMPILER'S ADVICE. K2 flags
                        // `result["error"] is JsonObject` as always-false; it was SUPPRESSED rather
                        // than removed, because Deezer genuinely returns a JsonObject for CSRF
                        // VALID_TOKEN_REQUIRED errors and "removing it would silently break session
                        // re-auth". The conversion below now DEPENDS on this branch executing, so a
                        // deletion would take the login prompt with it - and the symptom would be the
                        // old one returning: an unexplained playback error instead of a sign-in.
                        //
                        // ⚠⚠ THIS IS WHERE A SILENT RE-LOGIN ACTUALLY HAPPENS, AND THEREFORE
                        // WHERE A REFUSED ONE MUST BE TRANSLATED. Traced from a real report rather
                        // than assumed: build 1106, cold start, 80-track queue restoring, surfacing as
                        //     IOException: Login failed: authenticate user failed error in Deezer
                        //       at StreamableMediaSource.maybeThrowSourceInfoRefreshError
                        //     Caused by: ... at DeezerApi.getArlByEmail
                        // getArlByEmail's ONLY non-recursive caller is onLogin, and onLogin is reached
                        // from exactly two places: the login screen, and THIS line. So a playback-time
                        // refusal can only have come through here - loadTrack -> callApi -> stale CSRF
                        // -> silent re-login -> refused.
                        // ⚠️ DO NOT PUT THIS GUARD ON handleArlExpiration's makeUser() BRANCH
                        // INSTEAD - IT WOULD BE INERT THERE. That branch calls makeUser(), which never
                        // calls getArlByEmail; it reaches a re-login only by coming back through THIS
                        // line, where the conversion has already happened. A guard there would look
                        // correct, compile, and never fire.
                        // WHAT THE USER GETS: ClientException.LoginRequired is wrapped by the host into
                        // AppException.LoginRequired, which ExceptionUtils.getTitle renders as a
                        // LOCALIZED message naming the extension, with a SIGN IN action attached by
                        // getMessage. A raw ClientException has no branch there and would render as
                        // "Error: LoginRequired" with only a View button - so throwing the wrapped form
                        // is what produces the prompt, not a hope about how it renders.
                        // ⚠️ AND IT NEEDS NO CHANGE TO LoginRequired's CONTRACT. The recorded gap
                        // - that LoginRequired carries nothing, so it cannot separate "user must sign
                        // in" from "internal token went stale, no user action possible" - is about
                        // handleArlExpiration's `else if (isArlExpired)` branch, where credentials are
                        // ABSENT. THIS IS A DIFFERENT BRANCH: reaching here proves email and pass were
                        // present, were used, and were refused. That is unambiguously "sign in again",
                        // which is the one case where carrying nothing is fine - there is nothing left
                        // to disambiguate. So the ABI problem that blocked the host-layer fix (a new
                        // LoginRequired subtype is additive and binary-compatible, but an optional
                        // field with a default is binary-INCOMPATIBLE because Kotlin compiles the
                        // zero-arg <init>()V away - and adoption is a behavioural change per
                        // extension, not a recompile) does not arise: this throws the EXISTING type.
                        // The September conclusion was right about the HOST layer and wrong to read as
                        // closing the question - the extension could already express it.
                        val userList = try {
                            DeezerExtension().onLogin(
                                "userPass", mapOf(Pair("email", email), Pair("pass", pass))
                            )
                        } catch (_: DeezerAuthRejectedException) {
                            session.isArlExpired(true)
                            // Latch the refusal so the NEXT attempt costs nothing. This block re-runs
                            // on every Injectable.value() while it keeps throwing, and AA calls that
                            // per extension per browse-root build - see DeezerSession
                            // .credentialsRejected for why a cheap repeat matters more than it looks.
                            session.setCredentialsRejected(true)
                            throw ClientException.LoginRequired()
                        }
                        DeezerExtension().setLoginUser(userList.first())
                        return@withContext callApi(method, paramsBuilder, gatewayInput)
                    }
                }
            }

            // ⚠️ LOG-ONLY DETECTION, 2026-09-07. DOES NOT THROW YET, BY DESIGN. This measures how often a
            // gateway REJECTION reaches a caller disguised as data, before anything changes behaviour on a
            // chokepoint that all 44 callApi sites pass through.
            //
            // WHY THIS EXISTS: a rejected request is currently indistinguishable from an empty one. Measured
            // on 2026-09-07 — page.get for PAGE="smarttracklist/<id>" returns
            //   rootKeys=[error, results, payload]
            //   error={"REQUEST_ERROR":"Page type smarttracklist does not exist"}
            //   results={}
            // and DeezerPlaylistClient.smartTracklistTracks read that as "sections=0", i.e. an empty mix. Two
            // investigation rounds went into guessing a response shape while the server's refusal sat unread
            // in a sibling key.
            //
            // THE STRONGEST ARGUMENT FOR FIXING IT HERE RATHER THAN AT THE NEXT CALLER WHO NOTICES: SOMEONE
            // ALREADY FIXED IT LOCALLY. DeezerPlaylistClient.loadTracks (the playlistSongs branch) carries a
            // hand-rolled version with a comment naming the defect verbatim — "callApi returns non-CSRF
            // errors un-thrown" — extracting Deezer's message and throwing. One caller solved it; the other
            // 43 did not. AND ITS RULE WOULD HAVE MISSED THIS CASE: it triggers on `results` ABSENT, while the
            // capture above has `results` PRESENT AND EMPTY. A local fix written against the one shape its
            // author happened to see is exactly why a chokepoint fix is worth the higher bar.
            //
            // ⚠️ AND THIS IS A DELIBERATE DEPARTURE FROM A HOUSE POLICY, NOT A CORRECTION OF SLOPPINESS.
            // This extension deliberately makes every JsonObject/JsonArray cast safe so that unexpected field
            // shapes are silently skipped — that policy is right for PARSING, where a field we do not
            // recognise should not take down a page. It is wrong ONE LAYER UP: A REJECTION IS NOT AN
            // UNEXPECTED SHAPE, IT IS THE SERVER SAYING NO. Do not "restore consistency" here by
            // re-swallowing it; the inconsistency is the point, and it is scoped to this one check.
            //
            // WHAT THE DATA DECIDES — TWO CANDIDATE RULES, and `resultsUsable` below is the field that
            // separates them:
            //   RULE A — fire when `results` is ABSENT. The existing PlaylistClient precedent. Near-zero
            //            risk, and would NOT have caught the smarttracklist case.
            //   RULE B — fire when `error` is a JsonObject with >= 1 entry (what is logged here). Catches it.
            //            Deezer's success responses conventionally carry `"error": []`, and the `as?
            //            JsonObject` cast above already yields null for an array, so the common benign shape
            //            is excluded by construction. What is NOT known from our tree is whether any SUCCESS
            //            response carries a non-empty error OBJECT. That is the entire risk, and it is
            //            measurable rather than arguable — hence this build.
            //
            // PREDICTIONS, STATED BEFORE THE RUN so the log cannot come back "unclear":
            //   Every hit has resultsUsable=false and names a real refusal (REQUEST_ERROR, quota, geo)
            //     -> Rule B is safe. Next build turns this into a throw carrying `errorText`.
            //   ANY hit has resultsUsable=true on content that actually rendered — a playable album, a
            //     populated playlist — -> RULE B IS WRONG. Narrow to Rule A plus an explicit REQUEST_ERROR
            //     case, and record which method produced the benign hit.
            //   No hits at all across normal use -> the rejection path is rarer than the smarttracklist case
            //     suggested; re-run while deliberately touching a region-locked or pulled item before
            //     concluding anything.
            //
            // WHEN THIS BECOMES A THROW, THE MESSAGE IS A CONSTRAINT, NOT A NICETY. It must CARRY Deezer's
            // own text, attached rather than substituted: a generic user-facing string for the UI, with
            // `errorText` on the exception for the log and Crashlytics. The precedent is ClientException
            // .LoginRequired, which "carries nothing — no message, no fields" and so cannot distinguish "user
            // must sign in" from "internal token went stale, no user action possible". A typed failure that
            // drops the detail would have left this investigation exactly where it started: the entire value
            // of the capture above was the sentence "Page type smarttracklist does not exist".
            if (errorObj != null && errorObj.isNotEmpty()) {
                val errorText = runCatching {
                    errorObj.entries.joinToString { (k, v) ->
                        "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v.toString()}"
                    }
                }.getOrNull() ?: "<unreadable>"
                val resultsElement = result["results"]
                val resultsUsable = when (resultsElement) {
                    null, is JsonNull -> false
                    is JsonObject -> resultsElement.isNotEmpty()
                    is JsonArray -> resultsElement.isNotEmpty()
                    else -> true
                }
                // ⚠⚠ PERMANENT (2026-09-12). It began as the Rule-A/Rule-B probe and was kept
                // when the throw shipped, for the reason below rather than by omission.
                // ⚠⚠ THE LOG LINE STAYS ALONGSIDE THE THROW, AND THAT IS NOT BELT-AND-BRACES.
                // The most frequent real trigger lands on a path that SWALLOWS the throw - see below - so
                // without this line those refusals become invisible again, which is the exact defect this
                // whole probe exists to remove. The throw serves the surfacing paths; the log serves the
                // swallowing ones. Two audiences, two mechanisms.
                println(
                    "GladixDeezer GATEWAY-ERROR method=$method resultsUsable=$resultsUsable " +
                        "error=${errorText.take(300)}"
                )
                // ⚠⚠ [FLIPPED 2026-09-12] RULE B CONFIRMED BY MEASUREMENT, NOT BY ARGUMENT.
                // Thirteen samples across two sessions, zero counter-examples:
                //   1x  page.get          REQUEST_ERROR=Page type smarttracklist does not exist
                //   12x deezer.pageTrack  REQUEST_ERROR=Wrong parameters   (one playlist and the album
                //                         behind it, containing tracks that will not play. WHY they will
                //                         not play is NOT KNOWN and is deliberately not asserted here -
                //                         what matters for Rule B is that the gateway refused and the
                //                         results were unusable every time.)
                // Every hit resultsUsable=false. NO PARTIAL-FAILURE SHAPE WAS EVER OBSERVED - no response
                // carried a non-empty `error` object alongside usable `results`, which was the entire risk.
                // ⚠️ THE SAMPLE IS LARGER THAN THIRTEEN, AND THAT IS THE ARGUMENT THAT ACTUALLY
                // CARRIED IT. This is a NEGATIVE test: it fires only when `error` is a non-empty object, so
                // every gateway call that did NOT log proved its own response carried the benign shape.
                // A full browsing session - Home, albums, artists, search, playback - is hundreds of calls
                // through this one chokepoint, every one an implicit negative that passed. Counting only
                // the positives understates the evidence by orders of magnitude.
                //
                // ⚠️ MESSAGE SPLIT AS THE NOTE ABOVE REQUIRES: a generic user-facing string, with
                // Deezer's own sentence ATTACHED rather than substituted. The LoginRequired precedent is
                // what this is avoiding - a typed failure that carries nothing cannot tell "signed out"
                // from "token went stale", and the whole value of the two captures above was the literal
                // text "Page type smarttracklist does not exist".
                throw DeezerGatewayException(method, errorText)
            }
            result
        }
    }

    suspend fun getRestApi(url: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        clientNP.newCall(request).await().use { response ->
            response.body.source().let { decodeJsonStream(it, response.code) }
        }
    }

    suspend fun callAppApi(
        method: String,
        paramsBuilder: JsonObjectBuilder.() -> Unit = {}
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https").host("api.deezer.com")
            .addPathSegments("1.0/gateway.php")
            .addQueryParameter("api_key", APP_API_KEY)
            .addQueryParameter("sid", sid)
            .addQueryParameter("method", method)
            .addQueryParameter("output", "3")
            .addQueryParameter("input", "3")
            .build()

        val requestBody =  encodeJson(paramsBuilder).toRequestBody()
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .headers(staticAppHeaders)
            .build()

        clientNP.newCall(request).await().use { response ->
            response.body.source().let {
                decodeJsonStream(it, response.code)
            }
        }
    }

    //<============= Login =============>

    suspend fun makeUser(email: String? = null, pass: String? = null): List<User> {
        val userEmail = email ?: this.email
        val userPass = pass ?: this.pass
        val userList = mutableListOf<User>()
        val jObject = callApi("deezer.getUserData")
        val userResults = jObject["results"]
            ?: throw Exception("getUserData failed: no results in response")
        val userResultsObj = userResults as? JsonObject
            ?: throw Exception("getUserData failed: results is not an object — session may be invalid")
        val userObject = userResultsObj["USER"]
            ?: throw Exception("getUserData failed: no USER object — session may be expired")
        val token = (userResultsObj["checkForm"] as? JsonPrimitive)?.contentOrNull
            ?: throw Exception("getUserData failed: no checkForm token")
        val userId = (userObject.jsonObject["USER_ID"] as? JsonPrimitive)?.contentOrNull
            ?: throw Exception("getUserData failed: no USER_ID — guest or expired session")
        val licenseToken = (userObject.jsonObject["OPTIONS"] as? JsonObject)
            ?.get("license_token")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
        val name = (userObject.jsonObject["BLOG_NAME"] as? JsonPrimitive)?.contentOrNull ?: ""
        val cover = (userObject.jsonObject["USER_PICTURE"] as? JsonPrimitive)?.contentOrNull ?: ""
        val user = User(
            id = userId,
            name = name,
            cover = "https://cdn-images.dzcdn.net/images/user/$cover/100x100-000000-80-0-0.jpg".toImageHolder(),
            extras = mapOf(
                "arl" to arl,
                "user_id" to userId,
                "sid" to sid,
                "token" to token,
                "license_token" to licenseToken,
                "email" to userEmail,
                "pass" to userPass
            )
        )
        userList.add(user)
        return userList
    }

    suspend fun getArlByEmail(mail: String, password: String, remainingAttempts: Int = 3) {
        try {
            // Get SID
            getSid()

            val md5Password = md5(password)

            val params = mapOf(
                "app_id" to CLIENT_ID,
                "login" to mail,
                "password" to md5Password,
                "hash" to md5(CLIENT_ID + mail + md5Password + CLIENT_SECRET)
            )

            // Get access token
            val responseJson = getToken(params, sid)
            val apiResponse = decodeJson(responseJson)
            val accessToken = (apiResponse["access_token"] as? JsonPrimitive)?.content
                ?: run {
                    val errMsg = (apiResponse["error"] as? JsonPrimitive)?.contentOrNull
                        ?: (apiResponse["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    // An explicit error = a REFUSAL (typed, non-retryable). No error at all = a shape
                    // surprise, which stays a plain Exception and stays retryable.
                    if (errMsg != null) throw DeezerAuthRejectedException(errMsg)
                    throw Exception("Login failed: no access_token in response")
                }
            session.updateCredentials(token = accessToken)

            // Get ARL
            val arlObject = callApi("user.getArl")
            val arl = (arlObject["results"] as? JsonPrimitive)?.contentOrNull
                ?: run {
                    val errMsg = (arlObject["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    if (errMsg != null) throw DeezerAuthRejectedException(errMsg)
                    throw Exception("Login failed: no ARL in response")
                }
            session.updateCredentials(arl = arl)
        } catch (e: Exception) {
            // ⚠⚠ A REFUSAL IS DETERMINISTIC - DO NOT RETRY IT. This is independent of the
            // message work above and would be worth doing on its own. The catch used to take every
            // Exception, so a rejected password was RE-SENT THREE TIMES to a shared-account endpoint.
            // The June 2026 backoff (1.5s/3s, added because all three attempts fired within
            // milliseconds and risked Deezer rate-limiting the account) SLOWS that and does not stop
            // it - and a rate limit is exactly the outcome the backoff exists to avoid, so retrying a
            // known-bad credential makes the very risk it was added for WORSE. Two extra attempts buy
            // nothing: Deezer has already answered, and the answer will not change.
            // Transient failures (network, non-2xx, shape surprises) still retry, unchanged.
            if (e is DeezerAuthRejectedException) throw e
            if (remainingAttempts > 1) {
                delay(1500L * (4 - remainingAttempts))
                getArlByEmail(mail, password, remainingAttempts - 1)
            } else {
                throw e
            }
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    private suspend fun getToken(params: Map<String, String>, sid: String): String {
        val url = "https://connect.deezer.com/oauth/user_auth.php"
        val httpUrl = url.toHttpUrlOrNull()!!.newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .headers(
                Headers.headersOf(
                    "Cookie", "sid=$sid",
                    "User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36"
                )
            )
            .build()

        clientLog.newCall(request).await().use { response ->
            if (response.code == 403) throw ClientException.LoginRequired()
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            return response.body.string()
        }
    }

    suspend fun getSid() {
        val url = "https://www.deezer.com/ajax/gw-light.php?method=user.getArl&input=3&api_version=1.0&api_token=null"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = clientLog.newCall(request).await()
        response.headers.forEach {
            if (it.second.startsWith("sid=")) {
                session.updateCredentials(sid = it.second.substringAfter("sid=").substringBefore(";"))
            }
        }
    }

    //<============= Media =============>

    private val deezerMedia by lazy { DeezerMedia(this, clientNP) }

    suspend fun getMP3MediaUrl(track: Track, is128: Boolean): JsonObject = deezerMedia.getMP3MediaUrl(track, arl, sid, licenseToken, is128)

    suspend fun getMediaUrl(track: Track, quality: String): JsonObject = deezerMedia.getMediaUrl(track, quality)

    //<============= Search =============>

    private val deezerSearch by lazy { DeezerSearch(this) }

    suspend fun search(query: String): JsonObject = deezerSearch.search(query)

    suspend fun searchSuggestions(query: String): JsonObject = deezerSearch.searchSuggestions(query)

    suspend fun setSearchHistory(query: String) = deezerSearch.setSearchHistory(query)

    suspend fun getSearchHistory(): JsonObject = deezerSearch.getSearchHistory()

    suspend fun deleteSearchHistory() = deezerSearch.deleteSearchHistory(userId)

    //<============= Tracks =============>

    private val deezerTrack by lazy { DeezerTrack(this) }

    suspend fun track(id: String): JsonObject = deezerTrack.track(id)

    suspend fun getListData(ids: List<String>): List<JsonObject> = deezerTrack.getListData(ids)

    suspend fun getTracks(): JsonObject = deezerTrack.getTracks(userId)

    suspend fun addFavoriteTrack(id: String) = deezerTrack.addFavoriteTrack(id)

    suspend fun removeFavoriteTrack(id: String) = deezerTrack.removeFavoriteTrack(id)

    //<============= Artists =============>

    private val deezerArtist by lazy { DeezerArtist(this) }

    suspend fun artist(id: String): JsonObject = deezerArtist.artist(id)

    suspend fun getArtists(): JsonObject = deezerArtist.getArtists(userId)

    suspend fun followArtist(id: String) = deezerArtist.followArtist(id)

    suspend fun unfollowArtist(id: String) = deezerArtist.unfollowArtist(id)

    suspend fun artistAlbums(id: String, index: Int): JsonObject = deezerArtist.artistAlbums(id, index)

    suspend fun artistRelated(id: String, index: Int): JsonObject = deezerArtist.artistRelated(id, index)

    //<============= Albums =============>

    private val deezerAlbum by lazy { DeezerAlbum(this) }

    suspend fun album(album: Album): JsonObject = deezerAlbum.album(album)

    suspend fun getAlbums(): JsonObject = deezerAlbum.getAlbums(userId)

    suspend fun addFavoriteAlbum(id: String) = deezerAlbum.addFavoriteAlbum(id)

    suspend fun removeFavoriteAlbum(id: String) = deezerAlbum.removeFavoriteAlbum(id)

    //<============= Shows =============>

    private val deezerShow by lazy { DeezerShow(this) }

    suspend fun show(album: Album): JsonObject = deezerShow.show(album, language, userId)

    suspend fun getShows(): JsonObject = deezerShow.getShows(userId)

    suspend fun addFavoriteShow(id: String) = deezerShow.addFavoriteShow(id)

    suspend fun removeFavoriteShow(id: String) = deezerShow.removeFavoriteShow(id)

    suspend fun getBookmarkedEpisodes() = deezerShow.getBookmarkedEpisodes(userId)

    suspend fun bookmarkEpisode(id: String, offset: Long, duration: Double) = deezerShow.bookmarkEpisode(id, offset, duration)

    //<============= Playlists =============>

    private val deezerPlaylist by lazy { DeezerPlaylist(this) }

    suspend fun playlist(playlist: Playlist): JsonObject = deezerPlaylist.playlist(playlist)

    suspend fun playlistSongs(playlist: Playlist): JsonObject = deezerPlaylist.getSongs(playlist)

    suspend fun getPlaylists(): JsonObject = deezerPlaylist.getPlaylists(userId)

    suspend fun addFavoritePlaylist(id: String) = deezerPlaylist.addFavoritePlaylist(id)

    suspend fun removeFavoritePlaylist(id: String) = deezerPlaylist.removeFavoritePlaylist(id)

    suspend fun addToPlaylist(playlist: Playlist, tracks: List<Track>) = deezerPlaylist.addToPlaylist(playlist, tracks)

    suspend fun removeFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) = deezerPlaylist.removeFromPlaylist(playlist, tracks, indexes)

    suspend fun createPlaylist(title: String, description: String? = ""): JsonObject = deezerPlaylist.createPlaylist(title,description)

    suspend fun deletePlaylist(id: String) = deezerPlaylist.deletePlaylist(id)

    suspend fun updatePlaylist(id: String, title: String, description: String? = "") = deezerPlaylist.updatePlaylist(id, title, description)

    suspend fun updatePlaylistOrder(playlistId: String, ids: MutableList<String>) = deezerPlaylist.updatePlaylistOrder(playlistId, ids)

    //<============= Radios =============>

    private val deezerRadio by lazy { DeezerRadio(this) }

    suspend fun mix(id: String): JsonObject = deezerRadio.mix(id)

    suspend fun mixArtist(id: String): JsonObject = deezerRadio.mixArtist(id)

    suspend fun radio(trackId: String, artistId: String): JsonObject = deezerRadio.radio(trackId, artistId)

    suspend fun flow(id: String): JsonObject = deezerRadio.flow(id, userId)

    //<============= Pages =============>

    suspend fun page(page: String): JsonObject {
        return callApi(
            method = "page.get",
            gatewayInput = """
                {"PAGE":"$page","VERSION":"2.5","SUPPORT":{"ads":[],"deeplink-list":["deeplink"],"event-card":["live-event"],"grid-preview-one":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"grid-preview-two":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"horizontal-grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"horizontal-list":["track","song"],"item-highlight":["radio"],"large-card":["album","external-link","playlist","show","video-link"],"list":["episode"],"mini-banner":["external-link"],"slideshow":["album","artist","channel","external-link","flow","livestream","playlist","show","smarttracklist","user","video-link"],"small-horizontal-grid":["flow"],"long-card-horizontal-grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"filterable-grid":["flow"]},"LANG":"$langCode","OPTIONS":["deeplink_newsandentertainment","deeplink_subscribeoffer"]}
            """.trimIndent()
        )
    }

    //<============= Lyrics =============>

    /**
     * Track ids for one smarttracklist, over GRAPHQL. NOT the gateway — page.get has no smarttracklist
     * page type ("Page type smarttracklist does not exist", measured 2026-09-07), which is why this looks
     * nothing like its neighbours.
     *
     * QUERY VERIFIED FROM OPEN SOURCE, not from traffic capture: music-assistant/deezer-python-gql,
     * queries/get_smart_tracklist.graphql — variables $smartTracklistId: String!, $first: Int = 50,
     * $after: String; tracks at data.smartTracklist.tracks.edges[].node, confirmed twice (the .graphql
     * document and the generated model deezer_python_gql/generated/get_smart_tracklist.py, whose aliases
     * are "smartTracklist" and "pageInfo"). Fragments are inlined to the ONE field we consume.
     *
     * ⚠️ WE ASK FOR `node { id }` AND NOTHING ELSE, DELIBERATELY. TrackFields carries no MD5_ORIGIN, no
     * FILESIZE_* and no TRACK_TOKEN, so a Track built from it renders correctly and CANNOT PLAY. The ids
     * go to song.getListData and the existing DeezerParser.toTrack does the rest — see the block note on
     * DeezerPlaylistClient.smartTracklistTracks before changing this selection set.
     *
     * WHICH ID: the SLOT form (data.SMARTTRACKLIST_ID, e.g. "inspired-by-1"), NOT the compound instance id
     * (data.ID). Measured 2026-09-07 — `me { madeForMe }` returned
     * [6563868601, inspired-by-1 … inspired-by-5, new-releases], so the API addresses these by slot and
     * DeezerParser.toSmartTracklist already stores the right one. The leading bare-numeric id is the Flow
     * node (the user id), a different type in the same union; it is not handled here.
     *
     * JWT PER CALL, NOT CACHED. The token's TTL is ~6 minutes, so a cache needs expiry tracking AND a
     * 401-refresh path — and the 401 path has to exist either way, so the cache adds a second mechanism
     * without removing the first. Opening a mix is one user action; one extra round-trip is invisible
     * there. If a second pipe consumer appears, factor a cached holder THEN. Mirrors `lyrics` below.
     */
    suspend fun smartTracklistTrackIds(id: String, first: Int = 100): List<String> {
        // ⚠⚠ THIS IS A SECOND AUTH SURFACE, NOT THE GATEWAY'S. The pipe/GraphQL path does its
        // own exchange - ARL + sid cookie POSTed to auth.deezer.com/login/arl, which returns a JWT used as
        // `Authorization: Bearer` against pipe.deezer.com. callApi never touches that JWT; it sends the
        // ARL/sid cookie straight to gw-light.php.
        // ⚠️ SO ONE CAN REFUSE WHILE THE OTHER WORKS, AND THAT IS NOT A CONTRADICTION.
        // Measured (Crashlytics, 2026-09-12): this handshake returned the body `"Invalid Arl provided"`
        // while the gateway kept serving tracks, playlists and search on the same ARL, and the tiles
        // worked again afterwards. Transient, on this surface only. DO NOT READ A PIPE AUTH FAILURE AS
        // EVIDENCE THAT THE ARL IS BAD - check the gateway before concluding anything.
        // ⚠️ AND THE HANDSHAKE IS DUPLICATED INLINE in this function and in lyrics, so both
        // carry this exposure. If a third consumer appears, factor the exchange out THEN - see the caching
        // note above - and this comment moves with it.
        val request = Request.Builder()
            .url("https://auth.deezer.com/login/arl?jo=p&rto=c&i=c")
            .post(RequestBody.EMPTY)
            .headers(Headers.headersOf("Cookie", "arl=$arl; sid=$sid"))
            .build()
        val jwt = decodeJson(clientNP.newCall(request).await().body.string())["jwt"]
            ?.jsonPrimitive?.content
        val params = encodeJson {
            put("operationName", "GetSmartTracklist")
            put("query", $$"query GetSmartTracklist($smartTracklistId: String!, $first: Int = 50) { smartTracklist(smartTracklistId: $smartTracklistId) { id tracks(first: $first) { edges { node { id } } } } }")
            putJsonObject("variables") {
                put("smartTracklistId", id)
                put("first", first)
            }
        }
        val pipeRequest = Request.Builder()
            .url("https://pipe.deezer.com/api")
            .post(params.toRequestBody())
            .headers(Headers.headersOf("Authorization", "Bearer $jwt", "Content-Type", "application/json"))
            .build()
        val body = decodeJson(clientNP.newCall(pipeRequest).await().body.string())
        // GraphQL reports failures in a top-level `errors` array with HTTP 200 and a null data field — the
        // same shape of trap as the gateway's `error` key, so it is read rather than left to surface as an
        // empty list. Same house rule as callApi's GATEWAY-ERROR line: a refusal must not look like "none".
        body["errors"]?.let {
            println("GladixDeezer STL-GQL id=$id errors=${it.toString().take(300)}")
        }
        val edges = body["data"]?.jsonObject?.get("smartTracklist")?.jsonObject
            ?.get("tracks")?.jsonObject?.get("edges") as? JsonArray
        return edges?.filterIsInstance<JsonObject>()
            ?.mapNotNull { (it["node"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    }

    suspend fun lyrics(id: String): JsonObject {
        // ⚠️ SECOND AUTH SURFACE - the JWT exchange below is NOT the gateway's ARL/sid path, so
        // this can fail while the rest of Deezer works. Full note at smartTracklistTrackIds, which repeats
        // this same handshake inline; the observed failure there was transient and surface-specific.
        val request = Request.Builder()
            .url("https://auth.deezer.com/login/arl?jo=p&rto=c&i=c")
            .post(RequestBody.EMPTY)
            .headers(Headers.headersOf("Cookie", "arl=$arl; sid=$sid"))
            .build()
        val response = clientNP.newCall(request).await()
        val jsonObject = decodeJson(response.body.string())

        val jwt = jsonObject["jwt"]?.jsonPrimitive?.content
        val params = encodeJson {
            put("operationName", "SynchronizedTrackLyrics")
            put("query", $$"query SynchronizedTrackLyrics($trackId: String!) {\n  track(trackId: $trackId) {\n    id\n    isExplicit\n    lyrics {\n      id\n      copyright\n      text\n      writers\n      synchronizedLines {\n        lrcTimestamp\n        line\n        milliseconds\n        duration\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n}")
            putJsonObject("variables") {
                put("trackId", id)
            }
        }
        val pipeRequest = Request.Builder()
            .url("https://pipe.deezer.com/api")
            .post(params.toRequestBody())
            .headers(Headers.headersOf("Authorization", "Bearer $jwt", "Content-Type", "application/json"))
            .build()
        val pipeResponse = clientNP.newCall(pipeRequest).await()
        return decodeJson(pipeResponse.body.string())
    }

    //<============= Util =============>

    private val deezerUtil by lazy { DeezerUtil(this) }

    suspend fun updateCountry() = deezerUtil.updateCountry(country)

    suspend fun log(track: Track) = deezerUtil.log(track, userId)

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun decodeJsonStream(source: BufferedSource, code: Int) = withContext(Dispatchers.Default) {
        // Empty/truncated body (transient: dropped connection, 5xx with nobody, WAF/rate-limit) ->
        // fail with a clear retryable IOException instead of a raw JsonDecodingException
        // "Expected start of the object '{', but had 'EOF'". exhausted() is robust for chunked
        // responses where contentLength() is -1.
        if (source.exhausted()) throw IOException("Empty response body from Deezer (HTTP $code)")
        // Same guard as decodeJson below, on the streaming path. Peek rather than read: peek() gives a
        // read-ahead view that leaves `source` untouched for the real decode.
        source.peek().let { peek ->
            val buf = ByteArray(NON_OBJECT_PEEK_BYTES)
            val n = peek.read(buf)
            if (n > 0) requireJsonObject(String(buf, 0, n), "HTTP $code")
        }
        json.decodeFromStream<JsonObject>(source.inputStream())
    }

    suspend fun decodeJson(raw: String): JsonObject = withContext(Dispatchers.IO) {
        if (raw.isBlank()) throw IOException("Empty response body from Deezer")
        requireJsonObject(raw, null)
        json.decodeFromString<JsonObject>(raw)
    }

    private val NON_OBJECT_PEEK_BYTES = 200

    // ⚠⚠ A REFUSAL MUST NOT SURFACE AS A SYNTAX ERROR - SAME HOUSE RULE AS callApi's
    // GATEWAY-ERROR AND decodeJson's empty-body check, extended to the case that actually bit.
    // MEASURED (Crashlytics, 2026-09-12, tapping a "Made for You" tile): auth.deezer.com/login/arl
    // returned the body `"Invalid Arl provided"` and we reported
    // `JsonDecodingException: Expected start of the object '{', but had '"' instead at path: $`.
    // Deezer told us exactly what was wrong and the decoder threw that away.
    // ⚠️ THE BODY IS VALID JSON - A STRING PRIMITIVE - WHICH IS WHY A "NOT JSON" SNIFF WOULD
    // MISS IT. AddViewModel.getExtensionList guards `body.trimStart().startsWith("<")`, i.e. HTML ONLY; a
    // bare quoted string starts with `"` and sails straight through that shape of check. Testing for the
    // OBJECT we actually require, rather than for particular kinds of non-object, covers HTML, bare
    // strings, arrays and plain text in one condition and cannot be out-guessed by a new body shape.
    // ⚠️ IOException, MATCHING THE EMPTY-BODY PRECEDENT ABOVE, AND THAT IS A JUDGEMENT CALL
    // WORTH KNOWING ABOUT: from inside a decoder we cannot tell an auth refusal from a WAF page or a
    // truncated response, and the existing convention treats "the body is not what we asked for" as
    // transport-shaped and retryable. The observed case supports that - the ARL was fine before and after,
    // and the gateway kept working throughout. If a NON-transient refusal ever needs to surface as an auth
    // error instead, the discriminator is the body text, which this message now preserves.
    //
    // ⚠⚠ AND THIS IS NOW THE THIRD MEMBER OF A FAMILY THAT ALREADY HAS A RECORDED
    // CLASSIFICATION PROBLEM. NAMED TOGETHER HERE SO THE NEXT PERSON FINDS ALL THREE AT ONCE RATHER THAN
    // DISCOVERING THIS ONE BY ACCIDENT:
    //     1. "Empty response body from Deezer (HTTP N)"   decodeJsonStream, exhausted() guard
    //     2. "API call failed with status ..."            callApi
    //     3. "Deezer returned a non-object body: ..."     this
    // THE RECORDED PROBLEM: all three are GENUINELY TRANSIENT, NONE CARRIES A JDK NETWORK TYPE, and
    // IOException CANNOT BE EXCLUDED WHOLESALE by a classifier because it is the SUPERTYPE of
    // InvalidResponseCodeException. So "treat IOException as transient" over-matches and "match on JDK
    // network types" under-matches; the existing entry records this as having NO MITIGATION in the full
    // version. A third throw does not make that worse - it was already unresolved - but a future
    // classifier now has three strings to account for, not two.
    // ⚠️ IF ONE IS EVER FIXED, FIX THEM AS A SET. They share a chokepoint, a shape and a
    // failure mode; splitting them leaves the classifier half-right, which is how this got recorded as
    // unmitigated in the first place.
    private fun requireJsonObject(head: String, ctx: String?) {
        val first = head.trimStart().firstOrNull() ?: return
        if (first == '{') return
        val where = ctx?.let { " ($it)" } ?: ""
        throw IOException(
            "Deezer returned a non-object body$where: ${head.trim().take(NON_OBJECT_PEEK_BYTES)}"
        )
    }

    suspend fun encodeJson(raw: JsonObjectBuilder.() -> Unit = {}): String = withContext(Dispatchers.IO) {
        json.encodeToString(buildJsonObject(raw))
    }
}