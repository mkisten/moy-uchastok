package ru.obkhod.app.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Вход через Яндекс ID (OAuth): открывается страница Яндекса с подтверждением
 * по телефону/в приложении, пользователь вставляет «код подтверждения» в приложение,
 * код обменивается на OAuth-токен (официальный способ для приложений без redirect-URI,
 * как у CLI Яндекс Диска). Токен работает и с REST API, и с WebDAV.
 */
object YandexAuth {

    private const val AUTHORIZE_URL = "https://oauth.yandex.ru/authorize"
    private const val TOKEN_URL = "https://oauth.yandex.ru/token"

    private val prefs get() =
        appContext.getSharedPreferences("yandex_oauth", Context.MODE_PRIVATE)

    private lateinit var appContext: Context

    private val _login = MutableStateFlow<String?>(null)
    val login: StateFlow<String?> = _login

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun init(context: Context) {
        appContext = context.applicationContext
        _login.value = prefs.getString("login", null)
    }

    fun isConfigured(): Boolean = ru.obkhod.app.BuildConfig.YANDEX_CLIENT_ID.isNotBlank()
    fun isSignedIn(): Boolean = prefs.getString("access_token", null) != null

    /** URL страницы входа Яндекса (подтверждение в приложении/по телефону происходит там). */
    fun authorizeUrl(): String =
        "https://oauth.yandex.ru/authorize?response_type=code" +
            "&client_id=${ru.obkhod.app.BuildConfig.YANDEX_CLIENT_ID}&force_confirm=yes"

    fun openLoginPage(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl())))
    }

    private fun postForm(url: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    /**
     * Обмен кода подтверждения на OAuth-токен.
     * При успехе сохраняет токен и возвращает логин аккаунта (или null при ошибке).
     */
    @Synchronized
    fun exchangeCode(codeRaw: String): String? {
        val code = codeRaw.trim()
        val (code2, text) = postForm(
            TOKEN_URL,
            "grant_type=authorization_code" +
                "&code=${java.net.URLEncoder.encode(code, "UTF-8")}" +
                "&client_id=${ru.obkhod.app.BuildConfig.YANDEX_CLIENT_ID}" +
                "&client_secret=${java.net.URLEncoder.encode(ru.obkhod.app.BuildConfig.YANDEX_CLIENT_SECRET, "UTF-8")}",
        )
        if (code2 !in 200..299) {
            _message.value = "Яндекс отклонил код: вставьте свежий код подтверждения со страницы входа"
            return null
        }
        val json = JSONObject(text)
        val access = json.optString("access_token", "")
        if (access.isBlank()) {
            _message.value = "Ответ Яндекса без токена"
            return null
        }
        val refresh = json.optString("refresh_token", "").ifBlank { null }
        val expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 604_800L) * 1000L
        prefs.edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putLong("expires_at", expiresAt)
            .apply()
        val login = fetchLogin(access)
        _login.value = login ?: "аккаунт"
        prefs.edit().putString("login", _login.value).apply()
        return _login.value
    }

    private fun fetchLogin(token: String): String? = try {
        val conn = URL("https://login.yandex.ru/info?format=json").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "OAuth $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        JSONObject(body).optString("login", "").ifBlank { null }
    } catch (e: Exception) { null }

    /** Действующий OAuth-токен (refresh при истечении, если Яндекс выдал refresh_token). */
    @Synchronized
    fun accessToken(): String {
        val expiresAt = prefs.getLong("expires_at", 0)
        var access = prefs.getString("access_token", null)
        if (access != null && System.currentTimeMillis() < expiresAt - 60_000) return access
        val refresh = prefs.getString("refresh_token", null)
            ?: throw IllegalStateException("Токен Яндекса истёк — войдите через Яндекс заново")
        val (code, text) = postForm(
            TOKEN_URL,
            "grant_type=refresh_token" +
                "&refresh_token=${java.net.URLEncoder.encode(refresh, "UTF-8")}" +
                "&client_id=${ru.obkhod.app.BuildConfig.YANDEX_CLIENT_ID}" +
                "&client_secret=${java.net.URLEncoder.encode(ru.obkhod.app.BuildConfig.YANDEX_CLIENT_SECRET, "UTF-8")}",
        )
        if (code !in 200..299) throw IllegalStateException("Токен Яндекса истёк — войдите через Яндекс заново")
        val json = JSONObject(text)
        access = json.optString("access_token", access ?: "")
        val newRefresh = json.optString("refresh_token", "").ifBlank { refresh }
        val expiresAt2 = System.currentTimeMillis() + json.optLong("expires_in", 604_800L) * 1000L
        prefs.edit()
            .putString("access_token", access)
            .putString("refresh_token", newRefresh)
            .putLong("expires_at", expiresAt2)
            .apply()
        return access
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _login.value = null
    }

    fun clearMessage() {
        _message.value = null
    }
}
