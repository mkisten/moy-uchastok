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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.obkhod.app.data.db.AreaType
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.viewModelFactory
import ru.obkhod.app.ui.apartment.formatDateTime
import ru.obkhod.app.ui.apartment.statusLabel

class DeletedViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppContainer.entranceRepository

    val rows = repo.observeDeletedApartments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun restore(id: Long, onResult: (Boolean) -> Unit) =
        viewModelScope.launch { onResult(repo.restoreApartment(id)) }
}

/** Раздел «Удалённые квартиры» (п. 12 ТЗ). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedApartmentsScreen(onBack: () -> Unit) {
    val vm: DeletedViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory { DeletedViewModel(AppContainer.context() as android.app.Application) })
    val rows by vm.rows.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Удалённые") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Удалённых квартир и домов нет")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.id }) { row ->
                    val isPrivate = row.areaType == AreaType.PRIVATE.name
                    val title = "${if (isPrivate) "Дом" else "Квартира"} № ${row.label}"
                    val blockedReason = when {
                        row.floorNumber == null -> "Исходный этаж удалён"
                        row.numberTaken -> "Номер уже занят"
                        else -> null
                    }
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text(row.areaName, style = MaterialTheme.typography.bodyMedium)
                                if (!isPrivate) {
                                    val floor = row.floorNumber?.let { " · Этаж $it" }.orEmpty()
                                    Text("Подъезд № ${row.entranceNumber}$floor", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "${statusLabel(Status.valueOf(row.status))} · Удалено: ${formatDateTime(row.deletedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                blockedReason?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (blockedReason == null) {
                                TextButton(onClick = {
                                    vm.restore(row.id) { ok ->
                                        scope.launch {
                                            snackbar.showSnackbar(if (ok) "Восстановлено: $title" else "Не удалось восстановить: $title")
                                        }
                                    }
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
    }
}
