package bardisucks.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import bardisucks.core.LightMode
import bardisucks.core.LightState

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFC857),
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5B4300),
    onPrimaryContainer = Color(0xFFFFDF9E),
    secondary = Color(0xFFD5C4A1),
    onSecondary = Color(0xFF392F15),
    secondaryContainer = Color(0xFF50452A),
    onSecondaryContainer = Color(0xFFF2E0BB),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE6E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE6E1E6),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC9C5D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF47464F),
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF2A292E),
    surfaceContainerHighest = Color(0xFF353439),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6B5D3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5E0BB),
    onSecondaryContainer = Color(0xFF241A04),
    background = Color(0xFFFBF8F4),
    onBackground = Color(0xFF1D1B19),
    surface = Color(0xFFFBF8F4),
    onSurface = Color(0xFF1D1B19),
    surfaceVariant = Color(0xFFECE1CF),
    onSurfaceVariant = Color(0xFF4D4639),
    outline = Color(0xFF7F7667),
    outlineVariant = Color(0xFFD0C5B4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F1EB),
    surfaceContainer = Color(0xFFF0EBE5),
    surfaceContainerHigh = Color(0xFFEAE5DF),
    surfaceContainerHighest = Color(0xFFE4DFD9),
)

/** Wallpaper colours where the system offers them (Android 12+), the app's own amber palette elsewhere. */
@Composable
fun BardiTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private val WarmWhite = Color(0xFFFFB25B)
private val CoolWhite = Color(0xFFD6E6FF)

/** Roughly what a white lamp looks like at this temperature (0 = warmest, 100 = coolest). */
fun whiteColor(tempPercent: Int): Color = lerp(WarmWhite, CoolWhite, tempPercent.coerceIn(0, 100) / 100f)

/** Hue 0–360 and saturation 0–100 at full brightness. */
fun hueColor(hue: Int, saturation: Int): Color =
    Color.hsv(hue.coerceIn(0, 360).toFloat(), saturation.coerceIn(0, 100) / 100f, 1f)

/** The colour a lamp is showing, for tiles and thumbs (brightness is shown separately). */
fun LightState.displayColor(): Color = when (mode) {
    LightMode.COLOUR -> hueColor(colour.h, saturation)
    else -> whiteColor(tempPercent)
}
