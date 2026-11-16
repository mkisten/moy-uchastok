package ru.obkhod.app.sync

import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.db.ApartmentEntity
import ru.obkhod.app.data.db.EntranceEntity
import ru.obkhod.app.data.db.AreaEntity
import ru.obkhod.app.data.db.FloorEntity
import ru.obkhod.app.data.db.Status
import android.content.Context
import ru.obkhod.app.data.db.VisitHistoryEntity
import ru.obkhod.app.data.remote.TeamApi
import ru.obkhod.app.data.remote.GoogleAuth

/**
 * Синхронизация локальной Room-базы с командной Google-таблицей.
 *
 * Правила слияния:
 * - ключ строки — uuid;
 * - pull: строки, у которых remote.updated_at новее локального, применяются локально;
 *   отсутствующие локально — создаются;
 * - push: локальные строки, изменённые после прошлой выгрузки (updated_at > lastPush),
 *   записываются в таблицу (обновление по номеру строки / добавление в конец);
 * - visit_history — append-only: слияние = объединение множеств по uuid;
 * - удаление квартир — флаг deleted (наше мягкое удаление); удаление подъездов/этажей
 *   не синхронизируется (структуру меняет только владелец в своём приложении,
 *   у остальных она не удаляется).
 */
class SheetSync(
    private val db: AppDatabase,
    private val api: TeamApi,
    private val updatedBy: String,
) {

    data class Result(val pulled: Int, val pushed: Int)

    suspend fun sync(): Result {
        // 1. Читаем удалённые данные
        val structure = api.readSheet("structure!A2:I20000")
        val remoteApartments = api.readSheet("apartments!A2:L50000")
        val remoteHistory = api.readSheet("history!A2:H50000")

        // 2. Локальные данные
        val areaDao = db.areaDao()
        val entranceDao = db.entranceDao()
        val floorDao = db.floorDao()
        val apartmentDao = db.apartmentDao()
        val historyDao = db.visitHistoryDao()
        val localAreas = areaDao.getAll()
        val localEntrances = entranceDao.getAll()
        val localFloors = floorDao.getAll()
        val localApartments = apartmentDao.getAll()
        val localHistory = historyDao.getAll()

        var pulled = 0
        var pushed = 0
        val now = System.currentTimeMillis()

        // Индексы uuid → локальные id
        val areaByUuid = localAreas.associateBy { it.uuid }
        val entranceByUuid = localEntrances.associateBy { it.uuid }
        val floorByUuid = localFloors.associateBy { it.uuid }

        // ---- 3. structure: kind, uuid, parent_uuid, num1, num2, name, deleted, updated_at, updated_by
        val structureRemoteUuids = mutableSetOf<String>()
        for (row in structure) {
            val kind = row.getOrNull(0) ?: continue
            val uuid = row.getOrNull(1) ?: continue
            if (uuid.isBlank()) continue
            structureRemoteUuids.add(uuid)
            val parentUuid = row.getOrNull(2) ?: ""
            val num1 = row.getOrNull(3)?.toIntOrNull() ?: 0
            val num2 = row.getOrNull(4)?.toIntOrNull() ?: 0
            val name = row.getOrNull(5) ?: ""
            val updatedAt = row.getOrNull(7)?.toLongOrNull() ?: 0L
            when (kind) {
                "area" -> {
                    val local = areaByUuid[uuid]
                    // Тип участка кодируется в num1: 1 = MULTI, 2 = PRIVATE
                    val type = if (num1 == 2) "PRIVATE" else "MULTI"
                    if (local == null) {
                        areaDao.insert(AreaEntity(uuid = uuid, name = name, type = type, createdAt = now, updatedAt = updatedAt))
                        pulled++
                    } else if (updatedAt > local.updatedAt && name.isNotBlank() && name != local.name) {
                        areaDao.update(local.copy(name = name, updatedAt = updatedAt))
                        pulled++
                    }
                }
                "entrance" -> {
                    val local = entranceByUuid[uuid]
                    val parent = areaByUuid[parentUuid] ?: local?.let { areaDao.getById(it.areaId) }
                    if (local == null && parent != null) {
                        entranceDao.insert(EntranceEntity(uuid = uuid, areaId = parent.id, entranceNumber = num1, createdAt = now, updatedAt = updatedAt))
                        pulled++
                    } else if (local != null && updatedAt > local.updatedAt && num1 != local.entranceNumber) {
                        entranceDao.update(local.copy(entranceNumber = num1, updatedAt = updatedAt))
                        pulled++
                    }
                }
                "floor" -> {
                    val local = floorByUuid[uuid]
                    val parent = entranceByUuid[parentUuid] ?: local?.let { entranceDao.getById(it.entranceId) }
                    if (local == null && parent != null) {
                        floorDao.insert(FloorEntity(uuid = uuid, entranceId = parent.id, floorNumber = num1, apartmentCount = num2, sortOrder = num1 - 1, updatedAt = updatedAt))
                        pulled++
                    } else if (local != null && updatedAt > local.updatedAt && num1 != local.floorNumber) {
                        floorDao.update(local.copy(floorNumber = num1, updatedAt = updatedAt))
                        pulled++
                    }
                }
            }
        }

        // ---- 4. apartments: uuid, entrance_uuid, floor_uuid, number, label, status, phone, comment, lastVisitAt, deleted, updated_at, updated_by
        val apartmentRemoteUuids = mutableSetOf<String>()
        val apartmentUuidToLocal = localApartments.associateBy { it.uuid }
        val remoteApartmentUuidToLocal = mutableMapOf<String, Long>()
        for (row in remoteApartments) {
            val uuid = row.getOrNull(0) ?: continue
            if (uuid.isBlank()) continue
            apartmentRemoteUuids.add(uuid)
            val entranceUuid = row.getOrNull(1) ?: ""
            val floorUuid = row.getOrNull(2) ?: ""
            val number = row.getOrNull(3)?.toIntOrNull() ?: 0
            val label = row.getOrNull(4)?.takeIf { it.isNotBlank() }
            val status = row.getOrNull(5)?.takeIf { it.isNotBlank() } ?: Status.NONE.name
            val phone = row.getOrNull(6)?.takeIf { it.isNotBlank() }
            val comment = row.getOrNull(7)?.takeIf { it.isNotBlank() }
            val lastVisitAt = row.getOrNull(8)?.toLongOrNull()
            val deleted = (row.getOrNull(9) ?: "") == "1"
            val updatedAt = row.getOrNull(10)?.toLongOrNull() ?: 0L

            val local = apartmentUuidToLocal[uuid]
            if (local == null) {
                // Новая с чужого устройства: ищем родителей по uuid
                val entrance = entranceByUuid[entranceUuid]
                    ?: entranceDao.getAll().firstOrNull { it.uuid == entranceUuid }
                val floor = floorByUuid[floorUuid] ?: floorDao.getAll().firstOrNull { it.uuid == floorUuid }
                if (entrance != null && floor != null) {
                    val t = now
                    val newId = apartmentDao.insert(
                        ApartmentEntity(
                            uuid = uuid, entranceId = entrance.id, floorId = floor.id,
                            apartmentNumber = number, label = label, status = status,
                            phone = phone, comment = comment, lastVisitAt = lastVisitAt,
                            deletedAt = if (deleted) t else null,
                            createdAt = t, updatedAt = updatedAt,
                        )
                    )
                    remoteApartmentUuidToLocal[uuid] = newId
                    pulled++
                }
            } else {
                remoteApartmentUuidToLocal[uuid] = local.id
                // LWW: чужое новее — применяем (кроме числового номера и этажа: их меняет владелец локально)
                if (updatedAt > local.updatedAt) {
                    apartmentDao.update(
                        local.copy(
                            status = status,
                            label = label,
                            phone = phone,
                            comment = comment,
                            lastVisitAt = lastVisitAt ?: local.lastVisitAt,
                            deletedAt = if (deleted) (local.deletedAt ?: now) else local.deletedAt,
                            updatedAt = updatedAt,
                        )
                    )
                    pulled++
                }
            }
        }

        // Свежие локальные справочники (могли пополниться при pull)
        val freshEntrances = entranceDao.getAll()
        val freshFloors = floorDao.getAll()
        val freshEntranceById = freshEntrances.associateBy(EntranceEntity::id)
        val freshFloorById = freshFloors.associateBy(FloorEntity::id)

        // ---- 5. push structure (локальные строки, которых нет удалённо или изменены после lastPush)
        val lastPush = prefsLastPush()
        val pushStructure = mutableListOf<List<String>>()
        for (a in areaDao.getAll()) {
            if (a.uuid.isBlank()) { areaDao.update(a.copy(uuid = newLocalUuid())) }
            else if (a.updatedAt > lastPush || a.uuid !in structureRemoteUuids) {
                val typeCode = if (a.type == "PRIVATE") "2" else "1"
                pushStructure.add(listOf("area", a.uuid, "", typeCode, "", a.name, "0", a.updatedAt.toString(), updatedBy))
                pushed++
            }
        }
        for (e in freshEntrances) {
            if (e.uuid.isBlank()) { entranceDao.update(e.copy(uuid = newLocalUuid())) }
            else if (e.updatedAt > lastPush || e.uuid !in structureRemoteUuids) {
                val area = freshAreasById()[e.areaId]
                pushStructure.add(listOf("entrance", e.uuid, area?.uuid ?: "", e.entranceNumber.toString(), "", "", "0", e.updatedAt.toString(), updatedBy))
                pushed++
            }
        }
        for (f in freshFloors) {
            if (f.uuid.isBlank()) { floorDao.update(f.copy(uuid = newLocalUuid())) }
            else if (f.updatedAt > lastPush || f.uuid !in structureRemoteUuids) {
                val entrance = freshEntranceById[f.entranceId]
                pushStructure.add(listOf("floor", f.uuid, entrance?.uuid ?: "", f.floorNumber.toString(), f.apartmentCount.toString(), "", "0", f.updatedAt.toString(), updatedBy))
                pushed++
            }
        }
        if (pushStructure.isNotEmpty()) api.appendRows("structure", pushStructure)

        // ---- 6. push apartments (новые или изменённые после lastPush)
        val pushApartments = mutableListOf<List<String>>()
        for (a in apartmentDao.getAll()) {
            var uuid = a.uuid
            if (uuid.isBlank()) {
                uuid = newLocalUuid()
                apartmentDao.update(a.copy(uuid = uuid))
            } else if (a.updatedAt > lastPush || uuid !in apartmentRemoteUuids) {
                val entrance = freshEntranceById[a.entranceId]
                val floor = freshFloorById[a.floorId]
                pushApartments.add(
                    listOf(
                        uuid,
                        entrance?.uuid ?: "",
                        floor?.uuid ?: "",
                        a.apartmentNumber.toString(),
                        a.label ?: "",
                        a.status,
                        a.phone ?: "",
                        a.comment ?: "",
                        a.lastVisitAt?.toString() ?: "",
                        if (a.deletedAt != null) "1" else "0",
                        a.updatedAt.toString(),
                        updatedBy,
                    )
                )
                pushed++
            }
        }
        if (pushApartments.isNotEmpty()) api.appendRows("apartments", pushApartments)

        // ---- 7. history: объединение множеств по uuid (append-only)
        val remoteHistoryUuids = remoteHistory.mapNotNull { r -> r.getOrNull(0)?.takeIf { it.isNotBlank() } }.toSet()
        val pushHistory = mutableListOf<List<String>>()
        val allAptsNow = apartmentDao.getAll()
        val apartmentUuidById = allAptsNow.associateBy(ApartmentEntity::uuid)
        val apartmentIdByUuid = allAptsNow.associateBy(ApartmentEntity::id)
        val localHistoryUuids = localHistory.associateBy(VisitHistoryEntity::uuid)
        for (h in localHistory) {
            var uuid = h.uuid
            if (uuid.isBlank()) {
                uuid = newLocalUuid()
                historyDao.insert(h.copy(uuid = uuid))
            } else if (uuid !in remoteHistoryUuids) {
                val aptUuid = allAptsNow.firstOrNull { it.id == h.apartmentId }?.uuid ?: ""
                pushHistory.add(
                    listOf(
                        uuid, aptUuid, h.visitDate.toString(),
                        h.previousStatus ?: "", h.newStatus ?: "",
                        h.comment ?: "", h.eventType, updatedBy,
                    )
                )
                pushed++
            }
        }
        if (pushHistory.isNotEmpty()) api.appendRows("history", pushHistory)

        for (row in remoteHistory) {
            val uuid = row.getOrNull(0) ?: continue
            if (uuid.isBlank() || localHistoryUuids.containsKey(uuid)) continue
            val aptUuid = row.getOrNull(1) ?: ""
            val aptLocalId = apartmentUuidById[aptUuid]?.id ?: continue // история без квартиры локально — пропускаем
            historyDao.insert(
                VisitHistoryEntity(
                    uuid = uuid,
                    apartmentId = aptLocalId,
                    visitDate = row.getOrNull(2)?.toLongOrNull() ?: 0L,
                    previousStatus = row.getOrNull(3)?.takeIf { it.isNotBlank() },
                    newStatus = row.getOrNull(4)?.takeIf { it.isNotBlank() },
                    comment = row.getOrNull(5)?.takeIf { it.isNotBlank() },
                    eventType = row.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "VISIT",
                )
            )
            pulled++
        }

        // 8. Отмечаем время выгрузки
        prefsSetLastPush(now)
        return Result(pulled, pushed)
    }

    private fun newLocalUuid(): String = java.util.UUID.randomUUID().toString()

    private var areaIdCache: Map<Long, AreaEntity>? = null
    private suspend fun freshAreasById(): Map<Long, AreaEntity> {
        if (areaIdCache == null) areaIdCache = db.areaDao().getAll().associateBy(AreaEntity::id)
        return areaIdCache!!
    }

    // Время последней выгрузки — в prefs приложения
    private val prefs get() = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
    private lateinit var context: Context

    companion object {
        fun create(context: Context, db: AppDatabase, api: TeamApi): SheetSync {
            val s = SheetSync(db, api, GoogleAuth.email.value ?: "unknown")
            s.context = context.applicationContext
            return s
        }

        fun prefsLastPushStatic(context: Context): Long =
            context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE).getLong("last_push", 0L)

        fun prefsSetLastPushStatic(context: Context, ts: Long) {
            context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE).edit().putLong("last_push", ts).apply()
        }
    }

    private fun prefsLastPush(): Long = prefsLastPushStatic(context)
    private fun prefsSetLastPush(ts: Long) = prefsSetLastPushStatic(context, ts)
}
