package ru.obkhod.app.data.repo

import ru.obkhod.app.data.db.Status

/** Настройки одного подъезда в мастере создания участка (п. 4.2 ТЗ). */
data class EntranceDraft(
    var number: Int = 1,
    var floorCount: Int = 5,
    var startNumber: Int = 1,
    var apartmentsPerFloor: MutableList<Int> = MutableList(5) { 4 },
)

/** Результат валидации мастера создания участка (п. 4.3 ТЗ). */
sealed class CreateAreaResult {
    data class Success(val areaId: Long) : CreateAreaResult()
    data class Error(val message: String) : CreateAreaResult()
}

/** Краткая статистика участка/подъезда (п. 15 ТЗ). */
data class Statistics(
    val totalApartments: Int = 0,
    val visited: Int = 0,
    val withoutVisit: Int = 0,
    val refused: Int = 0,
    val noOneHome: Int = 0,
    val repeat: Int = 0,
    val nonResidential: Int = 0,
) {
    companion object {
        fun of(statuses: List<Status>, visitedIds: Set<Long>, ids: List<Long>): Statistics {
            var refused = 0; var noOne = 0; var repeat = 0; var non = 0
            statuses.forEach {
                when (it) {
                    Status.REFUSED -> refused++
                    Status.NO_ONE_HOME -> noOne++
                    Status.REPEAT -> repeat++
                    Status.NON_RESIDENTIAL -> non++
                    Status.NONE -> {}
                }
            }
            val visited = ids.count { it in visitedIds }
            val total = ids.size
            return Statistics(total, visited, total - visited, refused, noOne, repeat, non)
        }
    }
}

/** Квартира для отображения на схеме подъезда. */
data class ApartmentUi(
    val id: Long,
    val number: Int,
    /** Отображаемый номер: числовой или текстовый («1А»). */
    val label: String,
    val floorNumber: Int,
    val status: Status,
    val hasNote: Boolean,
    val phone: String?,
    val comment: String?,
    val hasChild: Boolean,
    val lastVisitAt: Long?,
    val hasVisits: Boolean,
)
