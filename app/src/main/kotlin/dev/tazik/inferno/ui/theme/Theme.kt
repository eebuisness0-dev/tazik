package dev.tazik.inferno.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import dev.tazik.inferno.data.AppSettings
import dev.tazik.inferno.data.ContrastLevel
import dev.tazik.inferno.data.ThemeMode

val spaceXS = 4.dp
val spaceS = 8.dp
val spaceM = 16.dp
val spaceL = 24.dp
val spaceXL = 32.dp
val spaceXXL = 48.dp

val cardRadius = 20.dp
val sheetRadius = 32.dp
val dialogRadius = 48.dp
val minTargetDp = 48.dp
val fabSizeDp = 56.dp

val FALLBACK_SEED = Color(0xFF4F9153)
val LOGS_BG = Color(0xFF0B0F14)

// Pixel-подобные нейтральные палитры (тёмный и светлый варианты)
private fun pixelDark(oled: Boolean = false) = darkColorScheme(
    primary = Color(0xFFA6D89C),
    onPrimary = Color(0xFF103311),
    primaryContainer = Color(0xFF38593A),
    onPrimaryContainer = Color(0xFFC2F0B8),
    secondary = Color(0xFFB4CDB2),
    onSecondary = Color(0xFF203523),
    secondaryContainer = Color(0xFF354B39),
    onSecondaryContainer = Color(0xFFD0E9CC),
    tertiary = Color(0xFFA6D0D4),
    onTertiary = Color(0xFF0F3537),
    tertiaryContainer = Color(0xFF334B4E),
    onTertiaryContainer = Color(0xFFC2ECF0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = if (oled) Color.Black else Color(0xFF121417),
    onBackground = Color(0xFFE2E3E6),
    surface = if (oled) Color.Black else Color(0xFF121417),
    onSurface = Color(0xFFE2E3E6),
    surfaceVariant = Color(0xFF3F493F),
    onSurfaceVariant = Color(0xFFBFC9BE),
    outline = Color(0xFF899387),
    outlineVariant = Color(0xFF434942),
    surfaceDim = Color(0xFF121417),
    surfaceBright = Color(0xFF38393C),
    surfaceContainerLowest = if (oled) Color.Black else Color(0xFF0C0E11),
    surfaceContainerLow = Color(0xFF1A1D1F),
    surfaceContainer = Color(0xFF1E2124),
    surfaceContainerHigh = Color(0xFF282C2F),
    surfaceContainerHighest = Color(0xFF333739),
    inversePrimary = Color(0xFF0F8A44),
    inverseSurface = Color(0xFFE2E3E6),
    inverseOnSurface = Color(0xFF2F3133),
    scrim = Color.Black,
)

private fun pixelLight() = lightColorScheme(
    primary = Color(0xFF0F8A44),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8F5B0),
    onPrimaryContainer = Color(0xFF00230F),
    secondary = Color(0xFF3D6943),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC1F0BC),
    onSecondaryContainer = Color(0xFF01220B),
    tertiary = Color(0xFF2C6569),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFAEF0F0),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF9FAF7),
    onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFF9FAF7),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFDEE5D9),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717972),
    outlineVariant = Color(0xFFC1C9C0),
    surfaceDim = Color(0xFFDADBDB),
    surfaceBright = Color(0xFFF9FAF7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F5F1),
    surfaceContainer = Color(0xFFEDEFEB),
    surfaceContainerHigh = Color(0xFFE7EAE6),
    surfaceContainerHighest = Color(0xFFE1E4E0),
    inversePrimary = Color(0xFF8CD794),
    inverseSurface = Color(0xFF2F312F),
    inverseOnSurface = Color(0xFFF0F0EE),
    scrim = Color.Black,
)

data class LocalUiState(
    val haptics: Boolean = true,
)

val LocalUi = staticCompositionLocalOf { LocalUiState() }

fun mappingContrast(level: ContrastLevel): Contrast = when (level) {
    ContrastLevel.STANDARD -> Contrast.Default
    ContrastLevel.MEDIUM -> Contrast.Medium
    ContrastLevel.HIGH -> Contrast.High
}

@Composable
fun TazikTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val sysDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> sysDark
    }
    val seed = if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        val sys = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        Color(sys.primary.toArgb())
    } else {
        FALLBACK_SEED
    }
    val colorScheme = remember(seed, dark, settings.contrastLevel, settings.oledBlack) {
        if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
            dynamicColorScheme(
                seedColor = seed,
                isDark = dark,
                style = PaletteStyle.TonalSpot,
                contrastLevel = mappingContrast(settings.contrastLevel).value,
                isAmoled = settings.oledBlack && dark,
            )
        } else {
            if (dark) pixelDark(settings.oledBlack) else pixelLight()
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = tazikShapes(),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

fun tazikShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(cardRadius),
    medium = RoundedCornerShape(cardRadius),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(sheetRadius),
)
