package ru.obkhod.app.data.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Информация о команде (общей таблице). */
data class TeamInfo(
    val spreadsheetId: String,
    val teamName: String,
    val teamCode: String,
    val shareUrl: String,
    val role: String, // OWNER / WRITER / READER (по Google-правам)
)

/**
 * Работа с командной Google-таблицей: создание, подключение, участники.
 * Файл создаётся на Google Диске текущего пользователя.
 */
class TeamApi(private val context: Context) {

    private val prefs get() = context.getSharedPreferences("cloud_team", Context.MODE_PRIVATE)

    fun currentTeamId(): String? = prefs.getString("spreadsheet_id", null)

    fun teamCode(): String? = prefs.getString("team_code", null)

    fun teamName(): String? = prefs.getString("team_name", null)

    fun forgetTeam() {
        prefs.edit().clear().apply()
    }

    private fun saveTeam(id: String, name: String, code: String) {
        prefs.edit()
            .putString("spreadsheet_id", id)
            .putString("team_name", name)
            .putString("team_code", code)
            .apply()
    }

    /** Создание командной таблицы со всеми листами. Возвращает TeamInfo или бросает исключение. */
    fun createTeam(teamName: String): TeamInfo {
        // 1. Создаём файл на Диске
        val createBody = JSONObject()
            .put("name", "Мой участок — $teamName")
            .put("mimeType", "application/vnd.google-apps.spreadsheet")
        val created = JSONObject(
            GoogleRest.post("https://www.googleapis.com/drive/v3/files", createBody.toString())
        )
        val id = created.getString("id")

        // 2. Листы
        val sheetsBody = JSONObject().put(
            "requests", JSONArray()
                .put(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", "apartments"))))
                .put(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", "history"))))
                .put(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", "structure"))))
                .put(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", "meta"))))
        )
        GoogleRest.post(
            "https://sheets.googleapis.com/v4/spreadsheets/$id:batchUpdate", sheetsBody.toString()
        )

        // 3. Заголовки и meta — одним batchUpdate
        val code = makeCode(id)
        val dataArr = JSONArray()
        dataArr.put(JSONObject()
            .put("range", "meta!A1:B6")
            .put("values", JSONArray()
                .put(JSONArray().put("team_name").put(teamName))
                .put(JSONArray().put("team_code").put(code))
                .put(JSONArray().put("created_by").put(GoogleAuth.email.value ?: ""))
                .put(JSONArray().put("created_at").put(System.currentTimeMillis()))
                .put(JSONArray().put("schema_version").put(1))
                .put(JSONArray().put("app").put("moy-uchastok"))
            ))
        dataArr.put(JSONObject().put("range", "apartments!A1:L1").put("values", JSONArray().put(JSONArray(toApartmentsHeaders()))))
        dataArr.put(JSONObject().put("range", "history!A1:H1").put("values", JSONArray().put(JSONArray(toHistoryHeaders()))))
        dataArr.put(JSONObject().put("range", "structure!A1:I1").put("values", JSONArray().put(JSONArray(toStructureHeaders()))))
        val meta = JSONObject().put("data", dataArr).put("valueInputOption", "RAW")
        GoogleRest.post(
            "https://sheets.googleapis.com/v4/spreadsheets/$id/values:batchUpdate", meta.toString()
        )
        saveTeam(id, teamName, code)
        return TeamInfo(id, teamName, code, "https://docs.google.com/spreadsheets/d/$id/edit", "OWNER")
    }

    private fun toApartmentsHeaders() = listOf(
        "uuid", "entrance_uuid", "floor_uuid", "number", "label", "status",
        "phone", "comment", "lastVisitAt", "deleted", "hasChild", "updated_at", "updated_by",
    )
    private fun toHistoryHeaders() = listOf(
        "uuid", "apartment_uuid", "visitDate", "previousStatus", "newStatus", "comment", "eventType", "updated_by",
    )
    private fun toStructureHeaders() = listOf(
        "kind", "uuid", "parent_uuid", "num1", "num2", "name", "deleted", "updated_at", "updated_by",
    )

    /** Подключение по ссылке или ID таблицы: проверка доступа чтением meta. */
    fun join(linkOrId: String): TeamInfo {
        val id = extractId(linkOrId)
            ?: throw IllegalArgumentException("Вставьте ссылку на таблицу команды (docs.google.com/spreadsheets/d/…)")
        val meta = readSheet(id, "meta!A1:B20")
        var name = "Команда"; var code = ""
        for (row in meta) {
            if (row.size >= 2) {
                when (row[0]) {
                    "team_name" -> name = row[1]
                    "team_code" -> code = row[1]
                }
            }
        }
        if (code.isBlank()) throw IllegalArgumentException("Это не таблица «Мой участок» (нет meta)")
        saveTeam(id, name, code)
        return TeamInfo(id, name, code, "https://docs.google.com/spreadsheets/d/$id/edit", roleOf(id))
    }

    /** Добавить редактора по email. */
    fun addWriter(email: String) {
        val id = currentTeamId() ?: throw IllegalStateException("Команда не подключена")
        val body = JSONObject()
            .put("type", "user")
            .put("role", "writer")
            .put("emailAddress", email.trim())
        GoogleRest.post(
            "https://www.googleapis.com/drive/v3/files/$id/permissions?sendNotificationEmail=true",
            body.toString(),
        )
    }

    /** Список участников (email + роль). */
    fun members(): List<Pair<String, String>> {
        val id = currentTeamId() ?: return emptyList()
        val json = JSONObject(GoogleRest.get("https://www.googleapis.com/drive/v3/files/$id/permissions?fields=permissions(id,emailAddress,role)&pageSize=100"))
        val arr = json.optJSONArray("permissions") ?: JSONArray()
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            list.add((p.optString("emailAddress", "(без email)")) to p.optString("role", "?"))
        }
        return list
    }

    /** Удаление участника по permissionId. */
    fun removeMember(permissionId: String) {
        val id = currentTeamId() ?: throw IllegalStateException("Команда не подключена")
        GoogleRest.delete("https://www.googleapis.com/drive/v3/files/$id/permissions/$permissionId")
    }

    /** permissionId по email (для удаления участника). */
    fun findPermissionId(email: String): String? {
        val id = currentTeamId() ?: return null
        val json = JSONObject(GoogleRest.get("https://www.googleapis.com/drive/v3/files/$id/permissions?fields=permissions(id,emailAddress,role)&pageSize=100"))
        val arr = json.optJSONArray("permissions") ?: return null
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.optString("emailAddress", "").equals(email.trim(), ignoreCase = true)) return p.getString("id")
        }
        return null
    }

    /** Полные permissions (id, email, role). */
    fun permissions(): List<Triple<String, String, String>> {
        val id = currentTeamId() ?: return emptyList()
        val json = JSONObject(GoogleRest.get("https://www.googleapis.com/drive/v3/files/$id/permissions?fields=permissions(id,emailAddress,role)&pageSize=100"))
        val arr = json.optJSONArray("permissions") ?: return emptyList()
        val list = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            list.add(Triple(p.getString("id"), p.optString("emailAddress", "?"), p.optString("role", "?")))
        }
        return list
    }

