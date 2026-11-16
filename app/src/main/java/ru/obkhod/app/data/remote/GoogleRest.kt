package ru.obkhod.app.data.remote

import java.net.HttpURLConnection
import java.net.URL

/** Минимальный REST-клиент Google API с автообновлением токена при 401. */
object GoogleRest {

    class HttpException(val code: Int, message: String) : Exception("$code: $message")

    private fun connection(url: String, method: String, body: String?): HttpURLConnection {
        val token = GoogleAuth.accessToken()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("User-Agent", "moy-uchastok-app")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn
    }

    private fun exec(url: String, method: String, body: String?): String {
        val conn = connection(url, method, body)
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw HttpException(code, text.take(300))
        return text
    }

    fun get(url: String): String = withRetry { exec(url, "GET", null) }
    fun post(url: String, body: String): String = withRetry { exec(url, "POST", body) }
    fun patch(url: String, body: String): String = withRetry { exec(url, "PATCH", body) }
    fun delete(url: String): String = withRetry { exec(url, "DELETE", null) }

    /** Один повтор после обновления токена при 401. */
    private fun <T> withRetry(block: () -> T): T {
        return try {
            block()
        } catch (e: HttpException) {
            if (e.code == 401) block() else throw e
        }
    }
}
