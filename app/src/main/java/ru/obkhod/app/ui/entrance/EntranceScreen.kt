package ru.obkhod.app.ui.entrance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.repo.ApartmentUi
import ru.obkhod.app.data.repo.FloorWithApartments
import ru.obkhod.app.data.repo.Statistics

/** Фильтры квартир (п. 10 ТЗ). */
enum class Filter(val label: String) {
    ALL("Все"), REPEAT("Повтор"), NOT_VISITED("Без обхода"),
    REFUSED("Отказ"), NO_ONE_HOME("Нет дома"), NON_RESIDENTIAL("Не жилая"),
    ROUGH_REFUSED("Грубый отказ"), CHILD("Ребёнок"),
}

fun statusColor(status: Status): Color = when (status) {
    Status.REFUSED -> Color(0xFFFFCDD2)        // красный светлый
    Status.ROUGH_REFUSED -> Color(0xFFE53935)  // грубый отказ — ярко-красный
    Status.NO_ONE_HOME -> Color(0xFFFFF59D)    // жёлтый
    Status.REPEAT -> Color(0xFFC8E6C9)         // зелёный
    Status.NON_RESIDENTIAL -> Color(0xFFCFD8DC) // серый
    Status.CHILD -> Color(0xFF90CAF9)          // ребёнок — голубой
    Status.NONE -> Color(0xFFF1F3F1)           // светло-серый
}

/** Локальное состояние жеста перемещения квартиры (п. 11). */
class DragState {
    var draggingId by mutableStateOf<Long?>(null)
    var offset by mutableStateOf(Offset.Zero)
    var hoverFloorId by mutableStateOf<Long?>(null)
    val floorBounds = mutableMapOf<Long, Pair<Offset, IntSize>>()
}

