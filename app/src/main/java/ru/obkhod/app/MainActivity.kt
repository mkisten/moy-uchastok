package ru.obkhod.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.AppNavHost
import ru.obkhod.app.ui.Routes
import ru.obkhod.app.ui.theme.ObkhodTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppContainer.init(this)

        // Возврат к последнему открытому подъезду после перезапуска (п. 21 ТЗ).
        // Для частного сектора восстановление ведёт к списку участков: у него нет
        // «родительского» экрана подъездов, иначе «Назад» не возвращает к списку.
        val prefs = getSharedPreferences("nav_state", Context.MODE_PRIVATE)
        val lastEntrance = prefs.getLong("lastEntranceId", -1L)
        val lastArea = prefs.getLong("lastAreaId", -1L)
        val start = kotlinx.coroutines.runBlocking {
            if (lastEntrance > 0 && lastArea > 0) {
                val area = AppContainer.areaRepository.getById(lastArea)
                val isPrivate = area?.type == ru.obkhod.app.data.db.AreaType.PRIVATE.name
                if (isPrivate) Routes.AREAS else Routes.entrance(lastEntrance)
            } else {
                Routes.AREAS
            }
        }

        setContent {
            // Настройки темы из DataStore: режим + динамический цвет
            val themeSettings by remember {
                ru.obkhod.app.ui.theme.ThemePrefs.flow(this)
            }.collectAsState(initial = ru.obkhod.app.ui.theme.ThemeSettings())
            val darkTheme = when (themeSettings.mode) {
                ru.obkhod.app.ui.theme.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ru.obkhod.app.ui.theme.ThemeMode.LIGHT -> false
                ru.obkhod.app.ui.theme.ThemeMode.DARK -> true
            }
            ObkhodTheme(darkTheme = darkTheme, dynamicColor = themeSettings.dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    AppNavHost(nav = nav, app = application, startDestination = start)
                    androidx.compose.runtime.LaunchedEffect(nav) {
                        nav.currentBackStackEntryFlow.collect { entry ->
                            val entranceId = entry.arguments?.getLong("entranceId")
                            val areaId = entry.arguments?.getLong("areaId")
                            prefs.edit()
                                .apply {
                                    if (entranceId != null && entranceId > 0) putLong("lastEntranceId", entranceId)
                                    if (areaId != null && areaId > 0) putLong("lastAreaId", areaId)
                                }
                                .apply()
                        }
                    }
                }
            }
        }
    }
}
