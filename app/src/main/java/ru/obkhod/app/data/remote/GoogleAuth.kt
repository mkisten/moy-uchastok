package ru.obkhod.app.data.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse

/**
 * OAuth-вход через Google (AppAuth, браузер без вебвью).
 * Требуется GOOGLE_CLIENT_ID (gradle.properties: googleClientId=...).
 * Scopes: spreadsheets + drive.file (видит только файлы, созданные приложением).
 */
object GoogleAuth {

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val SCOPE_SHEETS = "https://www.googleapis.com/auth/spreadsheets"
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val SCOPE_EMAIL = "https://www.googleapis.com/auth/userinfo.email"

    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAt: Long)

    private val prefs get() =
        appContext.getSharedPreferences("google_auth", Context.MODE_PRIVATE)

    private lateinit var appContext: Context
    private var authService: AuthorizationService? = null

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email

    private val _authMessage = MutableStateFlow<String?>(null)
    val authMessage: StateFlow<String?> = _authMessage

    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        _email.value = prefs.getString("email", null)
    }

    fun isConfigured(): Boolean = ru.obkhod.app.BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()
    fun isSignedIn(): Boolean = prefs.getString("refresh_token", null) != null

    private fun redirectUri(): String =
        "com.googleusercontent.apps.${ru.obkhod.app.BuildConfig.GOOGLE_CLIENT_ID}:/oauthredirect"

    /** Запуск браузера Google-авторизации. Завершение придёт в MainActivity через intent. */
    fun startAuth(activity: Activity) {
        val service = getAuthService(activity)
        val cfg = AuthorizationServiceConfiguration(
            Uri.parse(AUTH_ENDPOINT), Uri.parse(TOKEN_ENDPOINT),
        )
        val request = AuthorizationRequest.Builder(
            cfg, ru.obkhod.app.BuildConfig.GOOGLE_CLIENT_ID, ResponseTypeValues.CODE, Uri.parse(redirectUri()),
        ).setScopes(listOf(SCOPE_SHEETS, SCOPE_DRIVE_FILE, SCOPE_EMAIL)).build()

        activity.startActivity(service.getAuthorizationRequestIntent(request))
    }


    /**
     * Вызов из MainActivity при получении redirect- или cancel-intent.
     * Обмен кода выполняется глобально (независимо от открытого экрана).
     */
    fun handleRedirectIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == "ru.obkhod.app.AUTH_CANCELED") {
            _authMessage.value = "Вход отменён"
            return
        }
        val data = intent.data ?: return
        if (!data.toString().startsWith("com.googleusercontent.apps.")) return
        _authMessage.value = "Завершение входа…"
        bgScope.launch {
            try {
                val code = data.getQueryParameter("code")
                if (code.isNullOrBlank()) throw IllegalStateException("Вход отменён")
                val cfg = AuthorizationServiceConfiguration(Uri.parse(AUTH_ENDPOINT), Uri.parse(TOKEN_ENDPOINT))
                val request = TokenRequest.Builder(cfg, ru.obkhod.app.BuildConfig.GOOGLE_CLIENT_ID)
                    .setGrantType(net.openid.appauth.GrantTypeValues.AUTHORIZATION_CODE)
                    .setAuthorizationCode(code)
                    .setRedirectUri(Uri.parse(redirectUri()))
                    .build()
                val service = getAuthService(appContext)
                var tokens: TokenResponse? = null
                var error: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                service.performTokenRequest(request, NoClientAuthentication.INSTANCE) { t, ex ->
                    tokens = t
                    error = ex?.errorDescription ?: ex?.error
                    latch.countDown()
                }
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
                val resp = tokens ?: throw IllegalStateException(error ?: "Нет ответа от Google")
                val expiresAt = resp.accessTokenExpirationTime ?: (System.currentTimeMillis() + 3600_000L)
                saveTokens(resp.accessToken!!, resp.refreshToken, expiresAt)
                val email = try {
                    val body = GoogleRest.get("https://www.googleapis.com/oauth2/v2/userinfo")
                    org.json.JSONObject(body).optString("email", "").ifBlank { null }
                } catch (e: Exception) { null }
                _email.value = email
                prefs.edit().putString("email", email).apply()
                _authMessage.value = if (email != null) "Вход выполнен: $email" else "Вход выполнен"
            } catch (e: Exception) {
                _authMessage.value = "Ошибка входа: ${e.message}"
            }
        }
    }

    /** Действующий access-токен (обновляет при истечении). Бросает исключение при неудаче. */
    @Synchronized
    fun accessToken(): String {
        val expiresAt = prefs.getLong("expires_at", 0)
        var access = prefs.getString("access_token", null)
        if (access != null && System.currentTimeMillis() < expiresAt - 60_000) return access
        val refresh = prefs.getString("refresh_token", null)
            ?: throw IllegalStateException("Нет сохранённой авторизации Google")
        val cfg = AuthorizationServiceConfiguration(Uri.parse(AUTH_ENDPOINT), Uri.parse(TOKEN_ENDPOINT))
        val request = TokenRequest.Builder(cfg, ru.obkhod.app.BuildConfig.GOOGLE_CLIENT_ID)
            .setGrantType(net.openid.appauth.GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(refresh)
            .build()
        val service = getAuthService(appContext)
        var result: Pair<String, Long>? = null
        var error: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        service.performTokenRequest(request, NoClientAuthentication.INSTANCE) { tokens, ex ->
            if (tokens != null && ex == null) {
                val expires = tokens.accessTokenExpirationTime ?: (System.currentTimeMillis() + 3600_000L)
                result = Pair(tokens.accessToken!!, expires)
            } else {
                error = ex?.errorDescription ?: ex?.error ?: "ошибка обновления токена"
            }
            latch.countDown()
        }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        val r = result ?: throw IllegalStateException(error ?: "Не удалось обновить токен Google")
        access = r.first
        prefs.edit().putString("access_token", access).putLong("expires_at", r.second).apply()
        return access
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _email.value = null
    }

    fun clearAuthMessage() {
        _authMessage.value = null
    }

    private fun saveTokens(access: String, refresh: String?, expiresAt: Long) {
        prefs.edit()
            .putString("access_token", access)
            .putLong("expires_at", expiresAt)
            .apply()
        // refresh_token выдаётся Google только при первом входе — не затираем
        if (!refresh.isNullOrBlank()) prefs.edit().putString("refresh_token", refresh).apply()
    }

    private fun getAuthService(context: Context): AuthorizationService {
        if (!::appContext.isInitialized) init(context)
        return authService ?: AuthorizationService(context.applicationContext).also { authService = it }
    }
}
