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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
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
import okio.ByteString.Companion.decodeBase64
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
    // ⚠⚠ THE LANG THIS REQUEST CARRIED, ADDED 2026-10-03 FOR App.kt's deezer_gateway KEY.
    // The 1113 reports of this exception (12 events / 3 users, example at a startup feed load) could not
    // be triaged because the MESSAGE is a fixed generic string by design - see the two-strings note
    // above - and no Crashlytics key carried method or errorText, so twelve events arrived with zero
    // recoverable cause. The leading hypothesis was an unsupported gateway LANG, which nothing in a
    // report could confirm or refute. This field is the half of that question the exception can answer
    // about itself; see DeezerCountries.resolveApiLanguageTag for the fix shipped alongside it.
    // ⚠️ IT IS MEANINGFUL FOR EVERY METHOD, not only page.get. page.get carries LANG inside
    // gateway_input and deezer.pageAlbum/pageArtist/pagePlaylist/pageShow carry a `lang` param, but
    // getHeaders puts Accept-Language and Content-Language on EVERY call, so there is no gateway
    // request this does not describe.
    val lang: String,
) : Exception("Deezer refused this request.") {
    // ⚠️ FORMAT CHANGED 2026-10-03 (lang inserted). It is forwarded WHOLE by App.kt's fallback
    // arm and never parsed, so nothing reads the field order - but do not assume the old two-field
    // shape when reading an older stack trace.
    override fun toString() =
        "DeezerGatewayException(method=$method, lang=$lang, error=$errorText)"

    // ⚠⚠ CLASSIFIED HERE, BESIDE THE PARSE, NOT AT THE CALL SITES. callApi is what turns the
    // gateway's `error` object into [errorText], so this is the only place that knows the shape without
    // re-parsing it - and the house rule is to classify on an error code WE parse, never on a
    // third-party exception type. Two call sites already need the same test (artist and track radio), so
    // a string comparison in a client would have been copied before it was correct twice.
    //
    // ⚠️ "empty tracklist" IS NOT A REFUSAL, AND TREATING IT AS ONE COST 48 EVENTS / 8 USERS ON
    // 1116. `smart.getSmartRadio` answers DATA_ERROR "smart::getSmartRadio : empty tracklist for artist
    // <id>" when Deezer simply has no radio for that artist. That is the SAME ANSWER as "no tracks",
    // reported through the error channel - so it threw, surfaced the fixed "Deezer refused this request."
    // string, and filed a report, for a condition the app already knows how to handle.
    // ⚠️ AND IT LOOPED, WHICH IS WHY THE COUNT IS SO HIGH. A thrown page load sets
    // PlayerRadio.PlayResult.failed, whose doc requires a failure to be treated as TRANSIENT - play()
    // restores the prior Loaded state so the next transition retries. Permanent condition, retry
    // semantics: one artist (114420502) was re-requested on every track transition, which is also why
    // these reports correlate with aa_connected=true.
    // ⚠️ THE MATCH IS DELIBERATELY NARROW - code AND text. DATA_ERROR alone is far too broad
    // (it is the gateway's general data-layer code), and the text alone would match a future method that
    // means something else by it. Widen only against an observed error string, never pre-emptively.
    val isEmptyTracklist: Boolean
        get() = errorText.contains("DATA_ERROR", ignoreCase = true) &&
            errorText.contains("empty tracklist", ignoreCase = true)

    // ⚠⚠ ADDING THIS TRACK AGAIN IS NOT A FAILURE - THE USER'S INTENT IS ALREADY SATISFIED.
    // `playlist.addSongs` answers ERROR_DATA_EXISTS "This song already exists in this playlist", which
    // describes a playlist that is already in the state the caller asked for. The operation is
    // idempotent, so the honest result is success.
    // ⚠️ THE MULTI-PLAYLIST SAVE IS WHY SILENCE BEATS A MESSAGE. SaveToPlaylistViewModel loops
    // per playlist and emits each failure to throwFlow, so one duplicate track saved into N playlists
    // produced N "Deezer refused this request." snackbars AND N reports, while the outcome counted as
    // neither saved nor skipped. An "already in this playlist" toast per playlist would be no better in
    // a five-playlist save. If it is ever wanted, it belongs in the HOST as a `skipped` reason.
    val isAlreadyInPlaylist: Boolean
        get() = errorText.contains("ERROR_DATA_EXISTS", ignoreCase = true)

    // ⚠⚠ THE STORED SESSION IS DEAD - THIS IS A SIGN-IN, NOT A REFUSAL. The gateway answers
    // NEED_USER_AUTH_REQUIRED "Require user auth" when the credentials we sent are no longer
    // accepted by a method that needs a user. Observed on 1118 (Crashlytics 8eb50552,
    // gw=deezer.userMenu lang=ru, at app open) where it reached the user as the fixed
    // "Deezer refused this request." string - a generic error for the one condition that has an
    // obvious remedy.
    // ⚠️ MEANING, FROM A COMMUNITY SOURCE RATHER THAN DEEZER DOCS, AND LABELLED AS SUCH:
    // d-fi/releases issue #50 (github.com/d-fi/releases/issues/50) is titled
    // "ERROR - NEED_USER_AUTH_REQUIRED,Require user auth logout" and resolves it by supplying a
    // FRESH ARL, with a follow-up noting ARLs need renewing every 4-6 months. So the code means an
    // expired/invalid ARL - the stored session, not the request. That is an ISSUE THREAD, not a
    // source line and not documentation: it corroborates the reading below, it does not establish
    // the exhaustive account-side meaning. No open-source client was found that branches on the
    // code in code (searched 2026-10-10).
    // ⚠️ WHY LoginRequired IS UNAMBIGUOUS HERE, which is what makes the conversion safe: a caller
    // reaching a gateway method either pre-checks DeezerExtension.handleArlExpiration - which throws
    // LoginRequired while arl/sid/token are empty - or holds no credentials at all. So both reachable
    // states want the same affordance: credentials we had stopped working, or there were none.
    // Neither is recoverable without the user, which is the test the LoginRequired table at
    // DeezerExtension.handleArlExpiration requires of a new throw site.
    // ⚠️ MATCHED ON THE CODE ALONE, AND THE CONTRAST WITH isEmptyTracklist ABOVE IS DELIBERATE
    // rather than inconsistent. That one needs code AND text because DATA_ERROR is the gateway's
    // general data-layer code; this key names one condition and nothing else. Requiring the sentence
    // too would let a Deezer rewording silently stop routing to the login prompt - the same argument
    // DeezerAuthRejectedException's note makes for testing a type rather than a sentence.
    val isUserAuthRequired: Boolean
        get() = errorText.contains("NEED_USER_AUTH_REQUIRED", ignoreCase = true)
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
        // ⚠️ BOTH DERIVED FROM ONE MEASURED FACT - the pipe JWT's TTL is ~6 minutes (recorded at
        // smartTracklistTrackIds before the holder existed). 30s of margin is a twentieth of that; 60s
        // would be a sixth. The FALLBACK is used only when `exp` will not parse, and is deliberately far
        // below 6 minutes: an unparseable token is a reason for less confidence, not more, and caching
        // one past its life is worse than not caching at all. If the TTL is ever re-measured, these two
        // move together - do not raise either without a new measurement to point at.
        private const val PIPE_JWT_MARGIN_MS = 30_000L
        private const val PIPE_JWT_FALLBACK_TTL_MS = 90_000L

        // ⚠️ 30, REPLACING THE GATEWAY'S nb=128 FOR THIS SECTION ONLY, AND THE CEILING IS NOT
        // ARBITRARY: AndroidAutoCallback.performSearch does take(25), and CombineExtension stops at its
        // first match, so 25 is the largest number any programmatic consumer reads. 30 leaves headroom.
        // ⚠️ STATED COST: the phone Tracks tab goes from up to 128 scrollable rows to 30. Pipe
        // exposes pageInfo.endCursor for paging, but our per-tab lambda does a single loadPage(null), so
        // nothing consumes a cursor today - restoring depth means teaching that lambda to page.
        private const val PIPE_SEARCH_TRACKS = 30

        // Deezer picture hashes are 32 lowercase hex. See the ALB_PICTURE note at searchTracksPipe for
        // why a non-matching id yields no cover instead of a broken one.
        private val COVER_MD5 = Regex("^[0-9a-f]{32}$")

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
        // ⚠⚠ GOES THROUGH THE SUPPORTED LIST - DO NOT COLLAPSE BACK TO `?:`. This read was
        // `settings.getString("lang") ?: Locale.getDefault().toLanguageTag()`, which sent the raw device
        // tag as gateway LANG for every user who never picked a language, because the default-index
        // helper writes a DIFFERENT key ("languageCode"). Full argument, and what each input now
        // returns, at DeezerCountries.resolveApiLanguageTag.
        get() = DeezerCountries.resolveApiLanguageTag(
            session.settings?.getString("lang"),
            Locale.getDefault().toLanguageTag(),
            Locale.getDefault().country
        )

    // ⚠⚠ NULLABLE, AND NULL MEANS "SEND NOTHING" - see DeezerCountries.resolveApiCountry. This
    // read was `settings.getString("country") ?: Locale.getDefault().country`, which sent an
    // unvalidated device region for every user who never picked a country, because the default-index
    // helper writes a DIFFERENT key ("countryCode"). Exactly the `lang`/`languageCode` mismatch one
    // field over.
    // ⚠️ ONE CONSUMER, VERIFIED BY GREP OVER THE MODULE: updateCountry() below. DeezerShow's
    // `country` param is NOT this - it derives a region from the language tag, which is a separate
    // defect scoped separately; see the note at DeezerShow.show.
    private val country: String?
        get() = DeezerCountries.resolveApiCountry(
            session.settings?.getString("country"),
            Locale.getDefault().country
        )

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
                // lang= mirrors the field now on the exception, so the logcat line and the
                // deezer_gateway Crashlytics key cannot disagree about the same request.
                println(
                    "GladixDeezer GATEWAY-ERROR method=$method lang=$langCode " +
                        "resultsUsable=$resultsUsable error=${errorText.take(300)}"
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
                // ⚠⚠ ONE EXIT BEFORE THE GENERIC ONE. It uses the CLASSIFIER rather than a second string
                // test, so the exception is constructed either way and the rule stays beside the parse - see
                // isUserAuthRequired for what the code means and where that reading comes from.
                // ⚠⚠ deezer.getUserData IS EXCLUDED, AND THAT EXCLUSION IS THE WHOLE REASON THIS SHIPS.
                // It is the LOGIN FLOW'S ONLY GATEWAY METHOD: makeUser calls callApi("deezer.getUserData") and
                // nothing else in that flow goes through callApi at all (getSid and getArlByEmail build their
                // own requests, which is why DeezerAuthRejectedException exists separately). Four entry points
                // reach it - DeezerExtension.onLogin on BOTH branches, webViewRequest.onStop, and
                // getCurrentUser - and converting there would hand a user who is ALREADY ON THE LOGIN SCREEN,
                // having just submitted credentials, a snackbar whose action is Sign In: LoginViewModel.afterLogin
                // emits the failure to throwFlow, and AppException.LoginRequired renders with that action. A
                // circular prompt replacing a readable error, and it would also suppress the report, because
                // App.kt skips recordException for anything isLoginRequired() matches.
                // ⚠️ IT IS NOT A LOCKOUT AND THE DISTINCTION IS WORTH KEEPING STRAIGHT: afterLogin clears
                // loading on both arms and nothing latches, so 1108 does not repeat. The exclusion is about the
                // prompt being USELESS there, not about getting trapped.
                // ⚠️ WHAT THE EXCLUSION COSTS, NAMED: a dead session surfacing through handleArlExpiration ->
                // makeUser keeps the generic string. That case is already converted when Deezer answers
                // "Invalid CSRF token" in the branch above, so the gap is narrow and known rather than silent.
                // ⚠️ TOUCHES NO SESSION STATE. Not credentialsRejected (the CSRF catch above is its only
                // writer, and its meaning is "a silent re-login was refused", which this is not), and not
                // isArlExpired (which would drive a re-login on the next call - arguably better, but a separate
                // decision on the path 1108 came from).
                // ⚠️ AND IT CANNOT STRAND EXTENSION SELECTION: onExtensionSelected is
                // `runCatching { handleArlExpiration() }` and swallows everything, so this cannot escape the
                // Injectable injection block.
                val gatewayError = DeezerGatewayException(method, errorText, langCode)
                if (gatewayError.isUserAuthRequired && method != "deezer.getUserData")
                    throw ClientException.LoginRequired()
                throw gatewayError
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
     * ⚠️ [CORRECTED 2026-10-09] THIS USED TO READ "JWT PER CALL, NOT CACHED … If a second pipe
     * consumer appears, factor a cached holder THEN. Mirrors `lyrics` below." BOTH HALVES ARE NOW DONE,
     * not wrong: pipe track search became the third consumer, so the holder exists and the handshake is
     * factored out. The exchange, the cache and the "second auth surface" exposure note all live at
     * [pipeGraphQl] now; its reasoning quotes this note's measured ~6-minute TTL, which is still the
     * number that constrains the fallback TTL. Nothing about the SELECTION SET below changed.
     */
    suspend fun smartTracklistTrackIds(id: String, first: Int = 100): List<String> {
        val params = encodeJson {
            put("operationName", "GetSmartTracklist")
            put("query", $$"query GetSmartTracklist($smartTracklistId: String!, $first: Int = 50) { smartTracklist(smartTracklistId: $smartTracklistId) { id tracks(first: $first) { edges { node { id } } } } }")
            putJsonObject("variables") {
                put("smartTracklistId", id)
                put("first", first)
            }
        }
        val body = pipeGraphQl(params)
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
        // ⚠️ SECOND AUTH SURFACE - pipe is NOT the gateway's ARL/sid path, so this can fail while
        // the rest of Deezer works. Full note at pipeGraphQl, which now owns the handshake for all three
        // consumers; the observed failure was transient and surface-specific.
        val params = encodeJson {
            put("operationName", "SynchronizedTrackLyrics")
            put("query", $$"query SynchronizedTrackLyrics($trackId: String!) {\n  track(trackId: $trackId) {\n    id\n    isExplicit\n    lyrics {\n      id\n      copyright\n      text\n      writers\n      synchronizedLines {\n        lrcTimestamp\n        line\n        milliseconds\n        duration\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n}")
            putJsonObject("variables") {
                put("trackId", id)
            }
        }
        return pipeGraphQl(params)
    }

    /**
     * Track search over pipe GraphQL, returned as GATEWAY-SHAPED song records.
     *
     * ⚠⚠ WHY THIS RETURNS SYNTHESISED GATEWAY JSON INSTEAD OF `Track`s. Its one consumer splices
     * the result into pageSearch's `results["TRACK"]["data"]` before tabs and shelves are built, which is
     * the ONLY point both the "All" shelf list and the TRACK tab read - one change, no parser edits, and
     * DeezerParser.toEchoMediaItem/toTrack keep working untouched. Returning `Track`s would mean teaching
     * two separate consumers about a second representation. Measured 2026-10-09: pipe returns the tracks
     * the gateway's search index does not - the catalogue was re-issued under new ids (the acoustic single
     * is 3913168001 on pipe, 1987857917 in the gateway's own album listing) - while gateway ALBUM, ARTIST
     * and PLAYLIST were as good or better, which is why ONLY the TRACK section is replaced.
     *
     * ⚠️ NO TRACK_TOKEN, DELIBERATELY, AND IT IS WHAT MAKES THESE PLAYABLE. Pipe carries no
     * TRACK_TOKEN / MD5_ORIGIN / FILESIZE_*, so DeezerTrackClient.loadTrack's empty-token self-heal fires,
     * re-fetches by id and merges the gateway's extras onto this track's display fields. Verified
     * 2026-10-09 that deezer.pageTrack resolves the new ids WITH a token - without that this whole path
     * would render correctly and refuse to play. Same split as smartTracklistTrackIds: pipe for identity,
     * gateway for playback. DO NOT add a token field here.
     *
     * ⚠️ NO `VERSION` KEY. Pipe's `title` already contains the version marker ("… (Acoustic)"), and
     * toTrack APPENDS VERSION to the title - setting both would double the suffix.
     * ⚠️ NO ARTIST PICTURES. parseArtists reads ART_PICTURE per artist and pipe's contributors
     * carry none, so artist thumbnails are absent on these rows. Accepted: search rows show the TRACK
     * cover, which is present.
     * ⚠️ ALB_PICTURE ONLY WHEN `cover.id` IS A 32-HEX MD5. getCover builds
     * cdn-images.dzcdn.net/images/cover/<md5>/… from a HASH, not a URL, and it is unverified whether
     * pipe's cover.id is that same md5. The guard means a non-md5 yields NO cover rather than a broken
     * image - and a Tracks tab with missing covers is then the signal that it is not an md5, which is the
     * measurement. Do not strip the check without making that measurement first.
     *
     * ⚠️ NO `media { rights … }` IN THE SELECTION, AND IT IS NOT AN OVERSIGHT. Availability was
     * selected during the 2026-10-09 measurement and is the field that produced TrackMediaNotFoundException
     * on an "Other Worlds" search. We do not need it - nothing here filters on availability, deliberately:
     * the 2026-09-12 FILESIZE investigation measured 12/12 against "unplayable" tracks and was refuted two
     * captures later, so emptiness is not evidence a track cannot play. Adding it back buys a field nobody
     * reads and a per-node failure mode.
     *
     * Returns null ONLY when no rows map, so the caller keeps the gateway's own TRACK section then.
     * A non-empty `errors[]` alongside usable rows is logged and otherwise ignored - see the body.
     */
    suspend fun searchTracksPipe(query: String, first: Int = PIPE_SEARCH_TRACKS): JsonArray? {
        val params = encodeJson {
            put("operationName", "Search")
            put("query", $$"query Search($query: String!, $tracksFirst: Int!) { search(query: $query) { results { tracks(first: $tracksFirst) { edges { node { id title duration isExplicit ISRC album { id displayTitle cover { id urls(pictureRequest: { width: 500, height: 500 }) } } contributors(first: 3, roles: [MAIN, FEATURED]) { edges { node { ... on Artist { id name } } } } } } } } } }")
            putJsonObject("variables") {
                put("query", query)
                put("tracksFirst", first)
            }
        }
        val body = pipeGraphQl(params)
        val edges = body["data"]?.jsonObject?.get("search")?.jsonObject
            ?.get("results")?.jsonObject?.get("tracks")?.jsonObject
            ?.get("edges") as? JsonArray
        // ⚠⚠ PARTIAL ERRORS ARE NORMAL HERE AND MUST NOT DISCARD THE ROWS. GraphQL answers HTTP
        // 200 with BOTH a populated `data` and a non-empty `errors[]` when one field of one node fails to
        // resolve. Measured 2026-10-09: an "Other Worlds" search returned good track edges alongside a
        // TrackMediaNotFoundException. An earlier version of this function returned null on ANY `errors`
        // entry, which would have silently dropped pipe for exactly those queries and fallen back to the
        // gateway's empty TRACK section - the defect this whole path exists to fix, re-created by its own
        // error handling. So errors are LOGGED, never fatal; the only fatal condition is "no rows mapped".
        // ⚠️ THE LOG IS NOT OPTIONAL, because silence from this function is otherwise ambiguous: a
        // TOTAL refusal (null data + errors) and an honestly empty result both leave by the same
        // `songs.isEmpty()` return below. `partial=` is what separates them - true means data survived
        // alongside an error, false means the refusal was total. Same house rule as callApi's
        // GATEWAY-ERROR line: a refusal must never read as "none".
        body["errors"]?.let {
            println(
                "GladixDeezer SEARCH-GQL partial=${edges != null} " +
                    "errors=${it.toString().take(300)}"
            )
        }
        if (edges == null) return null
        val songs = edges.filterIsInstance<JsonObject>()
            .mapNotNull { (it["node"] as? JsonObject)?.let(::pipeNodeToSong) }
        return if (songs.isEmpty()) null else buildJsonArray { songs.forEach { add(it) } }
    }

    private fun pipeNodeToSong(node: JsonObject): JsonObject? {
        fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        val id = node.s("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = node.s("title") ?: return null
        val album = node["album"] as? JsonObject
        val cover = album?.get("cover") as? JsonObject
        // ⚠⚠ [CORRECTED 2026-10-09] cover.id IS NOT THE MD5 - MEASURED ON DEVICE, NOT INFERRED.
        // This read `cover.s("id")` and gated it on a 32-hex check, with a note saying a missing cover
        // would be the signal if that guess was wrong. It was wrong, and the signal arrived exactly as
        // described: Pipe rows had no thumbnail in search and no art in the player. The note's own
        // instruction was "do not strip the check without making that measurement first" - the
        // measurement is now made, and the check STAYS; only the source of the md5 changes.
        // ⚠️ THE MD5 COMES OUT OF THE URL, WHICH IS WHY NO SCHEMA FIELD WAS NEEDED. `urls` renders
        // https://cdn-images.dzcdn.net/images/cover/<md5>/500x500-000000-80-0-0.jpg - the same shape
        // DeezerParser.getCover builds - so the hash is the path segment after "/images/cover/". The repo's
        // own queries select only `id` and `urls` on that type and its schema excerpt does not reach the
        // Picture definition, so there is no documented hash field to prefer over this.
        // ⚠️ BOTH SHAPES HANDLED because the arity of `urls` is not established here: a JsonArray
        // (take the first entry) or a bare primitive. Guessing one and being wrong is how this field
        // produced a silent null the first time.
        val coverUrl = cover?.get("urls").let { urls ->
            when (urls) {
                is JsonArray -> (urls.firstOrNull() as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> urls.contentOrNull
                else -> null
            }
        }
        val coverId = coverUrl
            ?.substringAfter("/images/cover/", "")
            ?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() }
            ?: cover?.s("id")
        val artists = ((node["contributors"] as? JsonObject)?.get("edges") as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it["node"] as? JsonObject }
            .orEmpty()
        return buildJsonObject {
            put("__TYPE__", "song")
            put("SNG_ID", id)
            put("SNG_TITLE", title)
            node.s("duration")?.let { put("DURATION", it) }
            put("EXPLICIT_LYRICS", if (node.s("isExplicit") == "true") "1" else "0")
            node.s("ISRC")?.let { put("ISRC", it) }
            album?.s("id")?.let { put("ALB_ID", it) }
            album?.s("displayTitle")?.let { put("ALB_TITLE", it) }
            if (coverId != null && COVER_MD5.matches(coverId)) put("ALB_PICTURE", coverId)
            putJsonArray("ARTISTS") {
                artists.forEach { artist ->
                    addJsonObject {
                        put("ART_ID", artist.s("id").orEmpty())
                        put("ART_NAME", artist.s("name").orEmpty())
                    }
                }
            }
        }
    }

    // ⚠⚠ THE SINGLE PIPE AUTH + TRANSPORT. FACTORED OUT 2026-10-09 BECAUSE THE CODE SAID TO.
    // The note that used to sit inside smartTracklistTrackIds read: "THE HANDSHAKE IS DUPLICATED INLINE
    // in this function and in lyrics, so both carry this exposure. If a third consumer appears, factor
    // the exchange out THEN." Pipe track search is that third consumer, so this is that moment, and the
    // exposure note moves here with it:
    // ⚠️ THIS IS A SECOND AUTH SURFACE, NOT THE GATEWAY'S. ARL + sid are POSTed to
    // auth.deezer.com/login/arl for a JWT used as `Authorization: Bearer` on pipe.deezer.com; callApi
    // never touches that JWT and sends the ARL/sid cookie straight to gw-light.php. SO ONE CAN REFUSE
    // WHILE THE OTHER WORKS, AND THAT IS NOT A CONTRADICTION - measured (Crashlytics, 2026-09-12): this
    // handshake returned `"Invalid Arl provided"` while the gateway kept serving tracks, playlists and
    // search on the same ARL, and it recovered on its own. DO NOT READ A PIPE AUTH FAILURE AS EVIDENCE
    // THAT THE ARL IS BAD - check the gateway before concluding anything.
    //
    // ⚠⚠ [CORRECTED 2026-10-09] THE OLD DECISION WAS "JWT PER CALL, NOT CACHED", AND ITS
    // REASONING IS WHY THE CACHE LOOKS LIKE THIS RATHER THAN BEING DROPPED IN NAIVELY. That note said:
    // the token's TTL is ~6 minutes, a cache needs expiry tracking AND a 401-refresh path, the 401 path
    // has to exist either way, and one extra round trip is invisible when opening a mix. All still true.
    // What changed is the THIRD consumer: AndroidAutoCallback.performSearch wraps a whole search in
    // withTimeout(10_000) covering extension init and a page load, so a cold auth leg per query is how a
    // working AA search becomes an empty one. The note's own release condition - "if a second pipe
    // consumer appears, factor a cached holder THEN" - is met.
    // ⚠️ AND ITS MEASURED NUMBER IS LOAD-BEARING: ~6 MINUTES. A fallback TTL must stay well under
    // that or we would cache a token past its life, which is worse than not caching. Hence 90s, and a
    // 30s margin rather than a larger one - 60s would spend a sixth of a 6-minute token.
    // ⚠️ SO THE MEMO IS A BURST CACHE, NOT A SESSION CACHE. Expect hits within one AA search or a
    // user retyping, and misses across a session. That is the whole intended benefit; the 401 path below
    // is what makes it SAFE, and it would have had to exist even with no cache at all.
    //
    // ⚠️ FINGERPRINTED ON (arl, sid) RATHER THAN INVALIDATED BY A HOOK. A credential change -
    // re-login, ARL refresh, session rotation - changes the hash, so the next call re-exchanges with no
    // invalidate() call anywhere. The alternative meant editing handleArlExpiration and every credential
    // writer, i.e. touching guards whose reasons are recorded elsewhere, and going silently stale the day
    // a fourth writer appears. A fingerprint cannot be forgotten.
    @Volatile
    private var pipeJwtCache: Triple<String, Long, Int>? = null

    // ⚠⚠ [CORRECTED 2026-10-09] THIS USED android.util.Base64 WITH A COMMENT EXPLAINING THAT
    // java.util.Base64 IS API 26 AND minSdk IS 24. BOTH HALVES WERE WRONG, AND THE COMMENT ASSERTED
    // SOMETHING NOBODY HAD CHECKED. deezer-extension/ext is a PLAIN JVM MODULE - `java-library` plus
    // `org.jetbrains.kotlin.jvm`, no Android plugin - so there is no android.* on this classpath at all
    // and minSdk does not apply to it. It failed to compile with "Unresolved reference 'android'".
    // ⚠️ okio's decodeBase64 IS THE RIGHT TOOL AND IT WAS VERIFIED, NOT ASSUMED: a JWT payload is
    // base64URL (`-` and `_`), and okio 3.15.0's commonMain/okio/Base64.kt:68-70 reads
    // `c == '+' || c == '-'` and `c == '/' || c == '_'` - one decoder accepting BOTH alphabets, and
    // lenient about missing padding, which JWTs also omit. okio arrives transitively with OkHttp.
    // ⚠️ AND THE PARSE IS NON-SUSPEND ON PURPOSE. decodeJson is `suspend` (it hops to
    // Dispatchers.IO), which this cannot call and should not want to: the payload is a few dozen bytes
    // and the hop would cost more than the parse. The companion's own `json` instance parses in place.
    private fun jwtExpiryMs(token: String): Long? = runCatching {
        val payload = token.split('.').getOrNull(1)?.decodeBase64()?.utf8()
            ?: return@runCatching null
        val claims = json.parseToJsonElement(payload).jsonObject
        (claims["exp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.times(1000)
    }.getOrNull()

    private suspend fun pipeJwt(force: Boolean = false): String {
        val fingerprint = (arl to sid).hashCode()
        val now = System.currentTimeMillis()
        if (!force) pipeJwtCache?.let { (token, expiresAt, fp) ->
            if (fp == fingerprint && now < expiresAt) return token
        }
        val request = Request.Builder()
            .url("https://auth.deezer.com/login/arl?jo=p&rto=c&i=c")
            .post(RequestBody.EMPTY)
            .headers(Headers.headersOf("Cookie", "arl=$arl; sid=$sid"))
            .build()
        val token = decodeJson(clientNP.newCall(request).await().body.string())["jwt"]
            ?.jsonPrimitive?.contentOrNull
            ?: throw Exception("Deezer pipe auth returned no jwt")
        val expiresAt = jwtExpiryMs(token)?.minus(PIPE_JWT_MARGIN_MS)
            ?: (now + PIPE_JWT_FALLBACK_TTL_MS)
        pipeJwtCache = Triple(token, expiresAt, fingerprint)
        return token
    }

    /**
     * One POST to pipe.deezer.com for an already-built GraphQL body.
     *
     * ⚠️ TAKES THE BUILT `params`, NOT (name, query, variables), AND THAT IS DELIBERATE. Each
     * caller keeps its own query literal where it already lives - those strings carry `$` sigils and
     * escaped newlines, and moving them is how a query silently becomes a different query.
     *
     * ⚠️ THE 401 RETRY IS WHY THE MEMO ABOVE IS SAFE, and the old note already required it
     * independently of any cache. A cached token can expire between our expiry check and the server
     * reading it - `exp` is the issuer's clock, not ours. One retry, only on 401, only once: that turns
     * the race into a slow request instead of a failed one. Retrying any other status would retry real
     * errors, and retrying twice would hide a genuinely dead ARL behind a delay.
     */
    // ⚠️ `params` IS THE ENCODED JSON **STRING**, NOT A JsonObject - encodeJson returns String (it
    // is the same value the gateway path feeds to toRequestBody). Typing it JsonObject compiled nowhere:
    // all three callers pass encodeJson's result and toRequestBody has no JsonObject overload.
    private suspend fun pipeGraphQl(params: String): JsonObject {
        suspend fun post(token: String) = clientNP.newCall(
            Request.Builder()
                .url("https://pipe.deezer.com/api")
                .post(params.toRequestBody())
                .headers(
                    Headers.headersOf(
                        "Authorization", "Bearer $token",
                        "Content-Type", "application/json"
                    )
                )
                .build()
        ).await()

        var response = post(pipeJwt())
        if (response.code == 401) {
            response.close()
            response = post(pipeJwt(force = true))
        }
        return response.use { decodeJson(it.body.string()) }
    }

    //<============= Util =============>

    private val deezerUtil by lazy { DeezerUtil(this) }

    /**
     * Pushes RECOMMENDATION_COUNTRY to the account, at most once per distinct value per session.
     *
     * ⚠⚠ TWO GUARDS, AND THEY EXIST FOR DIFFERENT REASONS - do not collapse them.
     *   NULL means the resolver could not validate a country (see DeezerCountries.resolveApiCountry).
     *     Sending nothing leaves the account's own preference intact, which is the safe answer.
     *   UNCHANGED means we already pushed this exact value successfully in this session. The only
     *     caller is DeezerSearchClient.browseFeed, which runs on EVERY Search/Browse load, so without
     *     this the same account preference was re-written on every browse - a redundant gateway write
     *     per screen, on the one endpoint family a shared-account rate limit would notice first.
     * ⚠️ RECORDED ONLY ON SUCCESS, so a failed push is retried on the next browse rather than
     * being latched as done. The memo lives on DeezerSession and is cleared by
     * DeezerExtension.setLoginUser - RECOMMENDATION_COUNTRY is PER ACCOUNT, so a user switch must be
     * able to push again even when the resolved value has not changed.
     */
    suspend fun updateCountry() {
        val resolved = country ?: return
        if (resolved == session.lastSentCountry) return
        deezerUtil.updateCountry(resolved)
        session.setLastSentCountry(resolved)
    }

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