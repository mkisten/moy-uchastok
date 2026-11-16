package ru.obkhod.app.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import androidx.room.withTransaction
import ru.obkhod.app.data.db.ApartmentDao
import ru.obkhod.app.data.db.ApartmentEntity
import ru.obkhod.app.data.db.AreaDao
import ru.obkhod.app.data.db.AreaEntity
import ru.obkhod.app.data.db.EntranceDao
import ru.obkhod.app.data.db.EntranceEntity
import ru.obkhod.app.data.db.FloorDao
import ru.obkhod.app.data.db.FloorEntity
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.db.VisitHistoryDao
import ru.obkhod.app.data.db.VisitHistoryEntity
import ru.obkhod.app.data.db.AppDatabase

class AreaRepository(private val db: AppDatabase) {
    private val areaDao: AreaDao = db.areaDao()
    private val entranceDao: EntranceDao = db.entranceDao()
    private val floorDao: FloorDao = db.floorDao()
    private val apartmentDao: ApartmentDao = db.apartmentDao()
    private val historyDao: VisitHistoryDao = db.visitHistoryDao()

    fun observeAreas(): Flow<List<AreaEntity>> = areaDao.observeAll()

    fun observeArea(id: Long): Flow<AreaEntity?> = areaDao.observeById(id)

    suspend fun getById(id: Long): AreaEntity? = areaDao.getById(id)

    /** Участок, к которому относится подъезд (для заголовка экрана подъезда). */
    suspend fun areaOfEntrance(entranceId: Long): AreaEntity? {
        val entrance = entranceDao.getById(entranceId) ?: return null
        return areaDao.getById(entrance.areaId)
    }

    fun observeEntrances(areaId: Long): Flow<List<EntranceEntity>> = entranceDao.observeByArea(areaId)

    /** Статистика по участку: статусы + факт наличия посещений из истории (п. 15). */
    fun observeAreaStatistics(areaId: Long): Flow<Statistics> =
        combine(
            apartmentDao.observeByArea(areaId),
            historyDao.apartmentIdsWithVisitsFlow(),
        ) { apartments, visitedIds ->
            Statistics.of(
                apartments.map { Status.valueOf(it.status) },
                visitedIds.toSet(),
                apartments.map { it.id },
            )
        }

    /**
     * Создание участка со всеми подъездами, этажами и квартирами (пп. 4.1–4.3).
     * Для частного сектора (type = PRIVATE): создаётся участок-улица с одним служебным
     * подъездом и пустым «этажом», дома добавляются внутри участка в свободном порядке.
     */
    suspend fun createArea(name: String, drafts: List<EntranceDraft>, type: ru.obkhod.app.data.db.AreaType = ru.obkhod.app.data.db.AreaType.MULTI): CreateAreaResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CreateAreaResult.Error("Название обязательно для заполнения")
        if (type == ru.obkhod.app.data.db.AreaType.PRIVATE) {
            var createdPrivateId = 0L
            db.withTransactionCompat {
                val t = System.currentTimeMillis()
                val areaId = areaDao.insert(AreaEntity(uuid = newUuid(), name = trimmed, type = type.name, createdAt = t, updatedAt = t))
                createdPrivateId = areaId
                val entranceId = entranceDao.insert(EntranceEntity(uuid = newUuid(), areaId = areaId, entranceNumber = 1, createdAt = t, updatedAt = t))
                floorDao.insert(FloorEntity(uuid = newUuid(), entranceId = entranceId, floorNumber = 1, apartmentCount = 0, sortOrder = 0, updatedAt = t))
            }
            return CreateAreaResult.Success(createdPrivateId)
        }
        if (drafts.isEmpty()) return CreateAreaResult.Error("Добавьте хотя бы один подъезд")

        // Проверка уникальности номеров подъездов
        val numbers = drafts.map { it.number }
        if (numbers.size != numbers.distinct().size) {
            return CreateAreaResult.Error("Номера подъездов должны быть уникальными")
        }
        // Проверка пересечения номеров квартир внутри участка
        val used = mutableSetOf<Int>()
        for (d in drafts) {
            if (d.floorCount <= 0) return CreateAreaResult.Error("В подъезде № ${d.number} должно быть хотя бы 1 этаж")
            if (d.apartmentsPerFloor.size != d.floorCount || d.apartmentsPerFloor.any { it < 0 }) {
                return CreateAreaResult.Error("Некорректное количество квартир в подъезде № ${d.number}")
            }
            var n = d.startNumber
            for (count in d.apartmentsPerFloor) {
                repeat(count) {
                    if (n in used) return CreateAreaResult.Error("Пересечение номеров квартир: № $n повторяется в подъезде № ${d.number}")
                    used.add(n); n++
                }
            }
        }

        val now = System.currentTimeMillis()
        var createdId = 0L
        db.withTransactionCompat {
            val areaId = areaDao.insert(AreaEntity(uuid = newUuid(), name = trimmed, createdAt = now, updatedAt = now))
            createdId = areaId
            for (d in drafts) {
                val entranceId = entranceDao.insert(
                    EntranceEntity(uuid = newUuid(), areaId = areaId, entranceNumber = d.number, createdAt = now, updatedAt = now)
                )
                var nextNumber = d.startNumber
                for ((index, count) in d.apartmentsPerFloor.withIndex()) {
                    val floorNumber = index + 1
                    val floorId = floorDao.insert(
                        FloorEntity(
                            uuid = newUuid(),
                            entranceId = entranceId, floorNumber = floorNumber,
                            apartmentCount = count, sortOrder = index, updatedAt = now,
                        )
                    )
                    if (count > 0) {
                        val list = (0 until count).map { i ->
                            val t = System.currentTimeMillis()
                            ApartmentEntity(
                                uuid = newUuid(),
                                entranceId = entranceId, floorId = floorId,
                                apartmentNumber = nextNumber + i,
                                createdAt = t, updatedAt = t,
                            )
                        }
                        apartmentDao.insertAll(list)
                        nextNumber += count
                    }
                }
            }
        }
        return CreateAreaResult.Success(createdId)
    }

    suspend fun renameArea(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        areaDao.getById(id)?.let {
            areaDao.update(it.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Удаление участка — каскадное (подъезды, этажи, квартиры и история удаляются вместе с ним). */
    suspend fun deleteArea(id: Long) = areaDao.delete(id)

    suspend fun deleteAll() = db.withTransactionCompat {
        areaDao.deleteAll()
    }
}

/** Расширение для запуска транзакций Room из suspend-кода. */
suspend fun <R> AppDatabase.withTransactionCompat(block: suspend () -> R): R =
    this.withTransaction { block() }

/** Генерация стабильного UUID для синхронизации между устройствами. */
fun newUuid(): String = java.util.UUID.randomUUID().toString()
