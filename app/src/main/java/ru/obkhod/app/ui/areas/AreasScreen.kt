package ru.obkhod.app.ui.areas

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Главный экран — список участков (п. 3 ТЗ).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreasScreen(
    cards: List<AreaCard>,
    loaded: Boolean = true,
    onOpenSettings: () -> Unit = {},
    onOpenArea: (Long) -> Unit,
    onAddArea: () -> Unit,
    onDeleteArea: (Long) -> Unit,
    onRenameArea: (Long, String) -> Unit,
    onExportArea: (Long) -> Unit,
) {
    var menuFor by remember { mutableStateOf<AreaCard?>(null) }
    var deleteTarget by remember { mutableStateOf<AreaCard?>(null) }
    var renameTarget by remember { mutableStateOf<AreaCard?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мой участок") },
                actions = {
                    // Шестерёнка настроек (п. 20 ТЗ)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        floatingActionButton = {
            // FAB — доступен большим пальцем, одной рукой (п. 21 ТЗ)
            ExtendedFloatingActionButton(
                onClick = onAddArea,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Добавить участок") },
            )
        },
    ) { padding ->
        // Skeleton при первой загрузке (п. 23 ТЗ)
        if (!loaded) {
            val alpha = rememberInfiniteAlpha()
            Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                                MaterialTheme.shapes.medium,
                            ),
                    )
                }
            }
        } else if (cards.isEmpty()) {
            // Пустое состояние с подсказкой
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Участков пока нет",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Нажмите «Добавить участок» внизу экрана —\nвыберите частный сектор или многоквартирный дом",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cards, key = { it.area.id }) { card ->
                    AreaCardView(
                        card = card,
                        onClick = { onOpenArea(card.area.id) },
                        onMenu = { menuFor = card },
                    )
                }
            }
        }
    }

    // Контекстное меню участка
    menuFor?.let { card ->
        DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
            DropdownMenuItem(
                text = { Text("Открыть") },
                onClick = { menuFor = null; onOpenArea(card.area.id) },
                leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
            )
            DropdownMenuItem(
                text = { Text("Редактировать") },
                onClick = { menuFor = null; renameTarget = card },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
            )
            DropdownMenuItem(
                text = { Text("Экспортировать") },
                onClick = { menuFor = null; onExportArea(card.area.id) },
                leadingIcon = { Icon(Icons.Default.Add, null) },
            )
            DropdownMenuItem(
                text = { Text("Удалить") },
                onClick = { menuFor = null; deleteTarget = card },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
            )
        }
    }

    // Подтверждение удаления
    deleteTarget?.let { card ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить участок?") },
            text = { Text("Участок «${card.area.name}» будет удалён вместе со всеми подъездами, квартирами и историей обходов.") },
            confirmButton = {
                TextButton(onClick = { onDeleteArea(card.area.id); deleteTarget = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } },
        )
    }

    // Редактирование названия
    renameTarget?.let { card ->
        NameEditDialog(
            title = "Редактировать участок",
            initial = card.area.name,
            onDismiss = { renameTarget = null },
            onSave = { name -> onRenameArea(card.area.id, name); renameTarget = null },
        )
    }
}

@Composable
private fun AreaCardView(card: AreaCard, onClick: () -> Unit, onMenu: () -> Unit) {
    val isPrivate = card.area.type == ru.obkhod.app.data.db.AreaType.PRIVATE.name
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            // Категория участка
            Surface(
                color = if (isPrivate) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (isPrivate) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    if (isPrivate) "Частный сектор" else "Многоквартирный дом",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.area.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = "Меню") }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (isPrivate) {
                        Text(
                            "${card.stats.totalApartments} ${plural("дом", card.stats.totalApartments, "дома", "домов")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "${card.entranceCount} ${plural("подъезд", card.entranceCount, "подъезда", "подъездов")} · " +
                                "${card.totalFloors} ${plural("этаж", card.totalFloors, "этажа", "этажей")} · " +
                                "${card.stats.totalApartments} ${plural("квартира", card.stats.totalApartments, "квартиры", "квартир")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // Цветные чипы статусов — читаются быстрее текста
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip("Отказ", card.stats.refused, ru.obkhod.app.ui.entrance.statusColor(ru.obkhod.app.data.db.Status.REFUSED))
                        StatusChip("Нет дома", card.stats.noOneHome, ru.obkhod.app.ui.entrance.statusColor(ru.obkhod.app.data.db.Status.NO_ONE_HOME))
                        StatusChip("Повтор", card.stats.repeat, ru.obkhod.app.ui.entrance.statusColor(ru.obkhod.app.data.db.Status.REPEAT))
                        StatusChip("Нежил.", card.stats.nonResidential, ru.obkhod.app.ui.entrance.statusColor(ru.obkhod.app.data.db.Status.NON_RESIDENTIAL))
                        StatusChip("Без обхода", card.stats.withoutVisit, Color(0xFFF1F3F1))
                    }
                }
                // Кольцевой прогресс обхода
                if (card.stats.totalApartments > 0) {
                    val fraction = card.stats.visited.toFloat() / card.stats.totalApartments
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                        CircularProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 5.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text(
                            "${card.stats.visited}/${card.stats.totalApartments}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Пульсирующая прозрачность для skeleton-плейсхолдеров. */
@Composable
private fun rememberInfiniteAlpha(): Float {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha: Float by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.85f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(700),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    return alpha
}

/** Мини-чип статуса с цветной точкой и количеством. */
@Composable
private fun StatusChip(label: String, count: Int, color: Color) {
    if (count > 0) {
        Surface(
            color = color,
            contentColor = Color(0xFF37474F),
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(8.dp).background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape))
                Text("$label $count", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun plural(one: String, n: Int, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && (n % 100 < 10 || n % 100 >= 20) -> few
    else -> many
}

@Composable
fun NameEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Название участка") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