    /** Чтение диапазона листа (2D-массив строк). */
    fun readSheet(range: String): List<List<String>> {
        val id = currentTeamId() ?: return emptyList()
        return readSheet(id, range)
    }

    fun readSheet(spreadsheetId: String, range: String): List<List<String>> {
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/${java.net.URLEncoder.encode(range, "UTF-8")}"
        val json = JSONObject(GoogleRest.get(url))
        val rows = json.optJSONArray("values") ?: return emptyList()
        val result = mutableListOf<List<String>>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            val list = mutableListOf<String>()
            for (j in 0 until row.length()) list.add(row.getString(j))
            result.add(list)
        }
        return result
    }

    /** Запись строк в диапазон (замена). */
    fun writeRange(range: String, rows: List<List<String>>) {
        val id = currentTeamId() ?: throw IllegalStateException("Команда не подключена")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$id/values/${java.net.URLEncoder.encode(range, "UTF-8")}?valueInputOption=RAW"
        GoogleRest.post(url, JSONObject().put("values", JSONArray(rows.map { JSONArray(it) })).toString())
    }

    /** Добавление строк в конец листа. */
    fun appendRows(sheet: String, rows: List<List<String>>) {
        if (rows.isEmpty()) return
        val id = currentTeamId() ?: throw IllegalStateException("Команда не подключена")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$id/values/${sheet}!A1:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS"
        GoogleRest.post(url, JSONObject().put("values", JSONArray(rows.map { JSONArray(it) })).toString())
    }

    companion object {
        /** Короткий код команды из ID таблицы (для сверки «тот ли файл»). */
        fun makeCode(spreadsheetId: String): String =
            spreadsheetId.filter { it.isLetterOrDigit() }.takeLast(6).uppercase()

        /** Извлечение ID из ссылки docs.google.com/spreadsheets/d/… или из голого ID. */
        fun extractId(text: String): String? {
            val trimmed = text.trim()
            Regex("/d/([A-Za-z0-9_-]{20,})").find(trimmed)?.let { return it.groupValues[1] }
            if (trimmed.matches(Regex("[A-Za-z0-9_-]{20,}"))) return trimmed
            return null
        }

        private fun roleOf(id: String): String {
            return try {
                val json = JSONObject(GoogleRest.get("https://www.googleapis.com/drive/v3/files/$id?fields=ownedByMe"))
                if (json.optBoolean("ownedByMe", false)) "OWNER" else "WRITER"
            } catch (e: Exception) { "WRITER" }
        }
    }
}
