package ru.obkhod.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.obkhod.app.backup.BackupManager
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.export.CsvExporter
import ru.obkhod.app.export.ExcelExporter
import ru.obkhod.app.export.ExportDataLoader
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.Routes

/**
 * Раздел «Экспорт и резервное копирование» + настройки (пп. 16–18, 20 ТЗ).
 * Файлы сохраняются через Storage Access Framework.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, exportAreaId: Long? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeSettings by ru.obkhod.app.ui.theme.ThemePrefs.flow(context)
        .collectAsState(initial = ru.obkhod.app.ui.theme.ThemeSettings())
    var status by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // --- SAF-лаунчеры ---
    val createXlsx = rememberCreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") { uri ->
        runExport(context, uri, areaId = exportAreaId, kind = ExportKind.XLSX) { status = it }
    }
    val createApartmentsCsv = rememberCreateDocument("text/csv") { uri ->
        runExport(context, uri, areaId = exportAreaId, kind = ExportKind.CSV_APARTMENTS) { status = it }
    }
    val createHistoryCsv = rememberCreateDocument("text/csv") { uri ->
        runExport(context, uri, areaId = exportAreaId, kind = ExportKind.CSV_HISTORY) { status = it }
    }
    val createBackup = rememberCreateDocument("application/zip") { uri ->
        scope.launch(Dispatchers.IO) {
            try {
                val manager = BackupManager(context, AppDatabase.get(context))
                context.contentResolver.openOutputStream(uri)?.use { manager.createBackup(it) }
                withContextMain { status = "Резервная копия создана" }
            } catch (e: Exception) {
                withContextMain { status = "Ошибка создания копии: ${e.message}" }
            }
        }
    }
    val openBackup = rememberOpenDocument("application/zip", "application/octet-stream") { uri ->
        pendingRestoreUri = uri
        showRestoreConfirm = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Экспорт и настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (exportAreaId != null) "Экспорт выбранного участка" else "Экспорт всех участков",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = { createXlsx.launch("obkhod_export.xlsx") }, modifier = Modifier.fillMaxWidth()) {
                Text("Экспорт в Excel (XLSX)")
            }
            Button(onClick = { createApartmentsCsv.launch("apartments.csv") }, modifier = Modifier.fillMaxWidth()) {
                Text("Экспорт CSV — квартиры")
            }
            Button(onClick = { createHistoryCsv.launch("history.csv") }, modifier = Modifier.fillMaxWidth()) {
                Text("Экспорт CSV — история")
            }

            HorizontalDivider()
            Text("Резервное копирование", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = {
                    val manager = BackupManager(context, AppDatabase.get(context))
                    createBackup.launch(manager.suggestedFileName())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Создать резервную копию") }
            Button(onClick = { openBackup.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Восстановить из резервной копии")
            }

            HorizontalDivider()
            Text("Настройки", style = MaterialTheme.typography.titleMedium)
            Text("Тема", style = MaterialTheme.typography.titleMedium)

            // Выбор режима темы (п. 20 ТЗ)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ru.obkhod.app.ui.theme.ThemeMode.SYSTEM to "Системная",
                    ru.obkhod.app.ui.theme.ThemeMode.LIGHT to "Светлая",
                    ru.obkhod.app.ui.theme.ThemeMode.DARK to "Тёмная",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = themeSettings.mode == mode,
                        onClick = { scope.launch { ru.obkhod.app.ui.theme.ThemePrefs.setMode(context, mode) } },
                        label = { Text(label) },
                    )
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Цвет из обоев", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Material You (Android 12+)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = themeSettings.dynamicColor,
                    onCheckedChange = { enabled ->
                        scope.launch { ru.obkhod.app.ui.theme.ThemePrefs.setDynamic(context, enabled) }
                    },
                )
            }

            HorizontalDivider()
            Button(onClick = { }, modifier = Modifier.fillMaxWidth(), enabled = false) {
                Text("Удалённые квартиры — доступны из экрана участка")
            }
            HorizontalDivider()
            Text("О приложении", style = MaterialTheme.typography.titleMedium)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Мой участок",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Учёт обходов жилых домов, подъездов и квартир, а также частного сектора.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Версия: ${ru.obkhod.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Разработчик", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Максим Кистень", style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.Email,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("maximkisten@gmail.com", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.Phone,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("+375 29 838 00 01", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) { Text("Очистить все данные") }

            status?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // Предупреждение перед восстановлением (п. 18)
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false; pendingRestoreUri = null },
            title = { Text("Восстановление") },
            text = { Text("Восстановление заменит текущие данные приложения. Перед восстановлением будет создана временная резервная копия. Продолжить?") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    val uri = pendingRestoreUri
                    pendingRestoreUri = null
                    if (uri != null) {
                        scope.launch(Dispatchers.IO) {
                            val manager = BackupManager(context, AppDatabase.get(context))
                            val result = manager.restoreFrom(uri)
                            withContextMain {
                                status = when (result) {
                                    is BackupManager.RestoreResult.Success -> "Данные восстановлены"
                                    is BackupManager.RestoreResult.Error -> result.message
                                }
                            }
                        }
                    }
                }) { Text("Продолжить") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false; pendingRestoreUri = null }) { Text("Отмена") } },
        )
    }

    // Подтверждение очистки данных (п. 20)
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить все данные?") },
            text = { Text("Все участки, квартиры, заметки и история обходов будут удалены безвозвратно. Рекомендуем сначала создать резервную копию.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch { AppContainer.areaRepository.deleteAll(); status = "Данные очищены" }
                }) { Text("Удалить всё", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Отмена") } },
        )
    }
}

private enum class ExportKind { XLSX, CSV_APARTMENTS, CSV_HISTORY }

private fun runExport(context: Context, uri: Uri, areaId: Long?, kind: ExportKind, onStatus: (String) -> Unit) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            val db = AppDatabase.get(context)
            val data = ExportDataLoader(db).load(areaId?.let { listOf(it) })
            context.contentResolver.openOutputStream(uri)?.use { out ->
                when (kind) {
                    ExportKind.XLSX -> ExcelExporter().export(data, out)
                    ExportKind.CSV_APARTMENTS -> CsvExporter().exportApartments(data, out)
                    ExportKind.CSV_HISTORY -> CsvExporter().exportHistory(data, out)
                }
            }
            onStatusSafe(onStatus, "Экспорт завершён")
        } catch (e: Exception) {
            onStatusSafe(onStatus, "Ошибка экспорта: ${e.message}")
        }
    }
}

private fun onStatusSafe(onStatus: (String) -> Unit, message: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { onStatus(message) }
}

private fun withContextMain(block: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post(block)
}

@Composable
private fun rememberCreateDocument(mime: String, onResult: (Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> uri?.let(onResult) }

@Composable
private fun rememberOpenDocument(vararg mimes: String, onResult: (Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onResult) }
