package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings
import java.util.concurrent.atomic.AtomicReference

class DeezerSession(
    var settings: Settings? = null,
    @Volatile var arlExpired: Boolean = false
) {

    data class DeezerCredentials(
        val arl: String,
        val sid: String,
        val token: String,
        val userId: String,
        val licenseToken: String,
        val email: String,
        val pass: String
    )

    private val credentialsRef = AtomicReference(
        DeezerCredentials("", "", "", "", "", "", "")
    )

    var credentials: DeezerCredentials
        get() = credentialsRef.get()
        private set(value) = credentialsRef.set(value)

    fun updateCredentials(
        arl: String? = null,
        sid: String? = null,
        token: String? = null,
        userId: String? = null,
        licenseToken: String? = null,
        email: String? = null,
        pass: String? = null
    ) {
        credentialsRef.updateAndGet { current ->
            current.copy(
                arl = arl ?: current.arl,
                sid = sid ?: current.sid,
                token = token ?: current.token,
                userId = userId ?: current.userId,
                licenseToken = licenseToken ?: current.licenseToken,
                email = email ?: current.email,
                pass = pass ?: current.pass
            )
        }
    }

    fun isArlExpired(expired: Boolean) {
        arlExpired = expired
    }

    /**
     * Deezer has REFUSED these stored credentials. Distinct from [arlExpired], and the distinction is
     * the point: an expired ARL is RECOVERABLE without the user (re-login silently with the stored
     * email/pass), a refusal is NOT - only the user can fix it.
     *
     * ⚠⚠ IT EXISTS TO MAKE A REPEATED FAILURE CHEAP, NOT TO REMEMBER STATE FOR ITS OWN SAKE.
     * handleArlExpiration runs inside the Injectable INJECTION BLOCK (ExtensionLoader.injected), and
     * Injectable.value() only clears `injections` AFTER the block completes - so a block that throws
     * leaves the injections pending and RE-RUNS ON EVERY LATER value() CALL. Android Auto calls
     * value() for every enabled extension on every browse-root build (Extension<*>.toMediaItem), so
     * without this flag a user with dead credentials submits a fresh rejected login to Deezer on
     * EVERY AA ROOT BUILD - the shared-account rate-limit exposure that getArlByEmail's retry-break
     * exists to bound, reintroduced one level up. With it, the re-run costs a boolean read and a
     * throw. The user still gets the prompt every time; Deezer stops being asked.
     * ⚠️ FREQUENCY, STATED HONESTLY BECAUSE IT IS THE MULTIPLIER: the AA root is built at least
     * once per connect (measured, recorded at AndroidAutoCallback's ROOT-subscription note) and again
     * per RENEGOTIATION (onDisconnected fires per renegotiation, not per connection). HOW OFTEN
     * RENEGOTIATIONS HAPPEN IS NOT MEASURED ANYWHERE - do not repeat the "frequent mid-drive" claim
     * in that file's stale-tile note, which attributes it to the onDisconnected note, which does not
     * say it.
     *
     * Set where a silent re-login is refused; cleared by [DeezerExtension.setLoginUser], which runs on
     * every successful login and on logout. Not persisted - a fresh process retries once, which is
     * correct: the credentials may have been fixed on the Deezer side.
     */
    @Volatile
    var credentialsRejected: Boolean = false
        private set

    fun setCredentialsRejected(rejected: Boolean) {
        credentialsRejected = rejected
    }

    /**
     * The RECOMMENDATION_COUNTRY value last pushed SUCCESSFULLY in this session, or null if none.
     * Read by [DeezerApi.updateCountry] to skip a redundant write; see that function for why.
     *
     * ⚠️ CLEARED BY [DeezerExtension.setLoginUser] FOR A CONCRETE REASON, not for symmetry
     * with [credentialsRejected]: the preference is PER ACCOUNT, so after a logout or a user switch
     * the new account has not been told anything, and a stale memo would skip the push the new
     * account needs. Same chokepoint, same argument - it runs on login, logout and user switch.
     * Not persisted: a fresh process pushes once, which is correct and cheap.
     */
    @Volatile
    var lastSentCountry: String? = null
        private set

    fun setLastSentCountry(country: String?) {
        lastSentCountry = country
    }

    companion object {
        @Volatile
        private var instance: DeezerSession? = null

        fun getInstance(): DeezerSession {
            return instance ?: synchronized(this) {
                instance ?: DeezerSession().also { instance = it }
            }
        }
    }
}