package ru.obkhod.app.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WebDAV-клиент на OkHttp (поддерживает нестандартные методы, например MKCOL).
 * Авторизация: Basic (пароль приложения) или OAuth-токен (вход через Яндекс ID).
 */
class WebDavClient private constructor(private val server: String, private val authHeader: String) {

    class HttpException(val code: Int, message: String) : Exception("$code: $message")

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        private val jsonMedia = "application/octet-stream".toMediaType()

        /** Basic-авторизация (логин + пароль приложения). */
        fun basic(server: String, login: String, password: String): WebDavClient {
            val auth = java.util.Base64.getEncoder()
                .encodeToString("$login:$password".toByteArray(Charsets.UTF_8))
            return WebDavClient(server, "Basic $auth")
        }

        /** Авторизация OAuth-токеном (вход через Яндекс ID). */
        fun oauth(server: String, token: String): WebDavClient =
            WebDavClient(server, "OAuth $token")
    }

    private fun encodePath(path: String): String =
        "/" + path.trim('/').split("/").joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    private fun request(method: String, path: String, body: String? = null): Pair<Int, String> {
        val url = server.removeSuffix("/") + encodePath(path)
        val builder = Request.Builder().url(url).header("Authorization", authHeader)
        val req = when {
            method == "GET" -> builder.get()
            body != null -> builder.method(method, body.toRequestBody(jsonMedia))
            else -> builder.method(method, null)
        }.build()
        client.newCall(req).execute().use { response ->
            val code = response.code
            val text = response.body?.string() ?: ""
            if (code !in 200..299 && code != 405) throw HttpException(code, text.take(300))
            return code to text
        }
    }

    /** Чтение файла. null = файла нет (404). */
    fun get(path: String): String? = try {
        val (code, text) = request("GET", path)
        if (code == 404) null else text
    } catch (e: HttpException) {
        if (e.code == 404) null else throw e
    }

    /** Запись файла (создаёт/перезаписывает). */
    fun put(path: String, content: String) {
        request("PUT", path, content)
    }

    /** Создание папки (включая родителей). «Уже существует» игнорируется. */
    fun mkcol(path: String) {
        val parts = path.trim('/').split('/')
        var current = ""
        for (part in parts) {
            current += "/" + part
            try {
                request("MKCOL", current)
            } catch (e: HttpException) {
                if (e.code != 405 && e.code != 301) throw e // 405 = папка уже существует
            }
        }
    }

    /** Существует ли файл/папка (через GET; 404 = нет). */
    fun exists(path: String): Boolean = try {
        val (code, _) = request("GET", path)
        code != 404
    } catch (e: HttpException) {
        e.code != 404
    }
}
