package ru.obkhod.app.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.db.ApartmentEntity
import ru.obkhod.app.data.db.AreaEntity
import ru.obkhod.app.data.db.EntranceEntity
import ru.obkhod.app.data.db.FloorEntity
import ru.obkhod.app.data.db.VisitHistoryEntity
import ru.obkhod.app.data.repo.withTransactionCompat
import ru.obkhod.app.data.repo.newUuid
import ru.obkhod.app.data.db.AreaType
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.db.EventType
import ru.obkhod.app.BuildConfig
import ru.obkhod.app.sync.TeamConnectionStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        internal val cloudSyncAndRestore = Mutex()
    }

    class PreparedBackup internal constructor(
        val createdAt: Long?,
        internal val areas: List<AreaEntity>,
        internal val entrances: List<EntranceEntity>,
        internal val floors: List<FloorEntity>,
        internal val apartments: List<ApartmentEntity>,
        internal val history: List<VisitHistoryEntity>,
    ) {
        val areaCount get() = areas.size
        val entranceCount get() = entrances.size
        val activeCount get() = apartments.count { it.deletedAt == null }
        val deletedCount get() = apartments.size - activeCount
        val historyCount get() = history.size
    }

    private val safetyCopy get() = File(context.filesDir, "before_restore.zip")

    fun hasSafetyCopy(): Boolean = safetyCopy.isFile

    /** Создание резервной копии: все данные приложения → backup.json → ZIP (п. 18). */
    suspend fun createBackup(out: java.io.OutputStream) {
        writeZip(out, buildSnapshot())
    }

    private fun writeZip(out: java.io.OutputStream, json: JSONObject) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_NAME))
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
        root.put("appVersion", BuildConfig.VERSION_NAME)

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
                    put("updatedAt", e.updatedAt)
                })
            }
        })
        root.put("floors", JSONArray().apply {
            floors.forEach { f ->
                put(JSONObject().apply {
                    put("id", f.id); put("uuid", f.uuid); put("entranceId", f.entranceId)
                    put("floorNumber", f.floorNumber); put("apartmentCount", f.apartmentCount)
                    put("sortOrder", f.sortOrder); put("updatedAt", f.updatedAt)
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

    fun prepareZip(uri: Uri): PreparedBackup {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть файл резервной копии")
        return prepareJson(readZip(input))
    }

    fun prepareSafetyCopy(): PreparedBackup = prepareJson(readZip(safetyCopy.inputStream()))

    private fun readZip(input: InputStream): JSONObject {
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == ENTRY_NAME) {
                        return JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException("Не удалось прочитать резервную копию ZIP", e)
        }
        throw IOException("В ZIP нет файла резервной копии")
    }

    /** Снапшот всей базы в формате бэкапа (для облачной синхронизации). */
    suspend fun buildSnapshot(): JSONObject = db.withTransactionCompat { buildJson() }

    /** Проверяет и фиксирует содержимое копии, которую пользователь увидит перед заменой. */
    fun prepareJson(json: JSONObject): PreparedBackup {

        // Проверка версии формата (п. 18)
        val version = json.optInt("formatVersion", -1)
        if (version != BACKUP_FORMAT_VERSION) {
            throw IOException("Неподдерживаемая версия резервной копии: $version")
        }
        val areas = json.optJSONArray("areas")
        val entrances = json.optJSONArray("entrances")
        val floors = json.optJSONArray("floors")
        val apartments = json.optJSONArray("apartments")
        val history = json.optJSONArray("history")
        if (areas == null || entrances == null || floors == null || apartments == null || history == null) {
            throw IOException("Резервная копия неполна")
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
                        uuid = o.backupUuid(),
                    )
                )
            }
            for (i in 0 until entrances.length()) {
                val o = entrances.getJSONObject(i)
                parsedEntrances.add(
                    EntranceEntity(
                        id = o.getLong("id"), areaId = o.getLong("areaId"),
                        entranceNumber = o.getInt("entranceNumber"), createdAt = o.getLong("createdAt"),
                        uuid = o.backupUuid(), updatedAt = o.optLong("updatedAt", o.getLong("createdAt")),
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
                        uuid = o.backupUuid(), updatedAt = o.optLong("updatedAt", 0),
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
                        uuid = o.backupUuid(),
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
                        uuid = o.backupUuid(),
                    )
                )
            }
        } catch (e: Exception) {
            throw IOException("В копии есть записи с отсутствующими или неверными полями", e)
        }

        // Проверка целостности внешних ключей
        val entranceIds = parsedEntrances.map { it.id }.toSet()
        val floorEntrances = parsedFloors.associate { it.id to it.entranceId }
        val apartmentIds = parsedApartments.map { it.id }.toSet()
        val areaIds = parsedAreas.map { it.id }.toSet()
        validateIds(parsedAreas, AreaEntity::id, AreaEntity::uuid)
        validateIds(parsedEntrances, EntranceEntity::id, EntranceEntity::uuid)
        validateIds(parsedFloors, FloorEntity::id, FloorEntity::uuid)
        validateIds(parsedApartments, ApartmentEntity::id, ApartmentEntity::uuid)
        validateIds(parsedHistory, VisitHistoryEntity::id, VisitHistoryEntity::uuid)
        if (parsedEntrances.any { it.areaId !in areaIds }) throw IOException("Нарушена целостность: подъезд без участка")
        if (parsedFloors.any { it.entranceId !in entranceIds }) throw IOException("Нарушена целостность: этаж без подъезда")
        if (parsedApartments.any { apartment ->
                val floorEntranceId = floorEntrances[apartment.floorId]
                apartment.entranceId !in entranceIds ||
                    (floorEntranceId == null && apartment.deletedAt == null) ||
                    (floorEntranceId != null && floorEntranceId != apartment.entranceId)
            }
        ) {
            throw IOException("Нарушена целостность: квартира с некорректными ссылками")
        }
        if (parsedHistory.any { it.apartmentId !in apartmentIds }) {
            throw IOException("Нарушена целостность: запись истории без квартиры")
        }
        val statuses = Status.entries.map { it.name }.toSet()
        val types = AreaType.entries.map { it.name }.toSet()
        val events = EventType.entries.map { it.name }.toSet()
        if (parsedAreas.any { it.type !in types } ||
            parsedApartments.any { it.status !in statuses } ||
            parsedHistory.any {
                it.eventType !in events ||
                    (it.previousStatus != null && it.previousStatus !in statuses) ||
                    (it.newStatus != null && it.newStatus !in statuses)
            }
        ) {
            throw IOException("Копия содержит неизвестные типы участков, статусы или события")
        }
        return PreparedBackup(json.optLongOrNull("createdAt"), parsedAreas, parsedEntrances, parsedFloors, parsedApartments, parsedHistory)
    }

    /** Сохраняет текущую базу и заменяет её только после успешной записи ZIP. */
    suspend fun restore(backup: PreparedBackup) = cloudSyncAndRestore.withLock {
        check(!TeamConnectionStore.exists(context)) { "Перед восстановлением отключите синхронизацию команды" }
        db.withTransactionCompat {
            saveSafetyCopy(buildJson())
            replaceData(backup)
        }
    }

    /** Вызывается внутри транзакции вместе с обновлением контрольной точки. */
    internal suspend fun replaceData(backup: PreparedBackup) {
        db.visitHistoryDao().deleteEverything()
        db.apartmentDao().deleteEverything()
        db.floorDao().deleteEverything()
        db.entranceDao().deleteEverything()
        db.areaDao().deleteEverything()
        db.areaDao().insertAll(backup.areas)
        db.entranceDao().insertAll(backup.entrances)
        db.floorDao().insertAll(backup.floors)
        db.apartmentDao().insertAll(backup.apartments)
        db.visitHistoryDao().insertAll(backup.history)
    }

    private fun saveSafetyCopy(json: JSONObject) {
        val pending = File(context.filesDir, "before_restore.pending")
        try {
            writeZip(pending.outputStream(), json)
            FileOutputStream(pending, true).use { it.fd.sync() }
            Files.move(pending.toPath(), safetyCopy.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            throw IOException("Не удалось сохранить страховочную копию", e)
        } finally {
            pending.delete()
        }
    }

    private fun <T> validateIds(rows: List<T>, id: (T) -> Long, uuid: (T) -> String) {
        val ids = rows.map(id)
        if (ids.any { it <= 0 } || ids.toSet().size != rows.size || rows.map(uuid).toSet().size != rows.size) {
            throw IOException("В копии есть повторяющиеся или неверные идентификаторы")
        }
    }

    /** Автоматический префикс имени файла с датой (п. 18). */
    fun suggestedFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
        return "obkhod_backup_$date.zip"
    }
}

private fun JSONObject.backupUuid(): String = optStringOrNull("uuid").orEmpty().ifBlank { newUuid() }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else getString(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else getLong(key)
