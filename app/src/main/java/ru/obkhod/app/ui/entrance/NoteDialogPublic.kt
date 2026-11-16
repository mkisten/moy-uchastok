package ru.obkhod.app.ui.entrance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.obkhod.app.data.repo.ApartmentUi

/** Публичная обёртка формы заметки для использования из навигации (п. 8). */
@Composable
fun NoteDialogPublic(
    apartment: ApartmentUi,
    isPrivate: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String?, String?, Boolean) -> Unit,
) {
    var phone by remember { mutableStateOf(apartment.phone ?: "") }
    var comment by remember { mutableStateOf(apartment.comment ?: "") }
    var hasChild by remember { mutableStateOf(apartment.hasChild) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPrivate) "Заметка — дом № ${apartment.label}" else "Заметка — квартира № ${apartment.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Телефон") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("Комментарий") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = hasChild, onCheckedChange = { hasChild = it })
                    Text(if (isPrivate) "В доме есть ребёнок" else "В квартире есть ребёнок")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(phone.ifBlank { null }, comment.ifBlank { null }, hasChild) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