@Composable
fun rememberDragState() = remember { DragState() }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EntranceScreen(
    areaName: String,
    entranceNumber: Int,
    scheme: List<FloorWithApartments>,
    statistics: Statistics,
    isPrivate: Boolean = false,
    onBack: () -> Unit,
    onSetStatus: (Long, Status, Boolean) -> Unit,
    onResetStatus: (Long) -> Unit,
    onSaveNote: (Long, String?, String?) -> Unit,
    onMove: (Long, Long, (String?) -> Unit) -> Unit,
    onDelete: (Long) -> Unit,
    onRestore: (Long) -> Unit = {},
    onEditApartment: (Long, Long?, String, Boolean, (String?) -> Unit) -> Unit,
    onEditEntrance: () -> Unit,
    onAddFloor: (Int) -> Unit,
    onRemoveFloor: (Long, (List<Int>) -> Unit) -> Unit,
    onOpenInfo: (Long) -> Unit,
    onAddHouse: ((String, (String?) -> Unit) -> Unit)? = null,
) {
    var filter by remember { mutableStateOf(Filter.ALL) }
    var actionFor by remember { mutableStateOf<ApartmentUi?>(null) }
    var longPressFor by remember { mutableStateOf<ApartmentUi?>(null) }
    var noteFor by remember { mutableStateOf<ApartmentUi?>(null) }
    var moveFor by remember { mutableStateOf<ApartmentUi?>(null) }
    var editFor by remember { mutableStateOf<ApartmentUi?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var removeFloorFor by remember { mutableStateOf<FloorWithApartments?>(null) }
    var pendingMove by remember { mutableStateOf<Pair<ApartmentUi, Long>?>(null) }
    var addHouseDialog by remember { mutableStateOf(false) }
    var searchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val drag = rememberDragState()
    // Snackbar с Undo при удалении (мягкое удаление позволяет вернуть квартиру)
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // Полный адрес над кнопками, уменьшенный шрифт, без обрезки
                    Column {
                        Text(
                            areaName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        if (!isPrivate) {
                            Text(
                                "Подъезд № $entranceNumber",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    if (isPrivate) {
                        // Быстрый поиск дома по номеру (при большом количестве домов на улице)
                        IconButton(onClick = { searchDialog = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Найти дом")
                        }
                    } else {
                        IconButton(onClick = onEditEntrance) { Icon(Icons.Default.Edit, "Редактировать подъезд") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Сворачиваемая панель статистики и фильтров: максимум места для схемы (п. 21)
            var panelsExpanded by remember { mutableStateOf(false) }
            val allApartments = scheme.flatMap { it.apartments }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Обработано: ${statistics.visited} / ${statistics.totalApartments}" +
                                if (statistics.withoutVisit > 0) " · осталось ${statistics.withoutVisit}" else " · всё!",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (filter != Filter.ALL) {
                            TextButton(onClick = { filter = Filter.ALL }) { Text("Сбросить") }
                        }
                        IconButton(onClick = { panelsExpanded = !panelsExpanded }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Фильтры и статистика")
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(visible = panelsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Легенда цветов (п. 6, 7)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    Status.NONE to "Нет",
                                    Status.REFUSED to "Отказ",
                                    Status.NO_ONE_HOME to "Нет дома",
                                    Status.REPEAT to "Повтор",
                                    Status.NON_RESIDENTIAL to "Нежил.",
                                ).forEach { (status, label) ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Box(Modifier.size(12.dp).background(statusColor(status), androidx.compose.foundation.shape.CircleShape))
                                        Text(label, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Text(
                                "Отказ: ${statistics.refused} · Нет дома: ${statistics.noOneHome} · " +
                                    "Повтор: ${statistics.repeat} · Не жилые: ${statistics.nonResidential}" +
                                    " · Груб. отказ: ${statistics.roughRefused} · Ребёнок: ${statistics.child}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Фильтры со счётчиками (п. 10, 15)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilterChip(
                                    selected = filter == Filter.ALL,
                                    onClick = { filter = Filter.ALL },
                                    label = { Text("Все · ${allApartments.size}") },
                                )
                                Filter.entries.filter { it != Filter.ALL }.forEach { f ->
                                    val count = allApartments.count { apt ->
                                        when (f) {
                                            Filter.REPEAT -> apt.status == Status.REPEAT
                                            Filter.NOT_VISITED -> !apt.hasVisits
                                            Filter.REFUSED -> apt.status == Status.REFUSED
                                            Filter.NO_ONE_HOME -> apt.status == Status.NO_ONE_HOME
                                            Filter.NON_RESIDENTIAL -> apt.status == Status.NON_RESIDENTIAL
                                            Filter.ROUGH_REFUSED -> apt.status == Status.ROUGH_REFUSED
                                            Filter.CHILD -> apt.status == Status.CHILD
                                            Filter.ALL -> true
                                        }
                                    }
                                    FilterChip(
                                        selected = filter == f,
                                        onClick = { filter = f },
                                        label = { Text("${f.label} · $count") },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (isPrivate) {
                // Частный сектор: дома плитками по 4 в ряд, меньшие сверху, большие внизу
                val houses = scheme.flatMap { it.apartments }
                    .sortedWith(compareBy({ it.number }, { it.label }))
                val visible = houses.filter { apt ->
                    val byFilter = when (filter) {
                        Filter.ALL -> true
                        Filter.REPEAT -> apt.status == Status.REPEAT
                        Filter.NOT_VISITED -> !apt.hasVisits
                        Filter.REFUSED -> apt.status == Status.REFUSED
                        Filter.NO_ONE_HOME -> apt.status == Status.NO_ONE_HOME
                        Filter.NON_RESIDENTIAL -> apt.status == Status.NON_RESIDENTIAL
                        Filter.ROUGH_REFUSED -> apt.status == Status.ROUGH_REFUSED
                        Filter.CHILD -> apt.status == Status.CHILD
                    }
                    val bySearch = searchQuery.isBlank() ||
                        apt.label.contains(searchQuery.trim(), ignoreCase = true)
                    byFilter && bySearch
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (searchQuery.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Поиск: «${searchQuery.trim()}» — найдено ${visible.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { searchQuery = "" }) { Text("Сбросить") }
                        }
                    }
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        visible.forEach { apt ->
                            HouseTile(
                                house = apt,
                                onTap = { actionFor = apt },
                                onLongPress = { longPressFor = apt },
                                onInfo = { onOpenInfo(apt.id) },
                            )
                        }
                    }
                    if (visible.isEmpty()) {
                        Text(
                            "Домов пока нет. Нажмите «Добавить дом».",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { addHouseDialog = true }, modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp)); Text("Добавить дом")
                    }
                }
            } else {
                // Схема этажей: последний этаж вверху (п. 6)
                if (scheme.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("В подъезде нет этажей")
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        scheme.forEach { floor ->
                            FloorRow(
                                floor = floor,
                                filter = filter,
                                drag = drag,
                                onTileTap = { actionFor = it },
                                onTileLongPress = { longPressFor = it },
                                onInfoTap = { onOpenInfo(it.id) },
                                onRemoveFloor = { removeFloorFor = floor },
                                onDropMove = { aptId, targetFloor ->
                                    val apt = scheme.asSequence().flatMap { it.apartments.asSequence() }
                                        .firstOrNull { it.id == aptId }
                                    if (apt != null) pendingMove = apt to targetFloor
                                },
                            )
                        }
                        // Добавить этаж (п. 19)
                        TextButton(onClick = { onAddFloor(4) }, modifier = Modifier.padding(12.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp)); Text("Добавить этаж")
                        }
                    }
                }
            }
        }
    }

    // Диалог быстрого поиска дома по номеру
    if (searchDialog) {
        AlertDialog(
            onDismissRequest = { searchDialog = false },
            title = { Text("Найти дом") },
            text = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Номер дома") },
                    supportingText = { Text("Например: 25 или 25А") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { searchDialog = false }) { Text("Найти") }
            },
            dismissButton = {
                TextButton(onClick = { searchQuery = ""; searchDialog = false }) { Text("Очистить") }
            },
        )
    }

    // Диалог добавления дома (частный сектор)
    if (addHouseDialog) {
        var houseNumber by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addHouseDialog = false },
            title = { Text("Добавить дом") },
            text = {
                OutlinedTextField(
                    value = houseNumber,
                    onValueChange = { houseNumber = it },
                    label = { Text("Номер дома") },
                    supportingText = { Text("Можно с буквой, например 25А") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddHouse?.invoke(houseNumber) { err ->
                            if (err == null) addHouseDialog = false else editError = err
                        }
                    },
                    enabled = houseNumber.isNotBlank(),
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { addHouseDialog = false }) { Text("Отмена") } },
        )
    }

    // Ошибка редактирования квартиры (например, дубликат номера)
    editError?.let { err ->
        AlertDialog(
            onDismissRequest = { editError = null },
            title = { Text("Ошибка") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { editError = null }) { Text("OK") } },
        )
    }

    // Подтверждение перемещения — можно отменить до подтверждения (п. 11)
    pendingMove?.let { (apt, floorId) ->
        val targetFloor = scheme.firstOrNull { it.floor.id == floorId } ?: return@let
        AlertDialog(
            onDismissRequest = { pendingMove = null },
            title = { Text("Переместить квартиру?") },
            text = { Text("Квартира № ${apt.number} будет перемещена на ${targetFloor.floor.floorNumber} этаж. Номер, статус, телефон, комментарий и история сохранятся.") },
            confirmButton = {
                TextButton(onClick = {
                    onMove(apt.id, floorId) { err ->
                        pendingMove = null
                    }
                }) { Text("Переместить") }
            },
            dismissButton = { TextButton(onClick = { pendingMove = null }) { Text("Отмена") } },
        )
    }

    // Меню действий при обычном нажатии (п. 7) — Bottom Sheet: статусы крупно, до них легко дотянуться
    actionFor?.let { apt ->
        val noun = if (isPrivate) "Дом" else "Квартира"
        ModalBottomSheet(onDismissRequest = { actionFor = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "$noun № ${apt.label}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusCard(
                        label = "Заметка", color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Default.Edit, modifier = Modifier.weight(1f),
                    ) { noteFor = apt; actionFor = null }
                    StatusCard(
                        label = "Сброс", color = Color(0xFFF1F3F1),
                        contentColor = Color(0xFF37474F),
                        icon = Icons.Default.Close, modifier = Modifier.weight(1f),
                    ) { onResetStatus(apt.id); actionFor = null }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusCard(
                        label = "Отказ", color = statusColor(Status.REFUSED), contentColor = Color(0xFF37474F),
                        modifier = Modifier.weight(1f),
                    ) { onSetStatus(apt.id, Status.REFUSED, false); actionFor = null }
                    StatusCard(
                        label = "Нет дома", color = statusColor(Status.NO_ONE_HOME), contentColor = Color(0xFF37474F),
                        modifier = Modifier.weight(1f),
                    ) { onSetStatus(apt.id, Status.NO_ONE_HOME, false); actionFor = null }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusCard(
                        label = "Повтор", color = statusColor(Status.REPEAT), contentColor = Color(0xFF37474F),
                        modifier = Modifier.weight(1f),
                    ) { onSetStatus(apt.id, Status.REPEAT, false); actionFor = null }
                    StatusCard(
                        label = "Не жилая", color = statusColor(Status.NON_RESIDENTIAL), contentColor = Color(0xFF37474F),
                        modifier = Modifier.weight(1f),
                    ) { onSetStatus(apt.id, Status.NON_RESIDENTIAL, false); actionFor = null }
                }
                Spacer(Modifier.height(10.dp))
                // Грубый отказ (ярко-красный) и Ребёнок (голубой со значком) — в одном ряду
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusCard(
                        label = "Грубый отказ", color = Color(0xFFE53935), contentColor = Color.White,
                        modifier = Modifier.weight(1f),
                    ) { onSetStatus(apt.id, Status.ROUGH_REFUSED, false); actionFor = null }
                    StatusCard(
                        label = "Ребёнок", color = statusColor(Status.CHILD), contentColor = Color(0xFF0D47A1),
                        icon = Icons.Default.ChildCare,
                        modifier = Modifier.weight(1f),
                    ) { onSetStatus(apt.id, Status.CHILD, false); actionFor = null }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Диалог редактирования квартиры: этаж + номер (числовой или текстовый, «1А»)
    editFor?.let { apt ->
        ApartmentEditDialog(
            apartment = apt,
            floors = scheme.map { it.floor },
            isPrivate = isPrivate,
            onDismiss = { editFor = null },
            onSave = { floorId, numberText, recalc ->
                onEditApartment(apt.id, floorId, numberText, recalc) { err ->
                    editFor = null
                    editError = err
                }
            },
        )
    }

    // Меню долгого нажатия (пп. 11, 12 + редактирование квартиры) — Bottom Sheet
    longPressFor?.let { apt ->
        val noun = if (isPrivate) "Дом" else "Квартира"
        ModalBottomSheet(onDismissRequest = { longPressFor = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    "$noun № ${apt.label}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                SheetAction(
                    icon = Icons.Default.Edit,
                    label = if (isPrivate) "Изменить дом" else "Изменить квартиру",
                ) { editFor = apt; longPressFor = null }
                if (!isPrivate) {
                    SheetAction(icon = Icons.Default.MoveUp, label = "Переместить на этаж") {
                        moveFor = apt; longPressFor = null
                    }
                }
                SheetAction(icon = Icons.Default.Edit, label = "Заметка") {
                    noteFor = apt; longPressFor = null
                }
                SheetAction(
                    icon = Icons.Default.Delete,
                    label = if (isPrivate) "Удалить дом" else "Удалить квартиру",
                    contentColor = MaterialTheme.colorScheme.error,
                ) {
                    val deletedId = apt.id
                    val deletedLabel = apt.label
                    onDelete(deletedId)
                    longPressFor = null
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = (if (isPrivate) "Дом" else "Квартира") + " № $deletedLabel удалена",
                            actionLabel = "Отменить",
                            duration = androidx.compose.material3.SnackbarDuration.Long,
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            onRestore(deletedId)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Диалог выбора этажа при перемещении
    moveFor?.let { apt ->
        AlertDialog(
            onDismissRequest = { moveFor = null },
            title = { Text("Переместить № ${apt.number} на этаж") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    scheme.forEach { floor ->
                        OutlinedButton(onClick = { moveFor = null; pendingMove = apt to floor.floor.id }, modifier = Modifier.fillMaxWidth()) {
                            Text("${floor.floor.floorNumber} этаж (${floor.apartments.size} кв.)")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveFor = null }) { Text("Отмена") } },
        )
    }

    // Форма заметки (п. 8)
    noteFor?.let { apt ->
        NoteDialog(
            apartment = apt,
            isPrivate = isPrivate,
            onDismiss = { noteFor = null },
            onSave = { phone, comment -> onSaveNote(apt.id, phone, comment); noteFor = null },
        )
    }

    // Удаление этажа (п. 19)
    removeFloorFor?.let { floor ->
        FloorRemoveDialog(
            floor = floor,
            onDismiss = { removeFloorFor = null },
            onConfirmDelete = {
                onRemoveFloor(floor.floor.id) { }
                removeFloorFor = null
            },
        )
    }
}

/** Крупная статусная карточка в Bottom Sheet (цвет фона = цвет статуса). */
@Composable
private fun StatusCard(
    label: String,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick,
        color = color,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        interactionSource = interaction,
        modifier = modifier.height(64.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Строка действия в Bottom Sheet. */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = contentColor)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = contentColor)
    }
}

/** Строка одного этажа с плитками квартир. */
@Composable
private fun FloorRow(
    floor: FloorWithApartments,
    filter: Filter,
    drag: DragState,
    onTileTap: (ApartmentUi) -> Unit,
    onTileLongPress: (ApartmentUi) -> Unit,
    onInfoTap: (ApartmentUi) -> Unit,
    onRemoveFloor: () -> Unit,
    onDropMove: (Long, Long) -> Unit,
) {
    fun passes(apt: ApartmentUi): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.REPEAT -> apt.status == Status.REPEAT
        Filter.NOT_VISITED -> !apt.hasVisits
        Filter.REFUSED -> apt.status == Status.REFUSED
        Filter.NO_ONE_HOME -> apt.status == Status.NO_ONE_HOME
        Filter.NON_RESIDENTIAL -> apt.status == Status.NON_RESIDENTIAL
        Filter.ROUGH_REFUSED -> apt.status == Status.ROUGH_REFUSED
        Filter.CHILD -> apt.status == Status.CHILD
    }

    val hovered = drag.hoverFloorId == floor.floor.id && drag.draggingId != null

    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                drag.floorBounds[floor.floor.id] = coords.positionInRoot() to IntSize(coords.size.width, coords.size.height)
            }
            .background(if (hovered) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(1.dp, if (hovered) MaterialTheme.colorScheme.primary else Color.Transparent)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Номер этажа слева (п. 6) — компактно, чтобы влезало 6 плиток
        Row(Modifier.width(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${floor.floor.floorNumber}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        // Горизонтальный скролл при большом количестве квартир (п. 6)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val visible = floor.apartments.filter { passes(it) }
            if (visible.isEmpty()) {
                Text(
                    if (filter == Filter.ALL && floor.apartments.isEmpty()) "квартир нет"
                    else if (filter != Filter.ALL) "—" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            visible.forEach { apt ->
                ApartmentTile(
                    apartment = apt,
                    floorId = floor.floor.id,
                    dragging = drag.draggingId == apt.id,
                    drag = drag,
                    onTap = { onTileTap(apt) },
                    onLongPress = { onTileLongPress(apt) },
                    onInfo = { onInfoTap(apt) },
                    onDrop = { aptId, targetFloor -> onDropMove(aptId, targetFloor) },
                )
            }
        }
        // Удаление этажа доступно через «Редактировать подъезд» (место нужно для 6 плиток в ряд)
        Spacer(Modifier.width(6.dp))
    }
}



/** Плитка квартиры с цветовым статусом и иконкой «i» (пп. 6–8). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApartmentTile(
    apartment: ApartmentUi,
    floorId: Long,
    dragging: Boolean,
    drag: DragState,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onInfo: () -> Unit,
    onDrop: (Long, Long) -> Unit,
) {
    val color by animateColorAsState(statusColor(apartment.status), animationSpec = tween(250), label = "tileColor")
    var tileRoot by remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 44.dp)
            .background(color, RoundedCornerShape(12.dp))
            .border(
                width = if (dragging) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
            )
            .onGloballyPositioned { tileRoot = it.positionInRoot() }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = {
                    // Тактильное подтверждение входа в меню перемещения/удаления
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongPress()
                },
                onClick = onTap,
            )
            .pointerInput(apartment.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        drag.draggingId = apartment.id
                        drag.offset = Offset.Zero
                        drag.hoverFloorId = floorId
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.offset += amount
                        // Определяем этаж под пальцем по границам строк (корневые координаты)
                        val global = tileRoot + change.position
                        val target = drag.floorBounds.entries.firstOrNull { (_, bounds) ->
                            val (origin, size) = bounds
                            global.x >= origin.x && global.x <= origin.x + size.width &&
                                global.y >= origin.y && global.y <= origin.y + size.height
                        }?.key
                        if (target != null) drag.hoverFloorId = target
                    },
                    onDragEnd = {
                        val target = drag.hoverFloorId
                        drag.draggingId = null
                        drag.hoverFloorId = null
                        // Подтверждение перед перемещением — операцию можно отменить (п. 11)
                        if (target != null && target != floorId) onDrop(apartment.id, target)
                    },
                    onDragCancel = {
                        drag.draggingId = null
                        drag.hoverFloorId = null
                    },
                )
            },
    ) {
        // Телефон записан цифрами — маленькая неактивная трубка в кружке слева сверху
        val numericPhone = apartment.phone?.matches(Regex("[0-9()+-]{5,}")) == true
        if (numericPhone) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(16.dp)
                    .background(Color.White.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Есть телефон",
                    modifier = Modifier.size(11.dp),
                    tint = Color(0xFF1B5E20),
                )
            }
        }
        // Статус «Ребёнок» — логотип ребёнка в нижнем левом углу (крупный, заметный)
        if (apartment.status == Status.CHILD) {
            Icon(
                Icons.Default.ChildCare,
                contentDescription = "Ребёнок",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(20.dp)
                    .padding(1.dp),
                tint = Color(0xFF0D47A1),
            )
        }
        Text(
            apartment.label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum", // табулярные цифры — номера не «прыгают» по ширине
            ),
            // Номер всегда тёмный: фон плитки — светлый пастельный в обеих темах
            color = Color(0xFF263238),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 4.dp),
        )
        if (apartment.hasNote) {
            val iconInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Icon(
                Icons.Default.Info,
                contentDescription = "Есть заметка",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .clickable(
                        interactionSource = iconInteraction,
                        indication = null,
                        onClick = onInfo,
                    ),
                tint = Color(0xFF455A64),
            )
        }
    }
}

/** Панель статистики заменена сворачиваемым блоком в шапке экрана. */

/** Форма заметки: телефон + комментарий (п. 8). */
@Composable
private fun NoteDialog(
    apartment: ApartmentUi?,
    isPrivate: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String?, String?) -> Unit,
) {
    var phone by remember { mutableStateOf(apartment?.phone ?: "") }
    var comment by remember { mutableStateOf(apartment?.comment ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPrivate) "Заметка — дом № ${apartment?.label ?: ""}" else "Заметка — квартира № ${apartment?.label ?: ""}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Телефон") },
                    supportingText = { Text("Поддерживается международный формат, например +7…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("Комментарий") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(phone.ifBlank { null }, comment.ifBlank { null })
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Плитка дома (частный сектор): те же статусы/заметки, без перемещения между этажами. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HouseTile(
    house: ApartmentUi,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onInfo: () -> Unit,
) {
    val color = statusColor(house.status)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .sizeIn(minWidth = 76.dp, minHeight = 52.dp)
            .background(color, MaterialTheme.shapes.medium)
            .border(1.dp, Color(0xFFBDBDBD), MaterialTheme.shapes.medium)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongPress,
                onClick = onTap,
            ),
    ) {
        val numericHousePhone = house.phone?.matches(Regex("[0-9()+-]{5,}")) == true
        if (numericHousePhone) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(16.dp)
                    .background(Color.White.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Есть телефон",
                    modifier = Modifier.size(11.dp),
                    tint = Color(0xFF1B5E20),
                )
            }
        }
        if (house.status == Status.CHILD) {
            Icon(
                Icons.Default.ChildCare,
                contentDescription = "Ребёнок",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(22.dp)
                    .padding(1.dp),
                tint = Color(0xFF0D47A1),
            )
        }
        Text(
            house.label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            // Номер всегда тёмный: фон плитки — светлый пастельный в обеих темах
            color = Color(0xFF263238),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
        )
        if (house.hasNote) {
            val iconInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Icon(
                Icons.Default.Info,
                contentDescription = "Есть заметка",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .clickable(
                        interactionSource = iconInteraction,
                        indication = null,
                        onClick = onInfo,
                    ),
                tint = Color(0xFF455A64),
            )
        }
    }
}

/**
 * Диалог «Изменить квартиру»: выбор этажа + новый номер (числовой или текстовый, например «1А»).
 * При сохранении спрашивается, пересчитывать ли все номера квартир подъезда:
 * «Да» — пересчитать, «Нет» — оставить как есть.
 */
@Composable
private fun ApartmentEditDialog(
    apartment: ApartmentUi,
    floors: List<ru.obkhod.app.data.db.FloorEntity>,
    isPrivate: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Long?, String, Boolean) -> Unit,
) {
    var numberText by remember { mutableStateOf(apartment.label) }
    var floorChoice by remember { mutableStateOf<Long?>(null) }
    var askRecalc by remember { mutableStateOf(false) }

    // Частный сектор: номер дома меняется сразу, без вопроса о пересчёте и без этажей
    if (isPrivate) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Дом № ${apartment.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = numberText,
                        onValueChange = { numberText = it },
                        label = { Text("Номер дома") },
                        supportingText = { Text("Можно текстовый номер, например 25А") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (numberText.isNotBlank()) onSave(null, numberText.trim(), false) },
                    enabled = numberText.isNotBlank(),
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        )
        return
    }

    if (askRecalc) {
        AlertDialog(
            onDismissRequest = { askRecalc = false },
            title = { Text("Пересчитать номера?") },
            text = { Text("Пересчитать все номера квартир подъезда заново снизу вверх?\n\n«Да» — номера пересчитаются, «Нет» — останутся как есть (изменится только эта квартира).") },
            confirmButton = {
                TextButton(onClick = { askRecalc = false; onSave(floorChoice, numberText.trim(), true) }) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = { askRecalc = false; onSave(floorChoice, numberText.trim(), false) }) { Text("Нет") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPrivate) "Дом № ${apartment.label}" else "Квартира № ${apartment.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = numberText,
                    onValueChange = { numberText = it },
                    label = { Text(if (isPrivate) "Номер дома" else "Номер квартиры") },
                    supportingText = { Text("Можно текстовый номер, например 1А") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Новый этаж (сейчас: ${apartment.floorNumber}):", style = MaterialTheme.typography.labelLarge)
                Column(
                    Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    floors.sortedByDescending { it.floorNumber }.forEach { floor ->
                        OutlinedButton(
                            onClick = { floorChoice = floor.id },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (floorChoice == floor.id) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Text("${floor.floorNumber} этаж")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (numberText.isNotBlank()) askRecalc = true },
                enabled = numberText.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Диалог удаления этажа: если есть квартиры — перенос или логическое удаление (п. 19). */
@Composable
private fun FloorRemoveDialog(
    floor: FloorWithApartments,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val hasApartments = floor.apartments.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить ${floor.floor.floorNumber} этаж?") },
        text = {
            if (hasApartments) {
                Text(
                    "На этаже есть квартиры: " + floor.apartments.joinToString(", ") { "№ ${it.number}" } +
                        ". Квартиры будут логически удалены из схемы (их данные и история сохранятся в разделе «Удалённые»).",
                )
            } else {
                Text("Этаж пуст и будет удалён.")
            }
        },
        confirmButton = { TextButton(onClick = onConfirmDelete) { Text("Удалить этаж", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
