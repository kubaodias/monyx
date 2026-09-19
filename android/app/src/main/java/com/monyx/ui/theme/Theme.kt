package com.monyx.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Telnyx green. Bright enough that anything drawn ON it has to be dark — it
 * belongs to the platform the assistant runs on, so it is a literal rather
 * than a theme colour, and it stays itself in the dark theme.
 */
val TelnyxGreen = Color(0xFF00E3AA)
val OnTelnyxGreen = Color(0xFF10201B)

/**
 * Telnyx green for TEXT. The literal glows — right for a button face, too
 * loud for a heading that sits there all the time — so type takes the same
 * hue pulled back: deeper on the light background, softer on the dark one.
 */
val TelnyxGreenText: Color
    @Composable
    @ReadOnlyComposable
    get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF5FC7A6)
    } else {
        Color(0xFF00805F)
    }

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

// Amber, in two weights, because one amber cannot sit on both backgrounds. The
// category palette's amber (0xFFEEA23E) is tuned for a filled circle and is far
// too pale to read as TEXT on white — around 2:1, which is a colour you notice
// and then cannot make out.
private val AmberInk = Color(0xFF9A5B00)
private val AmberGlow = Color(0xFFF0B357)

/**
 * The third state, between "fine" and "wrong".
 *
 * Material gives a scheme an error colour and nothing between it and ordinary
 * text, so an app with anything to flag either shouts in red or says it in the
 * same grey as everything else. An update waiting to be installed is neither: it
 * is not a failure, and red next to "Dostępna jest wersja 0.9.0" reads as
 * something having gone wrong with it.
 *
 * Picked off the surface rather than by asking the system for dark mode, so it
 * follows whatever scheme is actually in force — including a preview or a test
 * that hands MonyxTheme a scheme of its own.
 */
val ColorScheme.attention: Color
    get() = if (surface.luminance() > 0.5f) AmberInk else AmberGlow

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
