package ru.obkhod.app.ui.cloud

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import ru.obkhod.app.data.remote.YandexAuth
import ru.obkhod.app.sync.YandexSync
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.viewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class YandexUiState(
    val configured: Boolean = true,
    val connected: Boolean = false,
    val login: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncAt: Long = 0L,
    val showPasswordForm: Boolean = false,
)

class YandexSyncViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    private val prefs
        get() = getApplication<Application>().getSharedPreferences("yandex_sync", android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(YandexUiState())
    val state: StateFlow<YandexUiState> = _state

    init {
        YandexAuth.init(app)
        refresh()
        // Реагируем на завершение OAuth-входа; после успешного входа сразу синхронизируем
        viewModelScope.launch {
            YandexAuth.login.collect { _ ->
                val signedIn = YandexAuth.isSignedIn()
                _state.value = _state.value.copy(
                    connected = signedIn,
                    login = YandexAuth.login.value ?: "",
                )
                YandexAuth.message.value?.let { msg ->
                    _state.value = _state.value.copy(message = msg)
                    YandexAuth.clearMessage()
                }
                if (signedIn) syncNow()
            }
        }
    }

    fun refresh() {
        val oauthSigned = YandexAuth.isSignedIn()
        val davLogin = prefs.getString("login", null)
        _state.value = _state.value.copy(
            configured = YandexAuth.isConfigured(),
            connected = oauthSigned || davLogin != null,
            login = YandexAuth.login.value ?: davLogin ?: "",
            lastSyncAt = prefs.getLong("last_sync_at", 0L),
        )
    }

    private fun client(): WebDavClient =
        if (YandexAuth.isSignedIn()) {
            WebDavClient.oauth("https://webdav.yandex.ru", YandexAuth.accessToken())
        } else {
            val login = prefs.getString("login", null)
                ?: throw IllegalStateException("Сначала подключите Яндекс Диск")
            WebDavClient.basic(
                "https://webdav.yandex.ru", login,
                prefs.getString("app_password", null) ?: "",
            )
        }

    fun openYandexLogin(context: android.content.Context) {
        if (!YandexAuth.isConfigured()) {
            _state.value = _state.value.copy(
                message = "Вход через Яндекс ID ещё не настроен в этой сборке — используйте пароль приложения ниже",
            )
            return
        }
        YandexAuth.openLoginPage(context)
        _state.value = _state.value.copy(message = "Подтвердите вход в Яндексе и вставьте код подтверждения сюда")
    }

    fun exchangeCode(code: String) {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            val login = YandexAuth.exchangeCode(code)
            withMain {
                _state.value = _state.value.copy(
                    busy = false,
                    message = if (login != null) "Вход выполнен: $login" else null,
                )
                refresh()
            }
        }
    }

    fun connectByPassword(login: String, appPassword: String) {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val clean = login.trim().removeSuffix("@yandex.ru").removeSuffix("@ya.ru")
                WebDavClient.basic("https://webdav.yandex.ru", clean, appPassword.trim()).get("/")
                prefs.edit().putString("login", clean).putString("app_password", appPassword.trim()).apply()
                withMain { _state.value = _state.value.copy(busy = false, message = "Подключено к Яндекс Диску") }
                refresh()
            } catch (e: WebDavClient.HttpException) {
                withMain {
                    _state.value = _state.value.copy(
                        busy = false,
                        message = if (e.code == 401) {
                            "Неверный логин или пароль. Основной пароль Яндекса не подходит — нужен «пароль приложения»: " +
                                "id.yandex.ru → Безопасность → Пароли приложений → тип «Яндекс Диск»"
                        } else "Ошибка подключения (${e.code})",
                    )
                }
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка подключения: проверьте интернет") }
            }
        }
    }

    fun syncNow() {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sync = YandexSync(getApplication(), db, client())
                val result = sync.sync()
                prefs.edit().putLong("last_sync_at", System.currentTimeMillis()).apply()
                withMain {
                    _state.value = _state.value.copy(busy = false,
                        message = "Синхронизировано: отправлено ${result.pushed}, получено ${result.pulled}")
                }
                refresh()
            } catch (e: WebDavClient.HttpException) {
                withMain {
                    _state.value = _state.value.copy(busy = false,
                        message = if (e.code == 401) "Доступ отклонён (401): войдите через Яндекс заново"
                        else "Ошибка синхронизации (${e.code}): проверьте интернет")
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
                YandexSync(getApplication(), db, client()).restoreFromCloud()
                withMain { _state.value = _state.value.copy(busy = false, message = "Данные восстановлены из облака") }
                refresh()
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка: ${e.message}") }
            }
        }
    }

    fun disconnect() {
        YandexAuth.signOut()
        prefs.edit().clear().apply()
        refresh()
        _state.value = _state.value.copy(message = "Яндекс Диск отключён")
    }

    fun togglePasswordForm() {
        _state.value = _state.value.copy(showPasswordForm = !_state.value.showPasswordForm)
    }

    private suspend fun withMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}

