package ru.obkhod.app.sync

import android.content.Context
import android.util.AtomicFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.obkhod.app.data.repo.newUuid
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class TeamConnection(
    val id: String,
    val endpoint: String,
    val token: String,
    val invite: String? = null,
    val seed: Boolean = false,
)

internal class TeamConnectionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "team_connection.json"))

    fun load(): TeamConnection? {
        val json = try {
            JSONObject(file.readFully().toString(Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            return null
        }
        return TeamConnection(
            id = json.getString("id"), endpoint = json.getString("endpoint"), token = json.getString("token"),
            invite = if (json.isNull("invite")) null else json.getString("invite"), seed = json.optBoolean("seed"),
        )
    }

    fun save(link: TeamConnection) {
        val content = JSONObject().put("id", link.id).put("endpoint", link.endpoint).put("token", link.token)
            .put("invite", link.invite ?: JSONObject.NULL).put("seed", link.seed)
        val stream = file.startWrite()
        try {
            stream.write(content.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun clear() = file.delete()

    companion object {
        fun exists(context: Context): Boolean = File(context.noBackupFilesDir, "team_connection.json").exists()

        fun create(endpoint: String, invite: String, seed: Boolean): TeamConnection {
            require(Regex("""https://script\.google\.com/macros/s/[A-Za-z0-9_-]+/exec""").matches(endpoint)) {
                "Вставьте ссылку на развёрнутое веб-приложение Apps Script, которая заканчивается на /exec"
            }
            require(invite.matches(Regex("[A-Fa-f0-9]{32}"))) { "Проверьте код приглашения" }
            return TeamConnection(newUuid(), endpoint, newUuid().replace("-", "") + newUuid().replace("-", ""), invite.lowercase(), seed)
        }
    }
}

internal fun interface TeamTransport {
    fun request(link: TeamConnection, request: JSONObject): JSONObject
}

internal object TeamHttp : TeamTransport {
    private val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    override fun request(link: TeamConnection, request: JSONObject): JSONObject {
        val payload = request.put("protocol", 1).put("token", link.token).toString()
        val response = client.newCall(Request.Builder().url(link.endpoint).post(payload.toRequestBody(json)).build())
        val body = response.execute().use {
            if (!it.isSuccessful) throw IOException("Сервис синхронизации недоступен (HTTP ${it.code})")
            it.body?.string() ?: throw IOException("Пустой ответ сервиса")
        }
        val parsed = try {
            JSONObject(body)
        } catch (_: org.json.JSONException) {
            throw IOException("Сервис вернул неверный ответ. Проверьте ссылку и настройки доступа Apps Script.")
        }
        if (!parsed.optBoolean("ok")) throw IOException(parsed.optString("error", "Запрос отклонён сервером"))
        return parsed
    }
}
