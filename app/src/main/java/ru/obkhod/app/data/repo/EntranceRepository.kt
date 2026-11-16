package ru.obkhod.app.data.repo

import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.db.ApartmentEntity
import ru.obkhod.app.data.db.EntranceEntity
import ru.obkhod.app.data.db.EventType
import ru.obkhod.app.data.db.FloorEntity
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.db.VisitHistoryEntity
import ru.obkhod.app.data.repo.withTransactionCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class EntranceRepository(private val db: AppDatabase) {
    private val areaDao = db.areaDao()
    private val entranceDao = db.entranceDao()
    private val floorDao = db.floorDao()
    private val apartmentDao = db.apartmentDao()
    private val historyDao = db.visitHistoryDao()

    fun observeEntrance(id: Long): Flow<EntranceEntity?> = entranceDao.observeByEntrance(id)

    fun observeFloors(entranceId: Long): Flow<List<FloorEntity>> = floorDao.observeByEntrance(entranceId)

    suspend fun observeFloorsOnce(entranceId: Long): List<FloorEntity> = floorDao.getByEntrance(entranceId)

    fun observeApartments(entranceId: Long): Flow<List<ApartmentEntity>> =
        apartmentDao.observeByEntrance(entranceId)

    /** Схема подъезда: этажи + активные квартиры + признак наличия посещений (п. 6, 10). */
    fun observeFloorScheme(entranceId: Long): Flow<List<FloorWithApartments>> =
        combine(
            floorDao.observeByEntrance(entranceId),
            apartmentDao.observeByEntrance(entranceId),
            historyDao.apartmentIdsWithVisitsFlow(),
        ) { floors, apartments, visitedIds ->
            val visited = visitedIds.toSet()
            floors.map { floor ->
                FloorWithApartments(
                    floor = floor,
                    apartments = apartments
                        .filter { it.floorId == floor.id }
                        .sortedBy { it.apartmentNumber }
                        .map { it.toUi(floor.floorNumber, visited) },
                )
            }.sortedByDescending { it.floor.floorNumber }
        }

    fun observeStatistics(entranceId: Long): Flow<Statistics> =
        combine(
            apartmentDao.observeByEntrance(entranceId),
            historyDao.apartmentIdsWithVisitsFlow(),
        ) { apartments, visitedIds ->
            Statistics.of(
                apartments.map { Status.valueOf(it.status) },
                visitedIds.toSet(),
                apartments.map { it.id },
            )
        }

    fun observeApartment(id: Long): Flow<ApartmentEntity?> = apartmentDao.observeById(id)

    fun observeHistory(apartmentId: Long): Flow<List<VisitHistoryEntity>> =
        historyDao.observeByApartment(apartmentId)

    fun observeDeletedApartments(): Flow<List<ApartmentEntity>> = apartmentDao.observeDeleted()

    // Агрегированные потоки по всем участкам — для главного экрана и экспорта
    fun observeAllEntrances(): Flow<List<EntranceEntity>> = entranceDao.observeAll()

    /** Первый подъезд участка (для частного сектора — служебный подъезд с домами). */
    suspend fun firstEntranceId(areaId: Long): Long? =
        entranceDao.getByArea(areaId).firstOrNull()?.id
    fun observeAllFloors(): Flow<List<FloorEntity>> = floorDao.observeAll()
    fun observeAllActiveApartments(): Flow<List<ApartmentEntity>> = apartmentDao.observeAllActive()

    val visitedApartmentIds: Flow<Set<Long>> =
        historyDao.apartmentIdsWithVisitsFlow().map { ids -> ids.toSet() }

    /** Установка статуса: пишет запись истории, обновляет дату обхода (пп. 7, 13, 14). */
    suspend fun setStatus(apartmentId: Long, newStatus: Status, isCorrection: Boolean = false, visitComment: String? = null) {
        val now = System.currentTimeMillis()
        val apt = apartmentDao.getById(apartmentId) ?: return
        val prev = Status.valueOf(apt.status)
        if (prev == newStatus && !isCorrection) return
        db.withTransactionCompat {
            apartmentDao.update(
                apt.copy(
                    status = newStatus.name,
                    lastVisitAt = now,
                    updatedAt = now,
                )
            )
            historyDao.insert(
                VisitHistoryEntity(
                    apartmentId = apartmentId,
                    visitDate = now,
                    previousStatus = if (prev == Status.NONE) null else prev.name,
                    newStatus = newStatus.name,
                    comment = visitComment,
                    eventType = if (isCorrection) EventType.CORRECTION.name else EventType.VISIT.name,
                )
            )
        }
    }

    /** Сброс статуса: дата обхода и история сохраняются (пп. 7, 13, 14). */
    suspend fun resetStatus(apartmentId: Long) {
        val now = System.currentTimeMillis()
        val apt = apartmentDao.getById(apartmentId) ?: return
        val prev = Status.valueOf(apt.status)
        if (prev == Status.NONE) return
        db.withTransactionCompat {
            apartmentDao.update(apt.copy(status = Status.NONE.name, updatedAt = now))
            historyDao.insert(
                VisitHistoryEntity(
                    apartmentId = apartmentId, visitDate = now,
                    previousStatus = prev.name, newStatus = null, eventType = EventType.RESET.name,
                )
            )
        }
    }

    /** Заметка (телефон/комментарий) — не меняет статус и дату обхода (п. 8). */
    suspend fun saveNote(apartmentId: Long, phone: String?, comment: String?) {
        val apt = apartmentDao.getById(apartmentId) ?: return
        val p = phone?.trim()?.takeIf { it.isNotEmpty() }
        val c = comment?.trim()?.takeIf { it.isNotEmpty() }
        apartmentDao.update(apt.copy(phone = p, comment = c, updatedAt = System.currentTimeMillis()))
    }

    /** Перемещение квартиры на другой этаж (п. 11). Возвращает ошибку или null. */
    suspend fun moveApartment(apartmentId: Long, targetFloorId: Long): MoveResult {
        val apt = apartmentDao.getById(apartmentId) ?: return MoveResult.ERROR
        if (apt.floorId == targetFloorId) return MoveResult.OK
        val targetFloor = floorDao.getById(targetFloorId) ?: return MoveResult.ERROR
        if (targetFloor.entranceId != apt.entranceId) return MoveResult.ERROR // только внутри подъезда
        // Запрет дубликата номера на этаже-приёмнике
        val duplicates = apartmentDao.observeByEntranceOnce(targetFloor.entranceId)
            .filter { it.floorId == targetFloorId && it.apartmentNumber == apt.apartmentNumber && it.id != apt.id }
        if (duplicates.isNotEmpty()) return MoveResult.DUPLICATE
        apartmentDao.update(apt.copy(floorId = targetFloorId, updatedAt = System.currentTimeMillis()))
        return MoveResult.OK
    }

    /** Логическое удаление квартиры (п. 12): история и данные сохраняются. */
    suspend fun deleteApartment(apartmentId: Long) {
        val now = System.currentTimeMillis()
        apartmentDao.softDelete(apartmentId, now, now)
    }

    /** Восстановление удалённой квартиры на прежний этаж, если он существует (п. 12). */
    suspend fun restoreApartment(apartmentId: Long): Boolean {
        val apt = apartmentDao.getById(apartmentId) ?: return false
        val floor = floorDao.getById(apt.floorId) ?: return false
        if (apartmentDao.findActiveByNumber(floor.entranceId, apt.apartmentNumber) != null) return false
        apartmentDao.restore(apartmentId, System.currentTimeMillis())
        return true
    }

    /** Редактирование подъезда: добавить/удалить этаж, изменить номер (п. 19). */
    suspend fun changeEntranceNumber(entranceId: Long, newNumber: Int): String? {
        val entrance = entranceDao.getById(entranceId) ?: return "Подъезд не найден"
        if (entranceDao.countByNumber(entrance.areaId, newNumber) > 0) return "Подъезд с таким номером уже существует"
        entranceDao.update(entrance.copy(entranceNumber = newNumber))
        return null
    }

    /** Добавление этажа сверху. Возвращает id нового этажа. */
    suspend fun addFloor(entranceId: Long, apartmentsOnFloor: Int): Long {
        val maxNumber = floorDao.getByEntrance(entranceId).maxOfOrNull { it.floorNumber } ?: 0
        val newNumber = maxNumber + 1
        val now = System.currentTimeMillis()
        return db.withTransactionCompat {
            val floorId = floorDao.insert(
                FloorEntity(
                    entranceId = entranceId, floorNumber = newNumber,
                    apartmentCount = apartmentsOnFloor,
                    sortOrder = floorDao.getByEntrance(entranceId).count(),
                )
            )
            if (apartmentsOnFloor > 0) {
                // Нумерация продолжается с последнего существующего номера подъезда,
                // а не с 1 (учитываются и текстовые номера — числовая часть label)
                val existingMax = apartmentDao.observeByEntranceOnce(entranceId)
                    .maxOfOrNull { apt -> apt.label?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: apt.apartmentNumber }
                    ?: 0
                apartmentDao.insertAll(
                    (1..apartmentsOnFloor).map { i ->
                        ApartmentEntity(
                            entranceId = entranceId, floorId = floorId,
                            apartmentNumber = existingMax + i, createdAt = now, updatedAt = now,
                        )
                    }
                )
            }
            floorId
        }
    }

    /**
     * Редактирование квартиры: смена этажа и/или номера (номер может быть текстовым, «1А»).
     * При recalcAll = true все квартиры подъезда пересчитываются снизу вверх,
     * НАЧИНАЯ С МИНИМАЛЬНОГО СУЩЕСТВУЮЩЕГО НОМЕРА подъезда (а не с 1).
     * Метка отредактированной квартиры (например «1А») сохраняется и после пересчёта.
     * Возвращает сообщение об ошибке или null.
     */
    suspend fun editApartment(apartmentId: Long, newFloorId: Long?, newNumberText: String, recalcAll: Boolean): String? {
        val apt = apartmentDao.getById(apartmentId) ?: return "Квартира не найдена"
        val targetFloorId = newFloorId ?: apt.floorId
        val targetFloor = floorDao.getById(targetFloorId) ?: return "Этаж не найден"
        if (targetFloor.entranceId != apt.entranceId) return "Этаж принадлежит другому подъезду"

        val trimmed = newNumberText.trim()
        if (trimmed.isEmpty()) return "Укажите номер квартиры"
        val newNumber: Int
        val newLabel: String?
        if (trimmed.matches(Regex("\\d+"))) {
            newNumber = trimmed.toInt()
            newLabel = null
        } else {
            val digits = Regex("\\d+").find(trimmed)?.value ?: return "Номер квартиры должен содержать цифры"
            newNumber = digits.toInt()
            newLabel = trimmed
        }

        val now = System.currentTimeMillis()

        // Режим «без пересчёта»: проверяем конфликт отображаемых номеров на этаже-приёмнике
        if (!recalcAll) {
            val sameFloor = apartmentDao.observeByEntranceOnce(apt.entranceId)
                .filter { it.floorId == targetFloorId && it.id != apt.id }
            val newKey = newLabel ?: newNumber.toString()
            val conflict = sameFloor.any { (it.label ?: it.apartmentNumber.toString()) == newKey }
            if (conflict) return "На ${targetFloor.floorNumber} этаже уже есть квартира с номером $newKey"
        }

        // Минимальный существующий числовой номер подъезда — точка отсчёта пересчёта
        val numericOf = { a: ApartmentEntity ->
            a.label?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: a.apartmentNumber
        }
        val recalcStart = apartmentDao.observeByEntranceOnce(apt.entranceId)
            .map(numericOf).filter { it > 0 }.minOrNull() ?: 1

        db.withTransactionCompat {
            apartmentDao.update(
                apt.copy(floorId = targetFloorId, apartmentNumber = newNumber, label = newLabel, updatedAt = now)
            )

            if (recalcAll) {
                // Пересчёт снизу вверх от минимального номера подъезда.
                // Квартира с текстовой меткой («29А») НЕ расходует числовой номер:
                // она встаёт после своей числовой части, а последующие числовые
                // квартиры сдвигаются. Пример: 29, 30→«29А», 31 ⇒ 29, 29А, 30.
                val floors = floorDao.getByEntrance(apt.entranceId).sortedBy { it.floorNumber }
                var counter = recalcStart
                for (floor in floors) {
                    val list = apartmentDao.observeByEntranceOnce(apt.entranceId)
                        .filter { it.floorId == floor.id && it.deletedAt == null }
                        .sortedWith(
                            compareBy(
                                { numericOf(it) },
                                // при равной числовой части: сначала число, потом метки по алфавиту
                                { it.label != null },
                                { it.label ?: "" },
                            )
                        )
                    for (a in list) {
                        if (a.label != null) {
                            // помеченная квартира сохраняет метку, счётчик не двигается
                            apartmentDao.update(a.copy(label = a.label, updatedAt = now))
                        } else {
                            apartmentDao.update(a.copy(apartmentNumber = counter++, label = null, updatedAt = now))
                        }
                    }
                }
            }
        }
        return null
    }

    /** Удаление этажа. Если этаж содержит квартиры — требуется решение пользователя (п. 19). */
    suspend fun removeFloor(floorId: Long, deleteApartments: Boolean): Boolean {
        val floor = floorDao.getById(floorId) ?: return true
        val apartments = apartmentDao.observeByEntranceOnce(floor.entranceId).filter { it.floorId == floorId }
        return db.withTransactionCompat {
            if (apartments.isNotEmpty() && !deleteApartments) {
                false // нужна явная операция
            } else {
                if (deleteApartments) {
                    val now = System.currentTimeMillis()
                    apartments.forEach { apartmentDao.softDelete(it.id, now, now) }
                }
                floorDao.delete(floorId)
                true
            }
        }
    }

    suspend fun deleteEntrance(entranceId: Long) = entranceDao.delete(entranceId)

    /**
     * Частный сектор: добавление дома (свободный порядок, номер может быть «25А»).
     * Дома хранятся как квартиры на служебном этаже; дубликат отображаемого номера запрещён.
     */
    suspend fun addHouse(entranceId: Long, numberText: String): String? {
        val trimmed = numberText.trim()
        if (trimmed.isEmpty()) return "Укажите номер дома"
        val floors = floorDao.getByEntrance(entranceId)
        val floor = floors.minByOrNull { it.floorNumber } ?: return "Участок повреждён: нет этажа"
        val existing = apartmentDao.observeByEntranceOnce(entranceId)
        if (existing.any { (it.label ?: it.apartmentNumber.toString()) == trimmed }) {
            return "Дом с номером $trimmed уже есть"
        }
        val digits = Regex("\\d+").find(trimmed)?.value?.toIntOrNull()
            ?: return "Номер дома должен содержать цифры"
        val now = System.currentTimeMillis()
        apartmentDao.insert(
            ApartmentEntity(
                entranceId = entranceId, floorId = floor.id,
                apartmentNumber = digits,
                label = if (trimmed.matches(Regex("\\d+"))) null else trimmed,
                createdAt = now, updatedAt = now,
            )
        )
        return null
    }

    /** Добавление подъезда в существующий участок (пп. 5, 19). */
    suspend fun addEntrance(areaId: Long, draft: EntranceDraft): String? {
        if (entranceDao.countByNumber(areaId, draft.number) > 0) {
            return "Подъезд с таким номером уже существует"
        }
        if (draft.floorCount <= 0) return "Должен быть хотя бы 1 этаж"
        // Проверка пересечения номеров квартир в пределах участка (п. 4.3)
        var n = draft.startNumber
        val newNumbers = mutableListOf<Int>()
        for (count in draft.apartmentsPerFloor) repeat(count) { newNumbers.add(n++); }
        val otherEntrances = entranceDao.getByArea(areaId).filter { it.id != 0L }
        for (e in otherEntrances) {
            val used = apartmentDao.observeByEntranceOnce(e.id).map { it.apartmentNumber }.toSet()
            val clash = newNumbers.firstOrNull { it in used }
            if (clash != null) return "Пересечение номеров квартир: № $clash уже есть в подъезде № ${e.entranceNumber}"
        }
        val now = System.currentTimeMillis()
        db.withTransactionCompat {
            val entranceId = entranceDao.insert(
                EntranceEntity(areaId = areaId, entranceNumber = draft.number, createdAt = now)
            )
            var next = draft.startNumber
            for ((index, count) in draft.apartmentsPerFloor.withIndex()) {
                val floorId = floorDao.insert(
                    FloorEntity(
                        entranceId = entranceId, floorNumber = index + 1,
                        apartmentCount = count, sortOrder = index,
                    )
                )
                if (count > 0) {
                    apartmentDao.insertAll(
                        (0 until count).map { i ->
                            val t = System.currentTimeMillis()
                            ApartmentEntity(
                                entranceId = entranceId, floorId = floorId,
                                apartmentNumber = next + i, createdAt = t, updatedAt = t,
                            )
                        }
                    )
                    next += count
                }
            }
        }
        return null
    }
}

enum class MoveResult { OK, DUPLICATE, ERROR }

fun ApartmentEntity.toUi(floorNumber: Int, visitedIds: Set<Long>) = ApartmentUi(
    id = id,
    number = apartmentNumber,
    label = label ?: apartmentNumber.toString(),
    floorNumber = floorNumber,
    status = Status.valueOf(status),
    hasNote = !phone.isNullOrBlank() || !comment.isNullOrBlank(),
    phone = phone,
    comment = comment,
    lastVisitAt = lastVisitAt,
    hasVisits = id in visitedIds,
)

data class FloorWithApartments(
    val floor: FloorEntity,
    val apartments: List<ApartmentUi>,
)
