package ru.obkhod.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Светлая тема по умолчанию (п. 20 ТЗ), тёмная — как дополнение.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F0D4),
    onPrimaryContainer = Color(0xFF00251B),
    secondary = Color(0xFF4B635B),
    tertiary = Color(0xFF3D6373),
    surface = Color(0xFFFBFDF9),
    background = Color(0xFFF6FAF6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DDAB6),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFB9F0D4),
    secondary = Color(0xFFB2CCC1),
    tertiary = Color(0xFFB4C9DB),
    surface = Color(0xFF1A1C1A),
    background = Color(0xFF121412),
)

/**
 * Типографика: крупные заголовки, читаемые номера, tabular-выравнивание цифр.
 * Шрифт системный — без бандла ttf, чтобы не раздувать APK.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun ObkhodTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Динамический цвет Material You (Android 12+) — палитра от обоев устройства
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
