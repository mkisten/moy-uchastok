package ru.obkhod.app.ui.edit

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.obkhod.app.data.repo.EntranceDraft
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.create.EntranceDraftBlock
import ru.obkhod.app.ui.viewModelFactory

class EntranceEditViewModel(app: Application, private val areaId: Long) : AndroidViewModel(app) {
    private val repo = AppContainer.entranceRepository

    val error = MutableStateFlow<String?>(null)
    val done = MutableStateFlow(false)

    fun save(draft: EntranceDraft) = viewModelScope.launch {
        val err = repo.addEntrance(areaId, draft)
        error.value = err
        if (err == null) done.value = true
    }
}

/**
 * Добавление подъезда в существующий участок (пп. 5, 19).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntranceScreen(areaId: Long, onDone: () -> Unit, onBack: () -> Unit) {
    val vm: EntranceEditViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "addEntrance$areaId",
        factory = viewModelFactory { EntranceEditViewModel(ru.obkhod.app.ui.AppContainer.context() as Application, areaId) },
    )
    val error by vm.error.collectAsState()
    val done by vm.done.collectAsState()
    var draft by remember { mutableStateOf(EntranceDraft()) }

    LaunchedEffect(done) { if (done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Добавить подъезд") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntranceDraftBlock(
                draft = draft,
                index = 0,
                canDelete = false,
                onUpdate = { draft = it },
                onDelete = {},
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { vm.save(draft) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp)); Text("Добавить подъезд")
            }
        }
    }
}

class EditEntranceViewModel(app: Application, private val entranceId: Long) : AndroidViewModel(app) {
    private val repo = AppContainer.entranceRepository

    val error = MutableStateFlow<String?>(null)
    val floors = MutableStateFlow(0)
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repo.observeEntrance(entranceId).collect { e -> if (e != null) { /* номер редактируется вручную */ } }
        }
        viewModelScope.launch {
            repo.observeFloors(entranceId).collect { floors.value = it.size }
        }
    }

    fun changeNumber(newNumber: Int) = viewModelScope.launch {
        error.value = repo.changeEntranceNumber(entranceId, newNumber)
    }

    fun addFloor(apartments: Int) = viewModelScope.launch {
        repo.addFloor(entranceId, apartments)
    }

    fun removeTopFloor() = viewModelScope.launch {
        val fs = repo.observeFloorsOnce(entranceId)
        val top = fs.maxByOrNull { it.floorNumber } ?: return@launch
        val ok = repo.removeFloor(top.id, deleteApartments = false)
        message.value = if (!ok) {
            "На этаже есть квартиры: выберите, что с ними сделать"
        } else null
    }
}

/**
 * Редактирование подъезда: номер, добавление/удаление этажей (п. 19).
 * Этажи с квартирами логически удаляются только после подтверждения пользователя.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntranceScreen(entranceId: Long, onDone: () -> Unit, onBack: () -> Unit) {
    val vm: EditEntranceViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "editEntrance$entranceId",
        factory = viewModelFactory { EditEntranceViewModel(ru.obkhod.app.ui.AppContainer.context() as Application, entranceId) },
    )
    val floors by vm.floors.collectAsState()
    val message by vm.message.collectAsState()
    var numberText by remember { mutableStateOf("") }
    var newFloorCount by remember { mutableStateOf("4") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактировать подъезд") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = numberText,
                onValueChange = { numberText = it },
                label = { Text("Новый номер подъезда") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { numberText.toIntOrNull()?.let { vm.changeNumber(it) } }) { Text("Применить") }
                },
            )
            Text("Этажей: $floors", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newFloorCount,
                    onValueChange = { newFloorCount = it },
                    label = { Text("Квартир на новом этаже") },
                    singleLine = true,
                    modifier = Modifier.width(200.dp),
                )
                Button(onClick = { vm.addFloor(newFloorCount.toIntOrNull() ?: 0) }) {
                    Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Добавить этаж")
                }
            }
            OutlinedButton(onClick = { vm.removeTopFloor() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp)); Text("Удалить верхний этаж")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Готово") }
        }
    }
}
