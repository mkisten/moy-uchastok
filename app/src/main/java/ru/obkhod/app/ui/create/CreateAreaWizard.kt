package ru.obkhod.app.ui.create

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.obkhod.app.data.repo.CreateAreaResult
import ru.obkhod.app.data.repo.EntranceDraft

/**
 * Мастер создания участка: название → настройка подъездов (п. 4 ТЗ).
 * Подъезд задаётся параметрами «этажей × квартир на этаже» + произвольным номером подъезда
 * и начальным номером квартиры.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAreaWizardScreen(
    onCreate: (String, List<EntranceDraft>, ru.obkhod.app.data.db.AreaType, (CreateAreaResult) -> Unit) -> Unit,
    onDone: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var step by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf(listOf(EntranceDraft())) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val type = if (isPrivate) ru.obkhod.app.data.db.AreaType.PRIVATE else ru.obkhod.app.data.db.AreaType.MULTI

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новый участок") },
                navigationIcon = {
                    IconButton(onClick = { if (step == 2) step = 1 else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (step == 1) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Шаг 1 из 2 — Тип и название", style = MaterialTheme.typography.titleMedium)
                    // Выбор типа участка: частный сектор или многоквартирный дом
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = !isPrivate,
                            onClick = { isPrivate = false },
                            label = { Text("Многоквартирный дом") },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = isPrivate,
                            onClick = { isPrivate = true },
                            label = { Text("Частный сектор") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(if (isPrivate) "Улица (название участка)" else "Название участка") },
                        supportingText = {
                            Text(if (isPrivate) "Например: ул. Полевая" else "Например: ул. Советская, дом 15")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isPrivate) {
                        Text(
                            "Дома будут добавляться внутри участка в свободном порядке: статусы, заметки, история — как у квартир.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (name.isBlank()) error = "Название обязательно для заполнения"
                            else if (isPrivate) {
                                // Частный сектор: подъезды не настраиваются
                                creating = true
                                onCreate(name, emptyList(), type) { result ->
                                    creating = false
                                    when (result) {
                                        is CreateAreaResult.Success -> onDone(result.areaId)
                                        is CreateAreaResult.Error -> error = result.message
                                    }
                                }
                            } else step = 2
                        },
                        enabled = !creating,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (isPrivate) "Создать участок" else "Далее", style = MaterialTheme.typography.titleMedium) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Шаг 2 из 2 — Подъезды", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        drafts.forEachIndexed { index, draft ->
                            EntranceDraftBlock(
                                draft = draft,
                                index = index,
                                canDelete = drafts.size > 1,
                                onUpdate = { updated -> drafts = drafts.toMutableList().also { it[index] = updated } },
                                onDelete = { drafts = drafts.filterIndexed { i, _ -> i != index } },
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val next = (drafts.maxOfOrNull { it.number } ?: 0) + 1
                                // Пользователь при необходимости задаёт собственный начальный номер (п. 4.2)
                                drafts = drafts + EntranceDraft(number = next, startNumber = 1)
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp)); Text("Добавить подъезд")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            creating = true
                            onCreate(name, drafts.map { it.copy(apartmentsPerFloor = it.apartmentsPerFloor.toMutableList()) }, type) { result ->
                                creating = false
                                when (result) {
                                    is CreateAreaResult.Success -> onDone(result.areaId)
                                    is CreateAreaResult.Error -> error = result.message
                                }
                            }
                        },
                        enabled = !creating,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Создать участок", style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * Блок настроек одного подъезда (п. 4.2, уточнение пользователя 2026-11-13):
 * номер подъезда задаётся вручную; этажность × количество квартир на этаже
 * формируют плитки квартир одинаково на всех этажах.
 */
@Composable
internal fun EntranceDraftBlock(
    draft: EntranceDraft,
    index: Int,
    canDelete: Boolean,
    onUpdate: (EntranceDraft) -> Unit,
    onDelete: () -> Unit,
) {
    // Локальные состояния ввода — свободное редактирование без «прилипания» цифр
    var numberText by remember(draft.number) { mutableStateOf(draft.number.toString()) }
    var floorsText by remember(draft.floorCount) { mutableStateOf(draft.floorCount.toString()) }
    var perFloorText by remember(draft.floorCount, draft.apartmentsPerFloor) {
        mutableStateOf(draft.apartmentsPerFloor.firstOrNull()?.toString() ?: "4")
    }
    var startText by remember(draft.startNumber) { mutableStateOf(draft.startNumber.toString()) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Подъезд ${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
            }
            FreeIntField("Номер подъезда", numberText, { numberText = it }) { v ->
                numberText = v.toString(); onUpdate(draft.copy(number = v))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeIntField("Этажей", floorsText, { floorsText = it }, Modifier.weight(1f), 1..60) { v ->
                    floorsText = v.toString()
                    val per = List(v) { perFloorText.toIntOrNull() ?: 4 }
                    onUpdate(draft.copy(floorCount = v, apartmentsPerFloor = per.toMutableList()))
                }
                FreeIntField("Квартир на этаже", perFloorText, { perFloorText = it }, Modifier.weight(1f), 0..20) { v ->
                    perFloorText = v.toString()
                    val per = List(draft.floorCount) { v }
                    onUpdate(draft.copy(apartmentsPerFloor = per.toMutableList()))
                }
            }
            FreeIntField("Начальный № квартиры", startText, { startText = it }) { v ->
                startText = v.toString(); onUpdate(draft.copy(startNumber = v))
            }
            val total = draft.apartmentsPerFloor.sum()
            val lastNumber = draft.startNumber + total - 1
            Text(
                "Всего квартир: $total · Последний номер: ${if (total > 0) lastNumber else "—"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Поле ввода целого числа со свободным редактированием: можно стереть значение
 * целиком и набрать новое; в модель уходит только корректное значение из диапазона.
 */
@Composable
private fun FreeIntField(
    label: String,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 1..99999,
    onValid: (Int) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            val filtered = s.filter { it.isDigit() }
            onTextChange(filtered)
            filtered.toIntOrNull()?.let { v -> if (v in range) onValid(v) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
