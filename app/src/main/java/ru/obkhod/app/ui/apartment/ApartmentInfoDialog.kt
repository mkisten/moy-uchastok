package ru.obkhod.app.ui.apartment

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.db.VisitHistoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val formatPlain = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
private val weekdays = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

private fun dialableNumber(text: String): String? {
    val normalized = text.filterNot { it.isWhitespace() || it in "()-" }
    return normalized.takeIf { it.matches(Regex("\\+?[0-9]{5,}")) }
}

fun formatDateTime(ts: Long?): String {
    ts ?: return "Обход не проводился"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    // Calendar.DAY_OF_WEEK: 1 = воскресенье … 7 = суббота → индекс в массиве, начинающемся с Пн
    val weekday = weekdays[(cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7]
    return "$weekday, ${formatPlain.format(Date(ts))}"
}

fun statusLabel(status: Status?): String = when (status) {
    Status.REFUSED -> "Отказ"
    Status.ROUGH_REFUSED -> "Грубый отказ"
    Status.NO_ONE_HOME -> "Нет дома"
    Status.REPEAT -> "Повтор"
    Status.NON_RESIDENTIAL -> "Не жилая"
    Status.CHILD -> "Ребенок один дома"
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
    history: List<VisitHistoryEntity> = emptyList(),
    isPrivate: Boolean = false,
    onDismiss: () -> Unit,
    onSaveNote: (String?, String?, (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val noun = if (isPrivate) "Дом" else "Квартира"
    var phoneText by rememberSaveable(info.id) { mutableStateOf(info.phone.orEmpty()) }
    var commentText by rememberSaveable(info.id) { mutableStateOf(info.comment.orEmpty()) }
    var saving by remember(info.id) { mutableStateOf(false) }
    var saveFailed by remember(info.id) { mutableStateOf(false) }
    val dialNumber = dialableNumber(phoneText)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("$noun № ${info.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (saveFailed) {
                    Text("Не удалось сохранить данные. Попробуйте ещё раз.", color = MaterialTheme.colorScheme.error)
                }
                Text("Подъезд: ${info.entranceNumber} · Этаж: ${info.floorNumber}")
                Text("Статус: ${statusLabel(info.status)}")
                if (info.status == Status.CHILD) {
                    Text("👶 ${if (isPrivate) "В доме" else "В квартире"} ребенок один дома", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                }
                Text("Последний обход: ${formatDateTime(info.lastVisitAt)}")
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = { phoneText = it },
                    label = { Text("Телефон") },
                    supportingText = { Text("Например: +375 29 123-45-67 или «Нет телефона»") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    trailingIcon = {
                        if (dialNumber != null) {
                            IconButton(
                                enabled = !saving,
                                onClick = {
                                    // ACTION_DIAL открывает набор номера без совершения звонка (п. 9)
                                    context.startActivity(
                                        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dialNumber, null))
                                    )
                                },
                            ) { Icon(Icons.Default.Call, contentDescription = "Позвонить") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    label = { Text("Комментарий") },
                    enabled = !saving,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("История:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (history.isEmpty()) {
                    Text("Обход не проводился", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        history.forEach { rec ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(6.dp)) {
                                    Text(formatDateTime(rec.visitDate), style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        statusLabelByName(rec.previousStatus) + " → " + statusLabelByName(rec.newStatus) +
                                            " · " + eventTypeLabel(rec.eventType),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    focus.clearFocus()
                    saving = true
                    saveFailed = false
                    onSaveNote(phoneText.trim().ifBlank { null }, commentText.trim().ifBlank { null }) { saved ->
                        saving = false
                        if (saved) onDismiss() else saveFailed = true
                    }
                },
            ) { Text(if (saving) "Сохраняем…" else "Сохранить") }
        },
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
