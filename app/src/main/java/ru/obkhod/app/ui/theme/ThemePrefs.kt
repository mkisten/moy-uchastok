package ru.obkhod.app.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Режим темы: системная / светлая / тёмная (п. 20 ТЗ). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/** Хранение настроек темы в DataStore. */
object ThemePrefs {
    private val MODE_KEY = intPreferencesKey("theme_mode")
    private val DYNAMIC_KEY = booleanPreferencesKey("dynamic_color")

    fun flow(context: Context): Flow<ThemeSettings> =
        context.applicationContext.dataStore.data.map { prefs ->
            ThemeSettings(
                mode = ThemeMode.entries.getOrElse(prefs[MODE_KEY] ?: 0) { ThemeMode.SYSTEM },
                dynamicColor = prefs[DYNAMIC_KEY] ?: true,
            )
        }

    suspend fun setMode(context: Context, mode: ThemeMode) {
        context.applicationContext.dataStore.edit { it[MODE_KEY] = mode.ordinal }
    }

    suspend fun setDynamic(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[DYNAMIC_KEY] = enabled }
    }
}
