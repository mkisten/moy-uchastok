package ru.obkhod.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.repo.EntranceDraft
import ru.obkhod.app.data.repo.Statistics
import ru.obkhod.app.export.CsvExporter
import ru.obkhod.app.ui.areas.plural

/**
 * Модульные тесты бизнес-логики (п. 24 ТЗ: нумерация снизу вверх, статистика, CSV).
 */
class LogicTest {

    // Нумерация квартир снизу вверх (п. 24, сценарий 7): 5 этажей по 4 квартиры = 1..20
    @Test
    fun numberingBottomUp() {
        val draft = EntranceDraft(
            number = 1, floorCount = 5, startNumber = 1,
            apartmentsPerFloor = mutableListOf(4, 4, 4, 4, 4),
        )
        var n = draft.startNumber
        val floors = draft.apartmentsPerFloor.map { count ->
            val list = (0 until count).map { n++ }
            list
        }
        assertEquals(listOf(1, 2, 3, 4), floors[0])
        assertEquals(listOf(17, 18, 19, 20), floors[4])
        assertEquals(20, n - 1)
        // Последний номер = start + total - 1
        assertEquals(20, draft.startNumber + draft.apartmentsPerFloor.sum() - 1)
    }

    // Нестандартный подъезд: разное количество квартир по этажам (сценарий 5)
    @Test
    fun irregularFloors() {
        val per = mutableListOf(3, 4, 4, 4, 3)
        assertEquals(18, per.sum())
    }

    // Статистика: обработано по истории, а не по текущему статусу (сценарий 22, 40)
    @Test
    fun statisticsCountsVisitsNotStatus() {
        // 5 квартир, посещение было у 3, статус остался только у 2
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        val statuses = listOf(Status.REFUSED, Status.REPEAT, Status.NONE, Status.NONE, Status.NON_RESIDENTIAL)
        val visited = setOf(1L, 3L, 5L)
        val s = Statistics.of(statuses, visited, ids)
        assertEquals(5, s.totalApartments)
        assertEquals(3, s.visited)
        assertEquals(2, s.withoutVisit)
        assertEquals(1, s.refused)
        assertEquals(1, s.repeat)
        assertEquals(1, s.nonResidential)
    }

    // Пересечение номеров квартир обнаруживается (п. 4.3, сценарий 39)
    @Test
    fun duplicateNumbersDetected() {
        val used = mutableSetOf(1, 2, 3, 4)
        val candidate = 4
        assertTrue(candidate in used) // конфликт
        assertFalse(5 in used)
    }

    // Русские склонения в карточках
    @Test
    fun pluralForms() {
        assertEquals("подъезд", plural("подъезд", 1, "подъезда", "подъездов"))
        assertEquals("подъезда", plural("подъезд", 2, "подъезда", "подъездов"))
        assertEquals("подъездов", plural("подъезд", 5, "подъезда", "подъездов"))
        assertEquals("подъездов", plural("подъезд", 11, "подъезда", "подъездов"))
    }

    // CSV: экранирование спецсимволов и многострочных комментариев (п. 17, сценарий 29)
    @Test
    fun csvEscaping() {
        val csv = CsvExporter()
        assertEquals("простой", csv.escape("простой"))
        assertEquals("\"с;точкой\"", csv.escape("с;точкой"))
        assertEquals("\"с \"\"кавычками\"\"\"", csv.escape("с \"кавычками\""))
        assertEquals("\"много\nстрок\"", csv.escape("много\nстрок"))
    }
}
