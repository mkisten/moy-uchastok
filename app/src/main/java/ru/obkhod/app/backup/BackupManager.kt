package ru.obkhod.app.backup

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.db.ApartmentEntity
import ru.obkhod.app.data.db.AreaEntity
import ru.obkhod.app.data.db.EntranceEntity
import ru.obkhod.app.data.db.FloorEntity
import ru.obkhod.app.data.db.VisitHistoryEntity
import ru.obkhod.app.data.repo.withTransactionCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Резервное копирование (п. 18 ТЗ): ZIP с backup.json внутри.
 * Формат не зависит от устройства; восстановление — одной транзакцией с валидацией.
 */
class BackupManager(private val context: Context, private val db: AppDatabase) {

    companion object {
        const val BACKUP_FORMAT_VERSION = 1
        const val ENTRY_NAME = "backup.json"
    }

    sealed class RestoreResult {
        data object Success : RestoreResult()
        data class Error(val message: String) : RestoreResult()
    }

    /** Создание резервной копии: все данные приложения → backup.json → ZIP (п. 18). */
    suspend fun createBackup(out: java.io.OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_NAME))
            val json = buildJson()
            zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
            zip.flush()
        }
    }

    private suspend fun buildJson(): JSONObject {
        val areaDao = db.areaDao()
        val entranceDao = db.entranceDao()
        val floorDao = db.floorDao()
        val apartmentDao = db.apartmentDao()
        val historyDao = db.visitHistoryDao()

        val areas = areaDao.getAll()
        val entrances = entranceDao.getAll()
        val floors = floorDao.getAll()
        val apartments = apartmentDao.getAll() // включая логически удалённые (п. 18)
        val history = historyDao.getAll()

        val root = JSONObject()
        root.put("formatVersion", BACKUP_FORMAT_VERSION)
        root.put("createdAt", System.currentTimeMillis())
        root.put("appVersion", "1.0.0")

        root.put("areas", JSONArray().apply {
            areas.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id); put("uuid", a.uuid); put("name", a.name)
                    put("type", a.type)
                    put("createdAt", a.createdAt); put("updatedAt", a.updatedAt)
                })
            }
        })
        root.put("entrances", JSONArray().apply {
            entrances.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id); put("uuid", e.uuid); put("areaId", e.areaId)
                    put("entranceNumber", e.entranceNumber); put("createdAt", e.createdAt)
                })
            }
        })
        root.put("floors", JSONArray().apply {
            floors.forEach { f ->
                put(JSONObject().apply {
                    put("id", f.id); put("uuid", f.uuid); put("entranceId", f.entranceId)
                    put("floorNumber", f.floorNumber); put("apartmentCount", f.apartmentCount)
                    put("sortOrder", f.sortOrder)
                })
            }
        })
        root.put("apartments", JSONArray().apply {
            apartments.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id); put("uuid", a.uuid); put("entranceId", a.entranceId); put("floorId", a.floorId)
                    put("apartmentNumber", a.apartmentNumber); put("status", a.status)
                    put("label", a.label ?: JSONObject.NULL)
                    put("phone", a.phone ?: JSONObject.NULL); put("comment", a.comment ?: JSONObject.NULL)
                    put("lastVisitAt", a.lastVisitAt ?: JSONObject.NULL)
                    put("createdAt", a.createdAt); put("updatedAt", a.updatedAt)
                    put("deletedAt", a.deletedAt ?: JSONObject.NULL)
                    put("hasChild", a.hasChild)
                })
            }
        })
        root.put("history", JSONArray().apply {
            history.forEach { h ->
                put(JSONObject().apply {
                    put("id", h.id); put("uuid", h.uuid); put("apartmentId", h.apartmentId); put("visitDate", h.visitDate)
                    put("previousStatus", h.previousStatus ?: JSONObject.NULL)
                    put("newStatus", h.newStatus ?: JSONObject.NULL)
                    put("comment", h.comment ?: JSONObject.NULL)
                    put("eventType", h.eventType)
                })
            }
        })
        return root
    }

    /**
     * Восстановление из ZIP (п. 18).
     * Валидация → временная копия текущей базы → замена всех данных одной транзакцией.
     * При любой ошибке существующие данные не изменяются.
     */
    suspend fun restoreFrom(uri: Uri): RestoreResult {
        val json: JSONObject = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    var found: ByteArray? = null
                    while (entry != null) {
                        if (entry.name == ENTRY_NAME) {
                            found = zip.readBytes()
                            break
                        }
                        entry = zip.nextEntry
                    }
                    found?.let { String(it, Charsets.UTF_8) }
                        ?.let { JSONObject(it) }
                }
            } ?: return RestoreResult.Error("Не удалось прочитать файл резервной копии")
        } catch (e: Exception) {
            return RestoreResult.Error("Файл повреждён или не является резервной копией")
        }
        return restoreFromJson(json)
    }

    /** Снапшот всей базы в формате бэкапа (для облачной синхронизации). */
    suspend fun buildSnapshot(): JSONObject = buildJson()

    /** Восстановление из JSON-снапшота (тот же формат, что и ZIP-бэкап). */
    suspend fun restoreFromJson(json: JSONObject): RestoreResult {

        // Проверка версии формата (п. 18)
        val version = json.optInt("formatVersion", -1)
        if (version != BACKUP_FORMAT_VERSION) {
            return RestoreResult.Error("Неподдерживаемая версия резервной копии: $version")
        }
        val areas = json.optJSONArray("areas")
        val entrances = json.optJSONArray("entrances")
        val floors = json.optJSONArray("floors")
        val apartments = json.optJSONArray("apartments")
        val history = json.optJSONArray("history")
        if (areas == null || entrances == null || floors == null || apartments == null || history == null) {
            return RestoreResult.Error("Резервная копия неполна")
        }

        // Разбор с проверкой целостности ссылок до изменения базы
        val parsedAreas = mutableListOf<AreaEntity>()
        val parsedEntrances = mutableListOf<EntranceEntity>()
        val parsedFloors = mutableListOf<FloorEntity>()
        val parsedApartments = mutableListOf<ApartmentEntity>()
        val parsedHistory = mutableListOf<VisitHistoryEntity>()
        try {
            for (i in 0 until areas.length()) {
                val o = areas.getJSONObject(i)
                parsedAreas.add(
                    AreaEntity(
                        id = o.getLong("id"), name = o.getString("name"),
                        type = o.optString("type", ru.obkhod.app.data.db.AreaType.MULTI.name),
                        createdAt = o.getLong("createdAt"), updatedAt = o.getLong("updatedAt"),
                    )
                )
            }
            for (i in 0 until entrances.length()) {
                val o = entrances.getJSONObject(i)
                parsedEntrances.add(
                    EntranceEntity(
                        id = o.getLong("id"), areaId = o.getLong("areaId"),
                        entranceNumber = o.getInt("entranceNumber"), createdAt = o.getLong("createdAt"),
                    )
                )
            }
            for (i in 0 until floors.length()) {
                val o = floors.getJSONObject(i)
                parsedFloors.add(
                    FloorEntity(
                        id = o.getLong("id"), entranceId = o.getLong("entranceId"),
                        floorNumber = o.getInt("floorNumber"), apartmentCount = o.getInt("apartmentCount"),
                        sortOrder = o.optInt("sortOrder", 0),
                    )
                )
            }
            for (i in 0 until apartments.length()) {
                val o = apartments.getJSONObject(i)
                parsedApartments.add(
                    ApartmentEntity(
                        id = o.getLong("id"), entranceId = o.getLong("entranceId"), floorId = o.getLong("floorId"),
                        apartmentNumber = o.getInt("apartmentNumber"), status = o.getString("status"),
                        label = o.optStringOrNull("label"),
                        phone = o.optStringOrNull("phone"), comment = o.optStringOrNull("comment"),
                        lastVisitAt = o.optLongOrNull("lastVisitAt"),
                        createdAt = o.getLong("createdAt"), updatedAt = o.getLong("updatedAt"),
                        deletedAt = o.optLongOrNull("deletedAt"),
                        hasChild = o.optBoolean("hasChild", false),
                    )
                )
            }
            for (i in 0 until history.length()) {
                val o = history.getJSONObject(i)
                parsedHistory.add(
                    VisitHistoryEntity(
                        id = o.getLong("id"), apartmentId = o.getLong("apartmentId"),
                        visitDate = o.getLong("visitDate"),
                        previousStatus = o.optStringOrNull("previousStatus"),
                        newStatus = o.optStringOrNull("newStatus"),
                        comment = o.optStringOrNull("comment"),
                        eventType = o.getString("eventType"),
                    )
                )
            }
        } catch (e: Exception) {
            return RestoreResult.Error("Ошибка чтения данных резервной копии: ${e.message}")
        }

        // Проверка целостности внешних ключей
        val entranceIds = parsedEntrances.map { it.id }.toSet()
        val floorIds = parsedFloors.map { it.id }.toSet()
        val apartmentIds = parsedApartments.map { it.id }.toSet()
        val areaIds = parsedAreas.map { it.id }.toSet()
        if (parsedEntrances.any { it.areaId !in areaIds }) return RestoreResult.Error("Нарушена целостность: подъезд без участка")
        if (parsedFloors.any { it.entranceId !in entranceIds }) return RestoreResult.Error("Нарушена целостность: этаж без подъезда")
        if (parsedApartments.any { it.entranceId !in entranceIds || it.floorId !in floorIds }) {
            return RestoreResult.Error("Нарушена целостность: квартира с некорректными ссылками")
        }
        if (parsedHistory.any { it.apartmentId !in apartmentIds }) {
            return RestoreResult.Error("Нарушена целостность: запись истории без квартиры")
        }

        // Замена всех данных в одной транзакции (п. 18)
        db.withTransactionCompat {
            db.visitHistoryDao().deleteEverything()
            db.apartmentDao().deleteEverything()
            db.floorDao().deleteEverything()
            db.entranceDao().deleteEverything()
            db.areaDao().deleteEverything()

            db.areaDao().insertAll(parsedAreas)
            db.entranceDao().insertAll(parsedEntrances)
            db.floorDao().insertAll(parsedFloors)
            db.apartmentDao().insertAll(parsedApartments)
            db.visitHistoryDao().insertAll(parsedHistory)
        }
        return RestoreResult.Success
    }

    /** Автоматический префикс имени файла с датой (п. 18). */
    fun suggestedFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
        return "obkhod_backup_$date.zip"
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else getString(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else getLong(key)
