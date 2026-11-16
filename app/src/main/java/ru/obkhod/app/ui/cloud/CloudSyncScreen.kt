package ru.obkhod.app.ui.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.viewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран «Синхронизация» (пункт в настройках): совместный доступ к данным команды
 * через общую Google-таблицу (п. «Общий доступ» ТЗ-дополнение 2026-11-16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(onBack: () -> Unit) {
    val vm: CloudSyncViewModel = viewModel(factory = viewModelFactory {
        CloudSyncViewModel(AppContainer.context() as android.app.Application)
    })
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Синхронизация") },
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

            if (!state.configured) {
                Text(
                    "Для синхронизации нужно создать OAuth Client ID в Google Cloud Console " +
                        "и добавить его в gradle.properties как googleClientId=… (см. README).",
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!state.signedIn) {
                Text("Войдите через Google-аккаунт, чтобы создавать команды и подключаться к ним.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.startAuth(context as android.app.Activity) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Войти через Google")
                }
            } else if (state.teamId == null) {
                Text("Вы вошли как: ${state.email}", style = MaterialTheme.typography.bodyMedium)
                CreateTeamSection(vm)
                HorizontalDivider()
                JoinTeamSection(vm)
            } else {
                Text("Вы вошли как: ${state.email}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TeamInfoSection(state, vm)
                HorizontalDivider()
                MembersSection(state, vm)
                HorizontalDivider()
                OutlinedButton(
                    onClick = { vm.leaveTeam() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Отключиться от команды") }
            }
        }
    }
}

@Composable
private fun CreateTeamSection(vm: CloudSyncViewModel) {
    var name by remember { mutableStateOf("") }
    Text("Создать команду", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("Название команды") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { vm.createTeam(name) },
        enabled = name.isNotBlank() && !vm.state.value.busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Создать общую таблицу") }
    Text(
        "Таблица будет создана на вашем Google Диске. Добавьте участников по email — они увидят те же данные.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun JoinTeamSection(vm: CloudSyncViewModel) {
    var link by remember { mutableStateOf("") }
    Text("Присоединиться к команде", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = link, onValueChange = { link = it },
        label = { Text("Ссылка на таблицу или её ID") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { vm.join(link) },
        enabled = link.isNotBlank() && !vm.state.value.busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Подключиться") }
}

@Composable
private fun TeamInfoSection(state: CloudUiState, vm: CloudSyncViewModel) {
    val ctx = LocalContext.current
    Text(
        state.teamName ?: "Команда",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Text("Код команды: ${state.teamCode}", style = MaterialTheme.typography.bodyMedium)
    val lastSync = if (state.lastSyncAt > 0) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(state.lastSyncAt))
    } else "ещё не синхронизировано"
    Text("Последняя синхронизация: $lastSync", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.syncNow() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text(if (state.busy) "Синхронизация…" else "Синхронизировать")
        }
        OutlinedButton(onClick = {
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(state.shareUrl)).let {
                ctx.startActivity(it)
            }
        }) { Text("Открыть таблицу") }
    }
}

@Composable
private fun MembersSection(state: CloudUiState, vm: CloudSyncViewModel) {
    var newEmail by remember { mutableStateOf("") }
    Text("Участники", style = MaterialTheme.typography.titleMedium)
    state.members.forEach { (email, role) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(email, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (role) {
                        "owner" -> "владелец"
                        "writer" -> "редактор"
                        else -> role
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (role != "owner") {
                TextButton(onClick = { vm.removeMember(email) }) { Text("Убрать") }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = newEmail, onValueChange = { newEmail = it },
            label = { Text("Email участника") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { vm.addWriter(newEmail); newEmail = "" }, enabled = newEmail.contains("@")) {
            Text("Добавить")
        }
    }
    Text(
        "Редактор может ставить статусы, добавлять дома и заметки. Структуру (этажи, подъезды, номера) меняет только владелец команды.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
