package io.github.funnyhell.daymark.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.funnyhell.daymark.data.repo.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5FD0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF3E7C5A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7EBD4),
    onSecondaryContainer = Color(0xFF002112),
    tertiary = Color(0xFFB0602A),
    tertiaryContainer = Color(0xFFFFDCC6),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFF7F8FC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF0F7),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF002A75),
    primaryContainer = Color(0xFF0D44A8),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFABCFB8),
    onSecondary = Color(0xFF113725),
    secondaryContainer = Color(0xFF285039),
    onSecondaryContainer = Color(0xFFC7EBD4),
    tertiary = Color(0xFFFFB68A),
    tertiaryContainer = Color(0xFF6E3810),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1C1F26),
    surfaceContainerHigh = Color(0xFF262A31),
    error = Color(0xFFFFB4AB),
)

/** Rounded, roomy shapes in the spirit of One UI. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DaymarkTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}

/** Palette offered for habits and medications. Color is decorative; every state also has text or an icon. */
val ItemColors: List<Long> = listOf(
    0xFF2F5FD0, 0xFF3E7C5A, 0xFFB0602A, 0xFF8E44AD, 0xFFC0392B, 0xFF16A085, 0xFFD4A017, 0xFF5D6D7E,
)
