package ru.obkhod.app.export

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт в CSV (п. 17 ТЗ): apartments.csv + history.csv.
 * UTF-8 с BOM, разделитель «;», экранирование кавычек, многострочные комментарии.
 */
class CsvExporter {

    fun exportApartments(data: ExportData, out: OutputStream) {
        val header = listOf("Участок", "Подъезд", "Этаж", "Квартира", "Статус", "Телефон", "Комментарий", "Дата последнего обхода")
        writeCsv(out, header) { w ->
            data.apartments.forEach { r ->
                w(
                    listOf(
                        r.areaName, r.entranceNumber.toString(), r.floorNumber.toString(), r.apartmentNumber.toString(),
                        r.status, r.phone ?: "", r.comment ?: "",
                        r.lastVisitAt?.let { formatDate(it) } ?: "Обход не проводился",
                    )
                )
            }
        }
    }

    fun exportHistory(data: ExportData, out: OutputStream) {
        val header = listOf("Участок", "Подъезд", "Этаж", "Квартира", "Дата посещения", "Предыдущий статус", "Новый статус", "Комментарий", "Тип события")
        writeCsv(out, header) { w ->
            data.history.forEach { r ->
                w(
                    listOf(
                        r.areaName, r.entranceNumber.toString(), r.floorNumber.toString(), r.apartmentNumber.toString(),
                        formatDate(r.visitDate),
                        label(r.previousStatus), label(r.newStatus),
                        r.comment ?: "", ExportDataLoader.eventTypeLabel(r.eventType),
                    )
                )
            }
        }
    }

    private fun label(statusName: String?): String =
        statusName?.let { ExportDataLoader.statusLabel(ru.obkhod.app.data.db.Status.valueOf(it)) } ?: "Без статуса"

    private fun formatDate(ts: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))

    private fun writeCsv(out: OutputStream, header: List<String>, body: ((List<String>) -> Unit) -> Unit) {
        val writer = OutputStreamWriter(out, StandardCharsets.UTF_8)
        // BOM для корректного открытия в Excel
        writer.write("\ufeff")
        writer.write(header.joinToString(";") { escape(it) })
        writer.write("\r\n")
        body { row ->
            writer.write(row.joinToString(";") { escape(it) })
            writer.write("\r\n")
        }
        writer.flush()
    }

    internal fun escape(value: String): String =
        if (value.contains(';') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
