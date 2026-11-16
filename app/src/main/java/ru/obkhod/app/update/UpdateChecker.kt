package ru.obkhod.app.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновлений через GitHub Releases (репозиторий mkisten/moy-uchastok).
 * Приложение работает офлайн; запрос выполняется только при открытии главного экрана,
 * при любой ошибке (нет сети и т.д.) проверка просто пропускается.
 */
object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/mkisten/moy-uchastok/releases/latest"

    data class UpdateInfo(
        val version: String,      // например "1.1.0" (из tag_name без «v»)
        val changelog: String,    // описание релиза
        val apkUrl: String,       // прямая ссылка на app-release.apk в релизе
        val releasePageUrl: String,
    )

    /** Есть ли обновление: сравнение SemVer-подобных версий «major.minor.patch». */
    fun isNewer(latest: String, current: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val a = parse(latest)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x > y) return true
            if (x < y) return false
        }
        return false
    }

    /** Запрос последнего релиза. Возвращает null при отсутствии сети/ошибке. */
    fun fetchLatest(currentVersion: String): UpdateInfo? {
        return try {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "moy-uchastok-app")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").ifBlank { return null }
            if (!isNewer(tag, currentVersion)) return null

            val assets = json.optJSONArray("assets")
            var apkUrl = json.optString("html_url", "")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            UpdateInfo(
                version = tag.removePrefix("v"),
                changelog = json.optString("body", "").trim(),
                apkUrl = apkUrl,
                releasePageUrl = json.optString("html_url", ""),
            )
        } catch (e: Exception) {
            null // офлайн или GitHub недоступен — не мешаем работе
        }
    }
}
