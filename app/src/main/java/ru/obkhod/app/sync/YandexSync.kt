package ru.obkhod.app.sync

import ru.obkhod.app.backup.BackupManager
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.db.ApartmentEntity
import ru.obkhod.app.data.db.AreaEntity
import ru.obkhod.app.data.db.EntranceEntity
import ru.obkhod.app.data.db.FloorEntity
import ru.obkhod.app.data.db.VisitHistoryEntity
import ru.obkhod.app.data.repo.withTransactionCompat
import ru.obkhod.app.data.remote.WebDavClient
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Синхронизация через общую папку Яндекс Диска (WebDAV).
 * Файл /Мой участок/data.json — снапшот всей базы (формат = ZIP-бэкап, внутри JSON).
 * Слияние: ключ uuid; история объединяется; статусы/заметки — «последний по времени выигрывает»;
 * удаление квартир — флаг deletedAt. Структуру правит только владелец (чужие structural
 * изменения применяются, только если они новее локальных).
 */
class YandexSync(
    private val context: Context,
    private val db: AppDatabase,
    private val webdav: WebDavClient,
) {

    companion object {
        const val FOLDER = "/Мой участок"
        const val DATA_FILE = "/Мой участок/data.json"
    }

    data class Result(val pulled: Int, val pushed: Int)

    suspend fun sync(): Result {
        val manager = BackupManager(context, db)

        // 1. Папка и удалённый снапшот
        if (!webdav.exists(FOLDER)) webdav.mkcol(FOLDER)
        val remoteJson: JSONObject? = webdav.get(DATA_FILE)?.let {
            try { JSONObject(it) } catch (e: Exception) { null } // повреждённый файл игнорируем
        }

        var pulled = 0

        if (remoteJson != null) {
            // 2. Слияние удалённого снапшота в локальную базу по uuid
            val areaDao = db.areaDao()
            val entranceDao = db.entranceDao()
            val floorDao = db.floorDao()
            val apartmentDao = db.apartmentDao()
            val historyDao = db.visitHistoryDao()
            val localAreas = areaDao.getAll().associateBy(AreaEntity::uuid)
            val localEntrances = entranceDao.getAll().associateBy(EntranceEntity::uuid)
            val localFloors = floorDao.getAll().associateBy(FloorEntity::uuid)
            val localApartments = apartmentDao.getAll().associateBy(ApartmentEntity::uuid)
            val localHistory = historyDao.getAll().associateBy(VisitHistoryEntity::uuid)
            val now = System.currentTimeMillis()

            val remoteAreas = remoteJson.optJSONArray("areas") ?: JSONArray()
            val remoteEntrances = remoteJson.optJSONArray("entrances") ?: JSONArray()
            val remoteFloors = remoteJson.optJSONArray("floors") ?: JSONArray()
            val remoteApartments = remoteJson.optJSONArray("apartments") ?: JSONArray()
            val remoteHistory = remoteJson.optJSONArray("history") ?: JSONArray()

            // structure: area → entrance → floor
            for (i in 0 until remoteAreas.length()) {
                val o = remoteAreas.getJSONObject(i)
                val uuid = o.optString("uuid", "")
                if (uuid.isBlank()) continue
                val updatedAt = o.optLong("updatedAt", 0)
                val local = localAreas[uuid]
                if (local == null) {
                    areaDao.insert(AreaEntity(uuid = uuid, name = o.optString("name"), type = o.optString("type", "MULTI"), createdAt = now, updatedAt = updatedAt))
                    pulled++
                } else if (updatedAt > local.updatedAt && o.optString("name") != local.name) {
                    areaDao.update(local.copy(name = o.optString("name"), updatedAt = updatedAt))
                    pulled++
                }
            }
            val freshAreas = areaDao.getAll()
            for (i in 0 until remoteEntrances.length()) {
                val o = remoteEntrances.getJSONObject(i)
                val uuid = o.optString("uuid", "")
                if (uuid.isBlank()) continue
                val updatedAt = o.optLong("updatedAt", 0)
                val parent = freshAreas.firstOrNull { it.uuid == o.optString("areaId") }
                val local = localEntrances[uuid]
                if (local == null && parent != null) {
                    entranceDao.insert(EntranceEntity(uuid = uuid, areaId = parent.id, entranceNumber = o.optInt("entranceNumber"), createdAt = now, updatedAt = updatedAt))
                    pulled++
                } else if (local != null && updatedAt > local.updatedAt && o.optInt("entranceNumber") != local.entranceNumber) {
                    entranceDao.update(local.copy(entranceNumber = o.optInt("entranceNumber"), updatedAt = updatedAt))
                    pulled++
                }
            }
            val freshEntrances = entranceDao.getAll()
            for (i in 0 until remoteFloors.length()) {
                val o = remoteFloors.getJSONObject(i)
                val uuid = o.optString("uuid", "")
                if (uuid.isBlank()) continue
                val updatedAt = o.optLong("updatedAt", 0)
                val parent = freshEntrances.firstOrNull { it.uuid == o.optString("entranceId") }
                val local = localFloors[uuid]
                if (local == null && parent != null) {
                    floorDao.insert(FloorEntity(uuid = uuid, entranceId = parent.id, floorNumber = o.optInt("floorNumber"), apartmentCount = o.optInt("apartmentCount"), sortOrder = o.optInt("floorNumber") - 1, updatedAt = updatedAt))
                    pulled++
                } else if (local != null && updatedAt > local.updatedAt && o.optInt("floorNumber") != local.floorNumber) {
                    floorDao.update(local.copy(floorNumber = o.optInt("floorNumber"), updatedAt = updatedAt))
                    pulled++
                }
            }

            // apartments: статус/заметка/удаление — LWW; номер и этаж — только если квартира новая
            val freshEntrances2 = entranceDao.getAll()
            val freshFloors2 = floorDao.getAll()
            for (i in 0 until remoteApartments.length()) {
                val o = remoteApartments.getJSONObject(i)
                val uuid = o.optString("uuid", "")
                if (uuid.isBlank()) continue
                val updatedAt = o.optLong("updatedAt", 0)
                val local = localApartments[uuid]
                if (local == null) {
                    val entrance = freshEntrances2.firstOrNull { it.uuid == o.optString("entranceId") }
                    val floor = freshFloors2.firstOrNull { it.uuid == o.optString("floorId") }
                    if (entrance != null && floor != null) {
                        val t = now
                        apartmentDao.insert(
                            ApartmentEntity(
                                uuid = uuid, entranceId = entrance.id, floorId = floor.id,
                                apartmentNumber = o.optInt("apartmentNumber"),
                                label = optStringOrNull(o, "label"),
                                status = o.optString("status", "NONE"),
                                phone = optStringOrNull(o, "phone"),
                                comment = optStringOrNull(o, "comment"),
                                lastVisitAt = optLongOrNull(o, "lastVisitAt"),
                                deletedAt = optLongOrNull(o, "deletedAt"),
                                createdAt = t, updatedAt = updatedAt,
                            )
                        )
                        pulled++
                    }
                } else if (updatedAt > local.updatedAt) {
                    apartmentDao.update(
                        local.copy(
                            status = o.optString("status", local.status),
                            label = optStringOrNull(o, "label"),
                            phone = optStringOrNull(o, "phone"),
                            comment = optStringOrNull(o, "comment"),
                            lastVisitAt = optLongOrNull(o, "lastVisitAt") ?: local.lastVisitAt,
                            deletedAt = optLongOrNull(o, "deletedAt") ?: local.deletedAt,
                            updatedAt = updatedAt,
                        )
                    )
                    pulled++
                }
            }

            // history: объединение множеств по uuid
            val allApartments = apartmentDao.getAll()
            val aptUuidById = allApartments.associateBy(ApartmentEntity::id)
            val aptIdByUuid = allApartments.associateBy(ApartmentEntity::uuid)
            for (i in 0 until remoteHistory.length()) {
                val o = remoteHistory.getJSONObject(i)
                val uuid = o.optString("uuid", "")
                if (uuid.isBlank() || localHistory.containsKey(uuid)) continue
                val apt = aptIdByUuid[o.optString("apartmentId")] ?: continue
                historyDao.insert(
                    VisitHistoryEntity(
                        uuid = uuid,
                        apartmentId = apt.id,
                        visitDate = o.optLong("visitDate", 0),
                        previousStatus = optStringOrNull(o, "previousStatus"),
                        newStatus = optStringOrNull(o, "newStatus"),
                        comment = optStringOrNull(o, "comment"),
                        eventType = o.optString("eventType", "VISIT"),
                    )
                )
                pulled++
            }
        }

        // 3. Выгружаем объединённый снапшот (локальная база теперь самая полная)
        val snapshot = manager.buildSnapshot()
        webdav.put(DATA_FILE, snapshot.toString(0))

        return Result(pulled, 1)
    }

    /** Полная замена локальных данных облачным снапшотом (ручное восстановление). */
    suspend fun restoreFromCloud() {
        val json = webdav.get(DATA_FILE)?.let {
            try { JSONObject(it) } catch (e: Exception) { null }
        } ?: throw IllegalStateException("В облаке нет файла данных")
        BackupManager(context, db).restoreFromJson(json)
    }

    private fun optStringOrNull(o: JSONObject, key: String): String? =
        if (o.isNull(key) || !o.has(key)) null else o.getString(key)

    private fun optLongOrNull(o: JSONObject, key: String): Long? =
        if (o.isNull(key) || !o.has(key)) null else o.getLong(key)
}