/**
 * Экран «Синхронизация (Яндекс Диск)»: вход через Яндекс ID (подтверждение в приложении
 * Яндекса / по телефону) либо по паролю приложения. Общая папка «Мой участок».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YandexSyncScreen(onBack: () -> Unit) {
    val vm: YandexSyncViewModel = viewModel(factory = viewModelFactory {
        YandexSyncViewModel(AppContainer.context() as Application)
    })
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current

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

            if (state.connected) {
                Text("Аккаунт: ${state.login}", style = MaterialTheme.typography.bodyMedium)
                val lastSync = if (state.lastSyncAt > 0) {
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(state.lastSyncAt))
                } else "ещё не синхронизировано"
                Text(
                    "Папка: /Мой участок · Последняя синхронизация: $lastSync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { vm.syncNow() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (state.busy) "Синхронизация…" else "Синхронизировать")
                }
                HorizontalDivider()
                Text(
                    "Совместный доступ: поделитесь папкой «Мой участок» с участниками по их email " +
                        "(на disk.yandex.ru → папка → Поделиться). Участники подключают свой аккаунт " +
                        "Яндекса в этом приложении и синхронизируются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    val ctx = LocalContext.current
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://disk.yandex.ru/client/%D0%9C%D0%BE%D0%B9%20%D1%83%D1%87%D0%B0%D1%81%D1%82%D0%BE%D0%BA"),
                        )
                    )
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть папку на Яндекс Диске (поделиться)")
                }
                OutlinedButton(onClick = { vm.restoreFromCloud() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Восстановить данные из облака")
                }
                OutlinedButton(
                    onClick = { vm.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Отключить Яндекс Диск") }
            } else {
                Text("Совместный доступ к данным через общую папку Яндекс Диска", style = MaterialTheme.typography.bodyMedium)

                // Основной вход — через Яндекс ID (подтверждение в приложении/по телефону)
                Button(
                    onClick = { vm.openYandexLogin(ctx) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Войти через Яндекс", fontWeight = FontWeight.SemiBold) }

                var code by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Код подтверждения (если Яндекс его показал)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.exchangeCode(code); code = "" },
                    enabled = code.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Подтвердить код") }

                HorizontalDivider()

                // Запасной вход — пароль приложения
                if (!state.showPasswordForm) {
                    OutlinedButton(onClick = { vm.togglePasswordForm() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Войти по паролю приложения")
                    }
                } else {
                    var login by remember { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    Text("Вход по паролю приложения", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = login, onValueChange = { login = it },
                        label = { Text("Логин Яндекса (без @yandex.ru)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Пароль приложения") },
                        supportingText = { Text("id.yandex.ru → Безопасность → Пароли приложений → тип «Яндекс Диск»") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.connectByPassword(login, password) },
                        enabled = login.isNotBlank() && password.isNotBlank() && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Подключить") }
                }
            }
        }
    }
}
