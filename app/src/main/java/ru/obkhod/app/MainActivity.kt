package ru.obkhod.app

import android.content.Context
import android.content.Intent
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Redirect Google OAuth приходит сюда, когда активность уже открыта
        ru.obkhod.app.data.remote.GoogleAuth.handleRedirectIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppContainer.init(this)
        ru.obkhod.app.data.remote.GoogleAuth.init(this)
        ru.obkhod.app.data.remote.GoogleAuth.handleRedirectIntent(intent)

        // Возврат к последнему открытому подъезду после перезапуска (п. 21 ТЗ).
        // Стартуем всегда со списка участков, а восстановленный подъезд открывается
        // поверх него в LaunchedEffect — тогда «Назад» проходит всю иерархию:
        // подъезд → участок → список участков (раньше «Назад» закрывал приложение).
        val prefs = getSharedPreferences("nav_state", Context.MODE_PRIVATE)
        val restoreEntranceId = prefs.getLong("lastEntranceId", -1L)

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
                    AppNavHost(
                        nav = nav,
                        app = application,
                        startDestination = Routes.AREAS,
                        restoreEntranceId = restoreEntranceId,
                    )
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
