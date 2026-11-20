package ru.obkhod.app.ui.cloud

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.sync.TeamSync
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.viewModelFactory

internal class TeamSyncViewModel(app: Application) : AndroidViewModel(app) {
    private val sync = TeamSync(app, AppDatabase.get(app))
    var connected by mutableStateOf(false)
        private set
    var awaitingJoin by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set
    var pending by mutableStateOf<Int?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf(false)
        private set

    init { run { null } }

    fun join(url: String, code: String, seed: Boolean) = run {
        sync.connect(url.trim(), code.trim(), seed).summary()
    }

    fun synchronize() = run { sync.sync().summary() }

    fun disconnect() = run {
        sync.disconnect()
        "Устройство отключено. Данные остались на телефоне."
    }

    private fun run(action: suspend () -> String?) {
        if (busy) return
        busy = true
        error = false
        message = null
        viewModelScope.launch {
            try {
                message = withContext(Dispatchers.IO) { action() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = true
                message = e.message ?: "Не удалось выполнить синхронизацию"
            } finally {
                try {
                    val link = withContext(Dispatchers.IO) { sync.connection() }
                    connected = link != null
                    awaitingJoin = link?.invite != null
                    pending = if (link != null && !awaitingJoin) withContext(Dispatchers.IO) { sync.pendingCount() } else null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    pending = null
                } finally {
                    initialized = true
                    busy = false
                }
            }
        }
    }

    private fun TeamSync.Result.summary() =
        "Синхронизация завершена. Отправлено: $sent · Получено: $received"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamSyncScreen(onBack: () -> Unit) {
    val vm: TeamSyncViewModel = viewModel(factory = viewModelFactory {
        TeamSyncViewModel(AppContainer.context() as Application)
    })
    var url by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var seed by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    BackHandler(enabled = vm.busy) {}

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Синхронизация команды") },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = !vm.busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.message?.let {
                Text(it, color = if (vm.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            if (vm.initialized && !vm.connected) {
                Text("Попросите владельца команды прислать ссылку и одноразовый код.")
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Ссылка команды") },
                    singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = code, onValueChange = { code = it }, label = { Text("Код приглашения") },
                    singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = seed, onCheckedChange = { seed = it }, enabled = !vm.busy)
                    Text("Начать новую команду с данных этого телефона")
                }
                Text(
                    if (seed) "Нужен код владельца и пустая команда."
                    else "Для присоединения откройте этот экран на устройстве с пустой базой.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { vm.join(url, code, seed); code = "" },
                    enabled = !vm.busy && url.isNotBlank() && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Подключиться") }
            } else if (vm.connected) {
                Text(if (vm.awaitingJoin) "Подключение не завершено" else "Устройство подключено")
                if (!vm.awaitingJoin) {
                    vm.pending?.let { Text("Не отправлено операций: $it") }
                    Text("Изменения сохраняются на телефоне даже без интернета.")
                }
                Button(onClick = vm::synchronize, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.awaitingJoin) "Повторить подключение" else "Синхронизировать")
                }
                OutlinedButton(
                    onClick = { confirmDisconnect = true },
                    enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vm.awaitingJoin) "Отменить подключение" else "Отключить устройство") }
            }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Отключить устройство?") },
            text = { Text("Локальные записи останутся на телефоне. Для нового подключения понадобится новое приглашение.") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; vm.disconnect() }) { Text("Отключить") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Отмена") } },
        )
    }
}
