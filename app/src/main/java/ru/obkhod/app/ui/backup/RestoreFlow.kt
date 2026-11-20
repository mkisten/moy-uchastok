package ru.obkhod.app.ui.backup

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.obkhod.app.backup.BackupManager
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.ui.apartment.formatDateTime

class RestoreViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = BackupManager(app, AppDatabase.get(app))
    var hasSafetyCopy by mutableStateOf(manager.hasSafetyCopy())
        private set
    var pending by mutableStateOf<BackupManager.PreparedBackup?>(null)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<String?>(null)
        private set

    fun prepareZip(uri: Uri) = prepare { manager.prepareZip(uri) }
    fun prepareSafetyCopy() = prepare { manager.prepareSafetyCopy() }
    fun refreshSafetyCopy() { hasSafetyCopy = manager.hasSafetyCopy() }

    fun prepare(load: suspend () -> BackupManager.PreparedBackup) {
        run("Проверяем резервную копию…") { pending = withContext(Dispatchers.IO) { load() } }
    }

    fun confirm() {
        val backup = pending ?: return
        pending = null
        run("Создаём страховочную копию и восстанавливаем данные…") {
            withContext(Dispatchers.IO) { manager.restore(backup) }
            result = "Данные восстановлены. Предыдущие данные можно вернуть из страховочной копии в настройках."
        }
    }

    fun cancel() { pending = null }
    fun dismissResult() { result = null }

    private fun run(label: String, action: suspend () -> Unit) {
        if (progress != null) return
        progress = label
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = "Восстановление не выполнено. ${e.message ?: "Неизвестная ошибка"}"
            } finally {
                refreshSafetyCopy()
                progress = null
            }
        }
    }
}

@Composable
fun RestoreDialogs(vm: RestoreViewModel) {
    val progress = vm.progress
    BackHandler(enabled = progress != null) {}
    if (progress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(progress) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
    }
    vm.pending?.let { backup ->
        AlertDialog(
            onDismissRequest = vm::cancel,
            title = { Text("Заменить данные из резервной копии?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Дата копии: ${backup.createdAt?.let(::formatDateTime) ?: "не указана"}")
                    Text("Участки: ${backup.areaCount} · Подъезды: ${backup.entranceCount}")
                    Text("Квартиры и дома: ${backup.activeCount} · Удалённые: ${backup.deletedCount}")
                    Text("Записи истории: ${backup.historyCount}")
                    Text("Текущие данные будут полностью заменены. Перед заменой приложение сохранит их в страховочную копию.")
                }
            },
            confirmButton = { TextButton(onClick = vm::confirm) { Text("Заменить данные") } },
            dismissButton = { TextButton(onClick = vm::cancel) { Text("Отмена") } },
        )
    }
    vm.result?.let { result ->
        AlertDialog(
            onDismissRequest = vm::dismissResult,
            text = { Text(result) },
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text("Понятно") } },
        )
    }
}
