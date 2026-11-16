package ru.obkhod.app.data.remote

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Минимальный WebDAV-клиент (GET/PUT/MKCOL) с Basic-авторизацией (пароль приложения)
 * или OAuth-токеном (Authorization: OAuth …) — Яндекс Диск поддерживает оба.
 */
class WebDavClient(private val server: String, private val authHeader: String) {

    class HttpException(val code: Int, message: String) : Exception("$code: $message")

    companion object {
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

    private fun connection(url: String, method: String, body: ByteArray? = null): HttpURLConnection {
        // Кириллица в путях папок: кодируем каждый сегмент (разделители сохраняем)
        val encoded = url.trim('/').split("/").joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }.let { "/$it" }
        val conn = URL(server.removeSuffix("/") + encoded).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("Authorization", authHeader)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    private fun exec(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    /** Чтение файла. null = файла нет (404). */
    fun get(path: String): String? {
        val (code, text) = exec(connection(path, "GET"))
        return when (code) {
            in 200..299 -> text
            404 -> null
            else -> throw HttpException(code, text.take(200))
        }
    }

    /** Запись файла (создаёт/перезаписывает). */
    fun put(path: String, content: String) {
        val (code, text) = exec(connection(path, "PUT", content.toByteArray(Charsets.UTF_8)))
        if (code !in 200..299) throw HttpException(code, text.take(200))
    }

    /** Создание папки (включая родителей). Игнорирует «уже существует» (405). */
    fun mkcol(path: String) {
        var i = 0
        val parts = path.trim('/').split('/')
        var current = ""
        while (i < parts.size) {
            current += "/" + parts[i]
            val (code, text) = exec(connection(current, "MKCOL"))
            if (code !in 200..299 && code != 405 && code != 301) throw HttpException(code, text.take(200))
            i++
        }
    }

    /** Существует ли файл/папка (через GET; 404 = нет). */
    fun exists(path: String): Boolean {
        val (code, _) = exec(connection(path, "GET"))
        return code != 404
    }
}
