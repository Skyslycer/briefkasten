package de.skyslycer.briefkasten

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.*
import net.openid.appauth.browser.BrowserAllowList
import net.openid.appauth.browser.VersionedBrowserMatcher
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class LoginRequired : Exception("Please sign in to DHL again. Your saved letters are still available.")
data class Session(val accessToken: String, val subject: String) {
    val account: String get() = sha256(subject.toByteArray())
}

class Auth(private val context: Context, private val vault: Vault) {
    private val mutex = Mutex()
    private val service = AuthorizationService(context)
    private var browserService: AuthorizationService? = null
    private fun load(): AuthState? = vault.read("auth")?.let { AuthState.jsonDeserialize(it.toString(Charsets.UTF_8)) }
    private fun save(state: AuthState) = vault.write("auth", state.jsonSerializeString().toByteArray())
    fun signedIn(): Boolean = runCatching { load()?.refreshToken != null }.getOrDefault(false)

    suspend fun loginIntent(useChrome: Boolean = true): Intent = withContext(Dispatchers.IO) {
        val configuration = suspendCoroutine<AuthorizationServiceConfiguration> { c ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse("https://account.dhl.de")) { config, error ->
                if (config != null) c.resume(config) else c.resumeWithException(error ?: Exception("Could not load DHL login."))
            }
        }
        val options = JSONObject().put("rs", true).put("rv", false).put("rp", false)
            .put("fid", "app-login-mehr-footer").put("hid", "app-login-mehr-header")
        val remoteOptions = Base64.encodeToString(options.toString().toByteArray(), Base64.NO_PADDING or Base64.NO_WRAP)
        val state = UUID.randomUUID().toString()
        val request = AuthorizationRequest.Builder(configuration, CLIENT_ID, ResponseTypeValues.CODE, REDIRECT)
            .setScope("openid email offline_access").setState(state).setPrompt("login").setUiLocales("de")
            .setAdditionalParameters(mapOf("audience" to "https://www.dhl.de", "ext-state" to remoteOptions,
                "ext-redirect_uri" to REDIRECT.toString(), "correlation_id" to UUID.randomUUID().toString())).build()
        vault.write("pending_auth", request.jsonSerializeString().toByteArray())
        browserService?.dispose()
        browserService = AuthorizationService(context, AppAuthConfiguration.Builder().apply {
            if (useChrome) setBrowserMatcher(BrowserAllowList(VersionedBrowserMatcher.CHROME_CUSTOM_TAB))
        }.build())
        browserService!!.getAuthorizationRequestIntent(request)
    }

    suspend fun finishLogin(intent: Intent): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val response = AuthorizationResponse.fromIntent(intent)
            ?: throw (AuthorizationException.fromIntent(intent) ?: Exception("Login cancelled."))
        val pending = vault.read("pending_auth")?.let { AuthorizationRequest.jsonDeserialize(it.toString(Charsets.UTF_8)) }
            ?: error("This login attempt expired. Please try again.")
        require(response.state == pending.state && response.request.codeVerifier == pending.codeVerifier) { "Login response did not match the pending request." }
        val state = AuthState(response, null)
        val token = suspendCoroutine<TokenResponse> { c ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { result, error ->
                if (result != null) c.resume(result) else c.resumeWithException(error ?: Exception("DHL token exchange failed."))
            }
        }
        state.update(token, null)
        require(state.refreshToken != null) { "DHL did not issue an offline session. Please sign in again." }
        subject(state) // Require an account identifier before saving a new session.
        save(state)
        vault.remove("pending_auth")
    } }

    suspend fun session(forceRefresh: Boolean = false): Session = withContext(Dispatchers.IO) { mutex.withLock {
        val state = load() ?: throw LoginRequired()
        if (forceRefresh) state.setNeedsTokenRefresh(true)
        val access = suspendCoroutine<String> { c ->
            state.performActionWithFreshTokens(service) { access, _, error ->
                try {
                    save(state) // Includes rotated refresh tokens and permanent auth errors.
                    when {
                        access != null -> c.resume(access)
                        error?.error == "invalid_grant" || error?.error == "invalid_token" -> c.resumeWithException(LoginRequired())
                        else -> c.resumeWithException(Exception("Could not refresh DHL login. Check your connection and try again."))
                    }
                } catch (e: Exception) { c.resumeWithException(e) }
            }
        }
        Session(access, subject(state))
    } }

    suspend fun signOut() = withContext(Dispatchers.IO) { mutex.withLock {
        // Local disconnect: keeps the archive; browser's DHL session is independent.
        vault.remove("auth"); vault.remove("pending_auth")
    } }

    private fun subject(state: AuthState): String {
        val token = state.idToken ?: state.accessToken ?: throw LoginRequired()
        val payload = token.split('.').getOrNull(1) ?: throw LoginRequired()
        return JSONObject(String(Base64.decode(payload, Base64.URL_SAFE), Charsets.UTF_8)).getString("sub").also {
            require(it.isNotBlank() && !it.contains(';') && !it.contains('\n') && !it.contains('\r'))
        }
    }
    companion object {
        const val CLIENT_ID = "f1KpQPzTyKeb5sgbw5y5Rb5Xa5fzZg5g"
        val REDIRECT: Uri = Uri.parse("dhllogin://de.dhl.paket/login")
    }
}
