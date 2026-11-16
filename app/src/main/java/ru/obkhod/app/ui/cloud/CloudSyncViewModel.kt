package ru.obkhod.app.ui.cloud

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.remote.GoogleAuth
import ru.obkhod.app.data.remote.TeamApi
import ru.obkhod.app.sync.SheetSync

data class CloudUiState(
    val configured: Boolean = true,
    val signedIn: Boolean = false,
    val email: String? = null,
    val teamId: String? = null,
    val teamName: String? = null,
    val teamCode: String? = null,
    val shareUrl: String? = null,
    val members: List<Pair<String, String>> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncAt: Long = 0L,
)

class CloudSyncViewModel(app: Application) : AndroidViewModel(app) {

    private val api = TeamApi(app)
    private val db = AppDatabase.get(app)
    // Yandex auto-sync uses AppContainer.db() from AppNavHost

    private val _state = MutableStateFlow(CloudUiState())
    val state: StateFlow<CloudUiState> = _state

    init {
        GoogleAuth.init(app)
        refreshState()
        // Реагируем на завершение OAuth (может вернуться, когда открыт другой экран)
        viewModelScope.launch {
            GoogleAuth.email.collect {
                refreshState()
                GoogleAuth.authMessage.value?.let { msg ->
                    _state.value = _state.value.copy(message = msg)
                    GoogleAuth.clearAuthMessage()
                }
            }
        }
    }

    fun refreshState() {
        val teamId = api.currentTeamId()
        _state.value = _state.value.copy(
            configured = GoogleAuth.isConfigured(),
            signedIn = GoogleAuth.isSignedIn(),
            email = GoogleAuth.email.value,
            teamId = teamId,
            teamName = api.teamName(),
            teamCode = api.teamCode(),
            shareUrl = teamId?.let { "https://docs.google.com/spreadsheets/d/$it/edit" },
            lastSyncAt = prefs().getLong("last_sync_at", 0L),
        )
        if (teamId != null && GoogleAuth.isSignedIn()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val members = api.permissions().map { it.second to it.third }
                    _state.value = _state.value.copy(members = members)
                } catch (_: Exception) { /* офлайн — пропускаем */ }
            }
        }
    }

    private fun prefs(): android.content.SharedPreferences = getApplication<Application>()
        .getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    fun startAuth(activity: android.app.Activity) {
        if (!GoogleAuth.isConfigured()) {
            _state.value = _state.value.copy(message = "Google Client ID не настроен: добавьте googleClientId в gradle.properties (см. README)")
            return
        }
        GoogleAuth.startAuth(activity)
    }

    /** OAuth завершается глобально в GoogleAuth; экран наблюдает за state. */
    fun onAuthRedirect(uri: android.net.Uri) = Unit

    fun createTeam(name: String) {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = api.createTeam(name)
                // Первичная выгрузка текущих данных
                val sync = SheetSync.create(getApplication(), db, api)
                val result = sync.sync()
                withMain { _state.value = _state.value.copy(busy = false,
                    message = "Команда «${info.teamName}» создана. Выгружено записей: ${result.pushed}") }
                refreshState()
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка: ${e.message}") }
            }
        }
    }

    fun join(linkOrId: String) {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = api.join(linkOrId)
                val sync = SheetSync.create(getApplication(), db, api)
                val result = sync.sync()
                withMain { _state.value = _state.value.copy(busy = false,
                    message = "Подключено к «${info.teamName}». Загружено записей: ${result.pulled}") }
                refreshState()
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка: ${e.message}") }
            }
        }
    }

    fun syncNow() {
        if (api.currentTeamId() == null || !GoogleAuth.isSignedIn()) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sync = SheetSync.create(getApplication(), db, api)
                val result = sync.sync()
                prefs().edit().putLong("last_sync_at", System.currentTimeMillis()).apply()
                withMain { _state.value = _state.value.copy(busy = false,
                    message = "Синхронизировано: получено ${result.pulled}, отправлено ${result.pushed}") }
                refreshState()
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка синхронизации: ${e.message}") }
            }
        }
    }

    fun addWriter(email: String) {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                api.addWriter(email)
                withMain { _state.value = _state.value.copy(busy = false, message = "Приглашение отправлено: $email") }
                refreshState()
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка: ${e.message}") }
            }
        }
    }

    fun removeMember(email: String) {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val permId = api.findPermissionId(email)
                if (permId != null) api.removeMember(permId)
                withMain { _state.value = _state.value.copy(busy = false, message = "Доступ отозван: $email") }
                refreshState()
            } catch (e: Exception) {
                withMain { _state.value = _state.value.copy(busy = false, message = "Ошибка: ${e.message}") }
            }
        }
    }

    fun leaveTeam() {
        api.forgetTeam()
        refreshState()
        _state.value = _state.value.copy(message = "Команда отключена (таблица Google осталась без изменений)")
    }

    private suspend fun withMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}
