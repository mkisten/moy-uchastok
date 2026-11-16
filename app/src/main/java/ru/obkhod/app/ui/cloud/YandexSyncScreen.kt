package ru.obkhod.app.ui.cloud

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.remote.WebDavClient
import ru.obkhod.app.sync.YandexSync
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.viewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class YandexUiState(
    val login: String = "",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncAt: Long = 0L,
)

class YandexSyncViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    private val prefs
        get() = getApplication<Application>().getSharedPreferences("yandex_sync", android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(YandexUiState())
    val state: StateFlow<YandexUiState> = _state

    init { refresh() }

    fun refresh() {
        val login = prefs.getString("login", null)
        _state.value = YandexUiState(
            login = login ?: "",
            connected = login != null,
            lastSyncAt = prefs.getLong("last_sync_at", 0L),
        )
    }

    private fun client(): WebDavClient {
        val login = prefs.getString("login", null)
            ?: throw IllegalStateException("Сначала введите логин и пароль приложения")
        return WebDavClient(
            server = "https://webdav.yandex.ru",
            login = login,
            password = prefs.getString("app_password", null) ?: "",
        )
    }

    fun connect(login: String, appPassword: String) {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Логин без домена: Яндекс WebDAV принимает только имя аккаунта
                val cleanLogin = login.trim()
                    .removeSuffix("@yandex.ru").removeSuffix("@ya.ru")
                    .removeSuffix("@yandex.by").removeSuffix("@yandex.com")
                // Проверка доступа: читаем корень диска
                WebDavClient("https://webdav.yandex.ru", cleanLogin, appPassword.trim()).get("/")
                prefs.edit()
                    .putString("login", cleanLogin)
                    .putString("app_password", appPassword.trim())
                    .apply()
                withMain { _state.value = _state.value.copy(busy = false, connected = true, login = cleanLogin, message = "Подключено к Яндекс Диску") }
                refresh()
            } catch (e: WebDavClient.HttpException) {
                withMain {
                    _state.value = _state.value.copy(
                        busy = false,
                        message = when (e.code) {
                            401 -> "Неверный логин или пароль. Важно: основной пароль Яндекса не подходит — нужен «пароль приложения»: id.yandex.ru → Безопасность → Пароли приложений → Создать → тип «Яндекс Диск»"
                            403 -> "Доступ запрещён: проверьте, что пароль приложения создан для Яндекс Диска"
                            else -> "Ошибка подключения (${e.code}). Проверьте интернет-соединение"
                        },
                    )
                }
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка подключения: проверьте интернет-соединение") }
            }
        }
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        refresh()
        _state.value = _state.value.copy(message = "Яндекс Диск отключён")
    }

    fun syncNow() {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sync = YandexSync(getApplication(), db, client())
                val result = sync.sync()
                prefs.edit().putLong("last_sync_at", System.currentTimeMillis()).apply()
                withMain { _state.value = _state.value.copy(busy = false,
                    message = "Синхронизировано: получено ${result.pulled} записей") }
                refresh()
            } catch (e: WebDavClient.HttpException) {
                withMain {
                    _state.value = _state.value.copy(
                        busy = false,
                        message = if (e.code == 401) {
                            "Доступ отклонён Яндексом (401): пароль приложения устарел или неверен. " +
                                "Создайте новый на id.yandex.ru → Безопасность → Пароли приложений, " +
                                "затем «Отключить» и подключитесь заново"
                        } else "Ошибка синхронизации (${e.code}): проверьте интернет-соединение",
                    )
                }
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка: ${e.message}") }
            }
        }
    }

    fun restoreFromCloud() {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sync = YandexSync(getApplication(), db, client())
                sync.restoreFromCloud()
                withMain { _state.value = _state.value.copy(busy = false, message = "Данные восстановлены из облака (локальные данные заменены)") }
                refresh()
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка: ${e.message}") }
            }
        }
    }

    private suspend fun withMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}

/**
 * Экран «Синхронизация (Яндекс Диск)»: общая папка «Мой участок» на Яндекс Диске,
 * вход по логину и паролю приложения (создаётся на id.yandex.ru → Безопасность).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YandexSyncScreen(onBack: () -> Unit) {
    val vm: YandexSyncViewModel = viewModel(factory = viewModelFactory {
        YandexSyncViewModel(AppContainer.context() as Application)
    })
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Яндекс Диск") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (!state.connected) {
                var login by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                Text("Вход", style = MaterialTheme.typography.titleMedium)
                // ВАЖНО: основной пароль Яндекса не подходит — только пароль приложения
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Как получить пароль:", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "1. Откройте id.yandex.ru → Безопасность\n" +
                                "2. «Пароли приложений» → «Создать новый»\n" +
                                "3. Тип: «Яндекс Диск»\n" +
                                "4. Вставьте сгенерированный пароль ниже\n\n" +
                                "Основной пароль Яндекса не подходит!",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedTextField(
                    value = login, onValueChange = { login = it },
                    label = { Text("Логин Яндекса (без @yandex.ru)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Пароль приложения") },
                    supportingText = { Text("Не основной пароль — именно из шага 2 выше") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.connect(login, password) },
                    enabled = login.isNotBlank() && password.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Подключить Яндекс Диск") }
            } else {
                Text("Аккаунт: ${state.login}", style = MaterialTheme.typography.bodyMedium)
                val lastSync = if (state.lastSyncAt > 0) {
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(state.lastSyncAt))
                } else "ещё не синхронизировано"
                Text(
                    "Папка: /Мой участок · Последняя синхронизация: $lastSync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { vm.syncNow() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (state.busy) "Синхронизация…" else "Синхронизировать") }

                HorizontalDivider()

                Text(
                    "Совместный доступ: на Яндекс Диске поделитесь папкой «Мой участок» " +
                        "с участниками (правый клик → Поделиться → email), и они, подключив свой аккаунт " +
                        "в этом приложении, будут работать с теми же данными.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.restoreFromCloud() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Восстановить данные из облака")
                }
                OutlinedButton(
                    onClick = { vm.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Отключить Яндекс Диск") }
            }
        }
    }
}
