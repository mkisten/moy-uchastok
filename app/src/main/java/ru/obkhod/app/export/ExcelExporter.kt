package ru.obkhod.app.export

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт в XLSX (п. 16 ТЗ): книга с тремя листами — Квартиры, История, Сводка.
 * Кириллица, автоширина столбцов, закреплённая верхняя строка, автофильтры.
 */
class ExcelExporter {

    fun export(data: ExportData, out: OutputStream) {
        XSSFWorkbook().use { wb ->
            val headerFont = wb.createFont().apply { bold = true }
            val headerStyle = wb.createCellStyle().apply {
                setFont(headerFont)
                alignment = HorizontalAlignment.CENTER
            }

            // Лист 1 — Квартиры
            wb.createSheet("Квартиры").apply {
                fillSheet(
                    this, headerStyle,
                    headers = listOf("Участок", "Подъезд", "Этаж", "Квартира", "Статус", "Телефон", "Комментарий", "Дата последнего обхода"),
                ) { row ->
                    var r0 = row
                    data.apartments.forEach { r ->
                        val line = createRow(r0++)
                        line.cell(0, r.areaName)
                        line.cell(1, r.entranceNumber)
                        line.cell(2, r.floorNumber)
                        line.cell(3, r.apartmentNumber)
                        line.cell(4, r.status)
                        line.cell(5, r.phone ?: "")
                        line.cell(6, r.comment ?: "")
                        line.cell(7, r.lastVisitAt?.let { formatDate(it) } ?: "Обход не проводился")
                    }
                    r0
                }
            }

            // Лист 2 — История
            wb.createSheet("История").apply {
                fillSheet(
                    this, headerStyle,
                    headers = listOf("Участок", "Подъезд", "Этаж", "Квартира", "Дата посещения", "Предыдущий статус", "Новый статус", "Комментарий", "Тип события"),
                ) { row ->
                    var r0 = row
                    data.history.forEach { r ->
                        val line = createRow(r0++)
                        line.cell(0, r.areaName)
                        line.cell(1, r.entranceNumber)
                        line.cell(2, r.floorNumber)
                        line.cell(3, r.apartmentNumber)
                        line.cell(4, formatDate(r.visitDate))
                        line.cell(5, label(r.previousStatus))
                        line.cell(6, label(r.newStatus))
                        line.cell(7, r.comment ?: "")
                        line.cell(8, ExportDataLoader.eventTypeLabel(r.eventType))
                    }
                    r0
                }
            }

            // Лист 3 — Сводка
            wb.createSheet("Сводка").apply {
                fillSheet(
                    this, headerStyle,
                    headers = listOf("Участок", "Количество подъездов", "Количество этажей", "Всего квартир", "Обработано", "Без обхода", "Отказ", "Нет дома", "Повтор", "Не жилые", "Грубый отказ", "Ребёнок"),
                ) { row ->
                    var r0 = row
                    data.summaries.forEach { r ->
                        val line = createRow(r0++)
                        line.cell(0, r.areaName)
                        line.cell(1, r.entranceCount)
                        line.cell(2, r.floorCount)
                        line.cell(3, r.stats.totalApartments)
                        line.cell(4, r.stats.visited)
                        line.cell(5, r.stats.withoutVisit)
                        line.cell(6, r.stats.refused)
                        line.cell(7, r.stats.noOneHome)
                        line.cell(8, r.stats.repeat)
                        line.cell(9, r.stats.nonResidential)
                        line.cell(10, r.stats.roughRefused)
                        line.cell(11, r.stats.child)
                    }
                    r0
                }
            }

            wb.write(out)
            out.flush()
        }
    }

    private fun label(statusName: String?): String =
        statusName?.let { ExportDataLoader.statusLabel(ru.obkhod.app.data.db.Status.valueOf(it)) } ?: "Без статуса"

    private fun formatDate(ts: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))

    private fun fillSheet(
        sheet: XSSFSheet,
        headerStyle: CellStyle,
        headers: List<String>,
        body: (Int) -> Int,
    ) {
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            val c = headerRow.createCell(i)
            c.setCellValue(h)
            c.setCellStyle(headerStyle)
        }
        var row = body(1)
        // Автоширина столбцов
        headers.indices.forEach { sheet.autoSizeColumn(it) }
        // Закреплённая верхняя строка + автофильтр
        sheet.createFreezePane(0, 1)
        sheet.setAutoFilter(CellRangeAddress(0, row - 1, 0, headers.size - 1))
    }
}

// Удобные расширения для записи ячеек
private fun Row.cell(index: Int, value: String) {
    createCell(index).setCellValue(value)
}

private fun Row.cell(index: Int, value: Int) {
    createCell(index).setCellValue(value.toDouble())
}

private fun Row.cell(index: Int, value: Long) {
    createCell(index).setCellValue(value.toDouble())
}
