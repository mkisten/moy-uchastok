package ru.obkhod.app.export

import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.db.ApartmentEntity
import ru.obkhod.app.data.db.AreaEntity
import ru.obkhod.app.data.db.EntranceEntity
import ru.obkhod.app.data.db.FloorEntity
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.db.VisitHistoryEntity

/** Плоская строка квартиры для экспорта (лист «Квартиры», CSV). */
data class ExportApartmentRow(
    val areaName: String,
    val entranceNumber: Int,
    val floorNumber: Int,
    val apartmentNumber: String,
    val status: String,
    val phone: String?,
    val comment: String?,
    val lastVisitAt: Long?,
)

/** Плоская строка истории для экспорта. */
data class ExportHistoryRow(
    val areaName: String,
    val entranceNumber: Int,
    val floorNumber: Int,
    val apartmentNumber: Int,
    val visitDate: Long,
    val previousStatus: String?,
    val newStatus: String?,
    val comment: String?,
    val eventType: String,
)

/** Сводка по участку (лист «Сводка»). */
data class ExportSummaryRow(
    val areaName: String,
    val entranceCount: Int,
    val floorCount: Int,
    val stats: ru.obkhod.app.data.repo.Statistics,
)

/** Все данные для экспорта по выбранным участкам. */
data class ExportData(
    val apartments: List<ExportApartmentRow>,
    val history: List<ExportHistoryRow>,
    val summaries: List<ExportSummaryRow>,
)

/** Загрузка данных для экспорта из БД (пп. 16, 17). */
class ExportDataLoader(private val db: AppDatabase) {

    suspend fun load(areaIds: List<Long>? = null): ExportData {
        val areaDao = db.areaDao()
        val entranceDao = db.entranceDao()
        val floorDao = db.floorDao()
        val apartmentDao = db.apartmentDao()
        val historyDao = db.visitHistoryDao()

        val areas: List<AreaEntity> = if (areaIds == null) areaDao.getAll() else areaIds.mapNotNull { areaDao.getById(it) }
        val allEntrances = entranceDao.getAll()
        val allFloors = floorDao.getAll()
        val visitedIds = historyDao.apartmentIdsWithVisits().toSet()

        val apartments = mutableListOf<ExportApartmentRow>()
        val history = mutableListOf<ExportHistoryRow>()
        val summaries = mutableListOf<ExportSummaryRow>()

        for (area in areas) {
            val entrances = allEntrances.filter { it.areaId == area.id }
            var totalStats = ru.obkhod.app.data.repo.Statistics()
            var totalFloors = 0

            for (entrance in entrances) {
                val floors = allFloors.filter { it.entranceId == entrance.id }
                totalFloors += floors.size
                val floorById = floors.associateBy { it.id }
                val entranceApartments = apartmentDao.getAll()
                    .filter { it.entranceId == entrance.id && it.deletedAt == null }
                val activeIds = entranceApartments.map { it.id }

                for (apt in entranceApartments) {
                    val floorNumber = floorById[apt.floorId]?.floorNumber ?: 0
                    apartments.add(
                        ExportApartmentRow(
                            areaName = area.name,
                            entranceNumber = entrance.entranceNumber,
                            floorNumber = floorNumber,
                            apartmentNumber = apt.label ?: apt.apartmentNumber.toString(),
                            status = statusLabel(Status.valueOf(apt.status)),
                            phone = apt.phone,
                            comment = apt.comment,
                            lastVisitAt = apt.lastVisitAt,
                        )
                    )
                }
                // История только активных квартир выбранного участка (п. 17)
                val historyRecords = historyDao.getAll().filter { it.apartmentId in activeIds }
                for (rec in historyRecords) {
                    val apt = entranceApartments.firstOrNull { it.id == rec.apartmentId } ?: continue
                    history.add(
                        ExportHistoryRow(
                            areaName = area.name,
                            entranceNumber = entrance.entranceNumber,
                            floorNumber = floorById[apt.floorId]?.floorNumber ?: 0,
                            apartmentNumber = apt.apartmentNumber,
                            visitDate = rec.visitDate,
                            previousStatus = rec.previousStatus,
                            newStatus = rec.newStatus,
                            comment = rec.comment,
                            eventType = rec.eventType,
                        )
                    )
                }
            }

            // Сводка по участку (п. 16, лист 3)
            val areaApartments = apartmentDao.getAll().filter { a ->
                a.deletedAt == null && entrances.any { it.id == a.entranceId }
            }
            totalStats = ru.obkhod.app.data.repo.Statistics.of(
                areaApartments.map { Status.valueOf(it.status) },
                visitedIds,
                areaApartments.map { it.id },
            )
            summaries.add(ExportSummaryRow(area.name, entrances.size, totalFloors, totalStats))
        }

        return ExportData(apartments, history, summaries)
    }

    companion object {
        fun statusLabel(status: Status): String = when (status) {
            Status.REFUSED -> "Отказ"
            Status.NO_ONE_HOME -> "Нет дома"
            Status.REPEAT -> "Повтор"
            Status.NON_RESIDENTIAL -> "Не жилая"
            Status.NONE -> "Без статуса"
        }

        fun eventTypeLabel(type: String): String = when (type) {
            "CORRECTION" -> "Исправление"
            "RESET" -> "Сброс"
            else -> "Посещение"
        }
    }
}
