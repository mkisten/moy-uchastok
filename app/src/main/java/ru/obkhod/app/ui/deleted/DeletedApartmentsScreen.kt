package ru.obkhod.app.ui.deleted

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.viewModelFactory
import ru.obkhod.app.ui.apartment.formatDateTime
import ru.obkhod.app.ui.apartment.statusLabel

data class DeletedRow(
    val id: Long,
    val number: Int,
    val label: String,
    val statusLabel: String,
    val deletedAt: Long?,
    val canRestore: Boolean,
)

class DeletedViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppContainer.entranceRepository

    val rows: StateFlow<List<DeletedRow>> = combine(
        repo.observeDeletedApartments(),
        repo.observeAllFloors(),
        repo.observeAllActiveApartments(),
    ) { deleted, floors, active ->
        deleted.map { apt ->
            DeletedRow(
                id = apt.id,
                number = apt.apartmentNumber,
                label = apt.label ?: apt.apartmentNumber.toString(),
                statusLabel = statusLabel(ru.obkhod.app.data.db.Status.valueOf(apt.status)),
                deletedAt = apt.deletedAt,
                canRestore = floors.any { it.id == apt.floorId } &&
                    active.none { it.entranceId == apt.entranceId && it.apartmentNumber == apt.apartmentNumber },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun restore(id: Long, onResult: (Boolean) -> Unit) =
        viewModelScope.launch { onResult(repo.restoreApartment(id)) }
}

/** Раздел «Удалённые квартиры» (п. 12 ТЗ). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedApartmentsScreen(onBack: () -> Unit) {
    val vm: DeletedViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory { DeletedViewModel(AppContainer.context() as android.app.Application) })
    val rows by vm.rows.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Удалённые квартиры") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Удалённых квартир нет")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.id }) { row ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Квартира № ${row.label}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${row.statusLabel} · Удалено: ${formatDateTime(row.deletedAt)}" +
                                        if (!row.canRestore) " · Нельзя восстановить (этаж удалён или номер занят)" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (row.canRestore) {
                                TextButton(onClick = {
                                    vm.restore(row.id) { ok -> message = if (ok) "Квартира № ${row.label} восстановлена" else "Не удалось восстановить" }
                                }) {
                                    Icon(Icons.Default.Restore, contentDescription = null)
                                    Spacer(Modifier.width(4.dp)); Text("Восстановить")
                                }
                            }
                        }
                    }
                }
            }
        }
        message?.let {
            Snackbar(
                action = { TextButton(onClick = { message = null }) { Text("OK") } },
            ) { Text(it) }
        }
    }
}
