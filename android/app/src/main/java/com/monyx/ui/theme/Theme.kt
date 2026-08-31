package com.monyx.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Green = Color(0xFF00875A)
private val GreenLight = Color(0xFF57C99A)
private val Ink = Color(0xFF14181F)
private val Sand = Color(0xFFF7F6F3)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEDF),
    onPrimaryContainer = Color(0xFF00291B),
    secondary = Color(0xFF4B635A),
    tertiary = Color(0xFF3F6375),
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEFEC),
    onSurfaceVariant = Color(0xFF52605A),
    outlineVariant = Color(0xFFDDE2DE),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFFCDEEDF),
    secondary = Color(0xFFB2CCC0),
    tertiary = Color(0xFFA6CBE0),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF181D1A),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outlineVariant = Color(0xFF3A423E),
    error = Color(0xFFF2B8B5),
)

/** Tabular figures matter on a screen that is mostly numbers. */
val MonyxTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 57.sp,
        lineHeight = 64.sp,
    ),
)

@Composable
fun MonyxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = MonyxTypography, content = content)
}
