package ru.obkhod.app.ui.apartment

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.db.VisitHistoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

fun formatDateTime(ts: Long?): String =
    ts?.let { format.format(Date(it)) } ?: "Обход не проводился"

fun statusLabel(status: Status?): String = when (status) {
    Status.REFUSED -> "Отказ"
    Status.NO_ONE_HOME -> "Нет дома"
    Status.REPEAT -> "Повтор"
    Status.NON_RESIDENTIAL -> "Не жилая"
    Status.NONE, null -> "Без статуса"
}

fun statusLabelByName(name: String?): String =
    name?.let { statusLabel(Status.valueOf(it)) } ?: "Без статуса"

fun eventTypeLabel(type: String): String = when (type) {
    "CORRECTION" -> "Исправление"
    "RESET" -> "Сброс"
    else -> "Посещение"
}

/** Данные инфо-окна квартиры (п. 9 ТЗ). */
data class ApartmentInfo(
    val id: Long,
    val number: Int,
    /** Отображаемый номер (может быть текстовым, «1А»). */
    val label: String,
    val entranceNumber: Int,
    val floorNumber: Int,
    val status: Status,
    val lastVisitAt: Long?,
    val phone: String?,
    val comment: String?,
    val hasVisits: Boolean,
)

/**
 * Информационное окно квартиры: статус, дата обхода, телефон с кнопкой вызова
 * через ACTION_DIAL (без автоматического звонка), комментарий (п. 9).
 */
@Composable
fun ApartmentInfoDialog(
    info: ApartmentInfo,
    isPrivate: Boolean = false,
    onDismiss: () -> Unit,
    onEditNote: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val noun = if (isPrivate) "Дом" else "Квартира"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$noun № ${info.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Подъезд: ${info.entranceNumber} · Этаж: ${info.floorNumber}")
                Text("Статус: ${statusLabel(info.status)}")
                Text("Последний обход: ${formatDateTime(info.lastVisitAt)}")
                if (!info.phone.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(info.phone, fontWeight = FontWeight.SemiBold)
                        IconButton(
                            onClick = {
                                // ACTION_DIAL открывает набор номера без совершения звонка (п. 9)
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(info.phone)}"))
                                )
                            },
                        ) { Icon(Icons.Default.Call, contentDescription = "Позвонить") }
                    }
                }
                if (!info.comment.isNullOrBlank()) {
                    Text("Комментарий: ${info.comment}")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEditNote) {
                    Icon(Icons.Default.Edit, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Заметка")
                }
                TextButton(onClick = onOpenHistory) {
                    Icon(Icons.Default.History, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("История")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** История посещений квартиры (п. 14): от новых к старым, без перезаписи. */
@Composable
fun HistoryDialog(
    apartmentNumber: String,
    records: List<VisitHistoryEntity>,
    isPrivate: Boolean = false,
    onDismiss: () -> Unit,
) {
    val noun = if (isPrivate) "Дом" else "Квартира"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$noun № $apartmentNumber — история") },
        text = {
            if (records.isEmpty()) {
                Text("Записей нет — обход не проводился")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    records.forEach { rec ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                Text(
                                    formatDateTime(rec.visitDate),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    statusLabelByName(rec.previousStatus) + " → " + statusLabelByName(rec.newStatus) +
                                        " · " + eventTypeLabel(rec.eventType),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!rec.comment.isNullOrBlank()) {
                                    Text(rec.comment!!, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
