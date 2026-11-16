package ru.obkhod.app.ui.area

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.obkhod.app.data.db.EntranceEntity
import ru.obkhod.app.data.db.FloorEntity
import ru.obkhod.app.data.db.ApartmentEntity
import ru.obkhod.app.data.repo.Statistics

/** Данные для экрана участка: подъезды с их краткой статистикой. */
data class EntranceCard(
    val entrance: EntranceEntity,
    val floors: List<FloorEntity>,
    val apartments: List<ApartmentEntity>,
    val visitedCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreaScreen(
    areaName: String,
    cards: List<EntranceCard>,
    isPrivate: Boolean = false,
    onOpenEntrance: (Long) -> Unit,
    onBack: () -> Unit,
    onAddEntrance: () -> Unit,
    onEditEntrance: (Long) -> Unit,
    onDeleteEntrance: (Long) -> Unit,
    onOpenDeleted: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<EntranceCard?>(null) }
    var deleteFor by remember { mutableStateOf<EntranceCard?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(areaName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    TextButton(onClick = onOpenDeleted) { Text("Удалённые") }
                    // В частном секторе подъезды не добавляются — только дома
                    if (!isPrivate) {
                        Button(onClick = onAddEntrance, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp)); Text("Подъезд")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Подъездов нет.\nДобавьте первый подъезд.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cards, key = { it.entrance.id }) { card ->
                    ElevatedCard(onClick = { onOpenEntrance(card.entrance.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Подъезд № ${card.entrance.entranceNumber}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${card.floors.size} ${ru.obkhod.app.ui.areas.plural("этаж", card.floors.size, "этажа", "этажей")} · " +
                                        "${card.apartments.size} ${ru.obkhod.app.ui.areas.plural("квартира", card.apartments.size, "квартиры", "квартир")}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Обработано: ${card.visitedCount} / ${card.apartments.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { menuFor = card }) { Icon(Icons.Default.MoreVert, "Меню") }
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { card ->
        DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
            DropdownMenuItem(
                text = { Text("Редактировать") },
                onClick = { menuFor = null; onEditEntrance(card.entrance.id) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
            )
            DropdownMenuItem(
                text = { Text("Удалить подъезд") },
                onClick = { menuFor = null; deleteFor = card },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
            )
        }
    }

    deleteFor?.let { card ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Удалить подъезд № ${card.entrance.entranceNumber}?") },
            text = { Text("Подъезд, его этажи, квартиры и история обходов будут удалены. Квартиры можно предварительно перенести в другие подъезды вручную.") },
            confirmButton = {
                TextButton(onClick = { onDeleteEntrance(card.entrance.id); deleteFor = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Отмена") } },
        )
    }
}
