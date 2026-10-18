package com.kar.reader.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kar.reader.data.prefs.ThemeMode

/** Central brand tokens for KAR READER. */
object KarColors {
    val Navy = Color(0xFF1F3A5F)
    val NavyDeep = Color(0xFF0D1B30)
    val NavyLight = Color(0xFF2F5688)
    val NavyBright = Color(0xFF4C7BBA)
    val Gold = Color(0xFFE0A33A)
    val GoldLight = Color(0xFFFCDC8E)
    val GoldDeep = Color(0xFFA86C15)
    val Cream = Color(0xFFFBF8F3)
    val Paper = Color(0xFFF4EFE6)
    val Sand = Color(0xFFE4DCCD)
    val Ink = Color(0xFF1A2130)
    val Slate = Color(0xFF4F5868)
    val Green = Color(0xFF2E6B3A)
    val GreenLight = Color(0xFF9BE3B0)
    val Red = Color(0xFFA8321F)
    val Graphite = Color(0xFF15171A)
}

private val LightScheme = lightColorScheme(
    primary = KarColors.Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5F0),
    onPrimaryContainer = Color(0xFF142843),
    secondary = KarColors.GoldDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE9C4),
    onSecondaryContainer = Color(0xFF3B2705),
    tertiary = KarColors.Green,
    background = KarColors.Cream,
    onBackground = KarColors.Ink,
    surface = Color(0xFFFFFDF9),
    onSurface = KarColors.Ink,
    surfaceVariant = Color(0xFFEFE9DE),
    onSurfaceVariant = KarColors.Slate,
    surfaceContainer = Color(0xFFF6F1E8),
    surfaceContainerHigh = Color(0xFFF1EBE0),
    surfaceContainerLow = Color(0xFFFAF6EF),
    outline = Color(0xFFCFC5B3),
    outlineVariant = Color(0xFFE6DFD2),
    error = KarColors.Red,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9CC2F2),
    onPrimary = Color(0xFF0D1B30),
    primaryContainer = Color(0xFF2A4D7C),
    onPrimaryContainer = Color(0xFFDCE5F0),
    secondary = Color(0xFFF4C268),
    onSecondary = Color(0xFF2A1A05),
    secondaryContainer = Color(0xFF5A3E0C),
    onSecondaryContainer = Color(0xFFFCE19A),
    tertiary = KarColors.GreenLight,
    background = Color(0xFF12151B),
    onBackground = Color(0xFFECE7DD),
    surface = Color(0xFF181C23),
    onSurface = Color(0xFFECE7DD),
    surfaceVariant = Color(0xFF262B34),
    onSurfaceVariant = Color(0xFFB4AEA4),
    surfaceContainer = Color(0xFF1E232B),
    surfaceContainerHigh = Color(0xFF252A33),
    surfaceContainerLow = Color(0xFF1A1E25),
    outline = Color(0xFF4A505B),
    outlineVariant = Color(0xFF30353F),
    error = Color(0xFFFF9A8A),
)

/** E-ink: pure black on white, no tints, so text is crisp and partial refreshes stay clean. */
private val EinkScheme = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color.White, onPrimaryContainer = Color.Black,
    secondary = Color.Black, onSecondary = Color.White,
    secondaryContainer = Color.White, onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color.White, onSurfaceVariant = Color.Black,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerLow = Color.White,
    outline = Color.Black, outlineVariant = Color.Black,
    error = Color.Black,
)

data class KarStyle(val eink: Boolean, val dark: Boolean) {
    /** Glossy effects (gradients, sheen, 3D tilt, glow) are drawn only when this is true. */
    val glossy: Boolean get() = !eink
}

val LocalKarStyle = staticCompositionLocalOf { KarStyle(eink = false, dark = false) }

private val SerifDisplay = FontFamily.Serif

private val KarTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = SerifDisplay, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = SerifDisplay, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = SerifDisplay, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
    )
}

@Composable
fun KarTheme(themeMode: ThemeMode, eink: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    } && !eink
    val scheme: ColorScheme = when {
        eink -> EinkScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalKarStyle provides KarStyle(eink, dark)) {
        MaterialTheme(colorScheme = scheme, typography = KarTypography, content = content)
    }
}
