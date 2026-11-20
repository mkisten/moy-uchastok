package ru.obkhod.app.sync

import org.json.JSONArray
import org.json.JSONObject
import ru.obkhod.app.data.repo.newUuid

private data class RowKey(val table: String, val uuid: String)

/** Документ без локальных Room ID. Ссылки между строками хранятся по UUID. */
internal class TeamDocument private constructor(private val rows: Map<RowKey, JSONObject>) {
    companion object {
        val tables = listOf("areas", "entrances", "floors", "apartments", "history")
        private val refs = mapOf(
            "entrances" to mapOf("areaId" to "areas"),
            "floors" to mapOf("entranceId" to "entrances"),
            "apartments" to mapOf("entranceId" to "entrances", "floorId" to "floors"),
            "history" to mapOf("apartmentId" to "apartments"),
        )

        fun read(json: JSONObject): TeamDocument = TeamDocument(buildMap {
            for (table in tables) {
                for (row in json.optJSONArray(table).objects()) {
                    val uuid = row.getString("uuid")
                    require(uuid.isNotBlank() && put(RowKey(table, uuid), row) == null) {
                        "Повторяющийся или пустой UUID в данных команды"
                    }
                }
            }
        })

        fun capture(snapshot: JSONObject): TeamDocument {
            val ids = tables.associateWith { table ->
                snapshot.getJSONArray(table).objects().associate { it.getLong("id") to it.getString("uuid") }
            }
            val wire = JSONObject()
            for (table in tables) {
                wire.put(table, JSONArray().apply {
                    for (row in snapshot.getJSONArray(table).objects()) {
                        val converted = JSONObject(row.toString())
                        converted.remove("id")
                        for ((field, parent) in refs[table].orEmpty()) {
                            val uuid = ids.getValue(parent)[row.getLong(field)]
                            require(uuid != null || (field == "floorId" && !row.isNull("deletedAt"))) {
                                "В локальной базе отсутствует связанная запись"
                            }
                            converted.put(field, uuid ?: JSONObject.NULL)
                        }
                        put(converted)
                    }
                })
            }
            return read(wire)
        }
    }

    val size: Int get() = rows.size

    fun encode(): JSONObject = JSONObject().apply {
        for (table in tables) put(table, JSONArray().apply {
            rows.filterKeys { it.table == table }.values.forEach { put(it) }
        })
    }

    fun changesFrom(base: TeamDocument): JSONArray = JSONArray().apply {
        val removedVisits = base.rows.filter { (key, _) -> key.table == "history" && key !in rows }
            .values.map { it.getString("apartmentId") to it.getLong("visitDate") }.toSet()
        val addedVisits = rows.filter { (key, _) -> key.table == "history" && key !in base.rows }
            .values.map { it.getString("apartmentId") to it.getLong("visitDate") }.toSet()
        for (key in base.rows.keys) {
            if (key !in rows) put(operation(key).put("remove", true))
        }
        for ((key, row) in rows) {
            val old = base.rows[key]
            val fields = JSONObject()
            for (field in row.keys()) {
                if (field != "uuid" && (old == null || row.get(field) != old.opt(field))) {
                    fields.put(field, row.get(field))
                }
            }
            // Статус и дата относятся к одному визиту.
            val visitChanged = key.table == "apartments" && (fields.has("status") || fields.has("lastVisitAt"))
            if (visitChanged) {
                fields.put("status", row.get("status")).put("lastVisitAt", row.get("lastVisitAt"))
            }
            if (old == null || fields.length() != 0) {
                val op = operation(key).put("fields", fields).put("create", old == null)
                if (visitChanged && old != null && !old.isNull("lastVisitAt") &&
                    (key.uuid to old.getLong("lastVisitAt")) in removedVisits &&
                    (key.uuid to row.optLong("lastVisitAt", -1L)) !in addedVisits
                ) {
                    // Отмена одного визита не должна сбрасывать более позднюю отметку другого телефона.
                    op.put("ifVisitAt", old.getLong("lastVisitAt"))
                }
                put(op)
            }
        }
    }

    fun apply(operations: JSONArray): TeamDocument {
        val result = rows.toMutableMap()
        for (operation in operations.objects()) {
            val table = operation.getString("table")
            require(table in tables) { "Неизвестный тип данных команды" }
            val key = RowKey(table, operation.getString("uuid"))
            if (operation.optBoolean("remove")) {
                result.remove(key)
            } else {
                val old = result[key]
                if (old == null && !operation.optBoolean("create")) continue
                val fields = operation.getJSONObject("fields")
                val next = JSONObject(old?.toString() ?: "{}")
                val ignoreVisit = operation.has("ifVisitAt") && (
                    old == null || old.isNull("lastVisitAt") ||
                        old.getLong("lastVisitAt") != operation.getLong("ifVisitAt")
                    )
                for (field in fields.keys()) {
                    if (!ignoreVisit || (field != "status" && field != "lastVisitAt")) next.put(field, fields.get(field))
                }
                next.put("uuid", key.uuid)
                result[key] = next
            }
        }
        return TeamDocument(result).prune()
    }

    private fun prune(): TeamDocument {
        val kept = mutableMapOf<RowKey, JSONObject>()
        for (table in tables) {
            for ((key, row) in rows) {
                if (key.table != table) continue
                var detachedFloor = false
                val valid = refs[table].orEmpty().all { (field, parent) ->
                    if (RowKey(parent, row.optString(field, "")) in kept) true
                    else if (field == "floorId" && !row.isNull("deletedAt")) {
                        detachedFloor = true
                        true
                    } else false
                }
                if (valid) kept[key] = if (detachedFloor) JSONObject(row.toString()).put("floorId", JSONObject.NULL) else row
            }
        }
        return TeamDocument(kept)
    }

    /** Входящие изменения сохраняют ID уже известных строк, чтобы навигация не менялась. */
    fun toBackup(local: JSONObject, sequences: Map<String, Long>): JSONObject {
        val ids = tables.associateWith { table ->
            val existing = local.getJSONArray(table).objects()
            val byUuid = existing.associate { it.getString("uuid") to it.getLong("id") }
            var high = maxOf(sequences[table] ?: 0L, byUuid.values.maxOrNull() ?: 0L)
            rows.keys.filter { it.table == table }.associate { it.uuid to (byUuid[it.uuid] ?: ++high) }
        }
        return JSONObject().put("formatVersion", 1).apply {
            for (table in tables) {
                put(table, JSONArray().apply {
                    for ((key, row) in rows) {
                        if (key.table != table) continue
                        val id = ids.getValue(table).getValue(key.uuid)
                        val converted = JSONObject(row.toString()).put("id", id)
                        for ((field, parent) in refs[table].orEmpty()) {
                            val parentId = if (row.isNull(field)) -id else ids.getValue(parent).getValue(row.getString(field))
                            converted.put(field, parentId)
                        }
                        put(converted)
                    }
                })
            }
        }
    }

    private fun operation(key: RowKey): JSONObject = JSONObject()
        .put("opId", newUuid()).put("table", key.table).put("uuid", key.uuid)
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
