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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kar.reader.data.prefs.ThemeMode

/**
 * Brand tokens for KAR READER: a clean, modern blue theme. Cool white and grey surfaces, one confident blue,
 * a bright accent for focus and progress, and soft depth instead of heavy gloss.
 */
object KarColors {
    val Primary = Color(0xFF1A5CFF)
    val PrimaryDeep = Color(0xFF0B3FCC)
    val PrimaryLight = Color(0xFF5B8CFF)
    val PrimarySoft = Color(0xFFE8EFFF)
    val Accent = Color(0xFF00A3FF)
    val AccentLight = Color(0xFF7FD1FF)
    val AccentDeep = Color(0xFF0077CC)
    val Background = Color(0xFFF6F8FC)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceAlt = Color(0xFFEEF2F9)
    val Border = Color(0xFFDCE3EE)
    val Ink = Color(0xFF0E1A2B)
    val Muted = Color(0xFF5A6B85)
    val Success = Color(0xFF12A150)
    val SuccessLight = Color(0xFF8BE3B1)
    val Danger = Color(0xFFE5484D)
    val Warning = Color(0xFFF5A524)
    val Graphite = Color(0xFF15171A)

    // Dark theme.
    val DarkBackground = Color(0xFF0B1220)
    val DarkSurface = Color(0xFF131C2E)
    val DarkSurfaceHigh = Color(0xFF1A2540)
    val DarkBorder = Color(0xFF26324A)
    val DarkPrimary = Color(0xFF6EA8FF)
    val DarkInk = Color(0xFFE8EEF8)
    val DarkMuted = Color(0xFF94A3BD)
}

private val LightScheme = lightColorScheme(
    primary = KarColors.Primary,
    onPrimary = Color.White,
    primaryContainer = KarColors.PrimarySoft,
    onPrimaryContainer = Color(0xFF0A2A80),
    secondary = KarColors.AccentDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F3FF),
    onSecondaryContainer = Color(0xFF00385F),
    tertiary = KarColors.Success,
    background = KarColors.Background,
    onBackground = KarColors.Ink,
    surface = KarColors.Surface,
    onSurface = KarColors.Ink,
    surfaceVariant = KarColors.SurfaceAlt,
    onSurfaceVariant = KarColors.Muted,
    surfaceContainer = Color(0xFFF1F4FA),
    surfaceContainerHigh = Color(0xFFEAEFF7),
    surfaceContainerLow = Color(0xFFF8FAFD),
    outline = Color(0xFFC3CDDD),
    outlineVariant = KarColors.Border,
    error = Color(0xFFD12F35),
)

private val DarkScheme = darkColorScheme(
    primary = KarColors.DarkPrimary,
    onPrimary = Color(0xFF00225F),
    primaryContainer = Color(0xFF173A8C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = KarColors.AccentLight,
    onSecondary = Color(0xFF00324F),
    secondaryContainer = Color(0xFF004C78),
    onSecondaryContainer = Color(0xFFCDEBFF),
    tertiary = KarColors.SuccessLight,
    background = KarColors.DarkBackground,
    onBackground = KarColors.DarkInk,
    surface = KarColors.DarkSurface,
    onSurface = KarColors.DarkInk,
    surfaceVariant = KarColors.DarkSurfaceHigh,
    onSurfaceVariant = KarColors.DarkMuted,
    surfaceContainer = Color(0xFF162036),
    surfaceContainerHigh = KarColors.DarkSurfaceHigh,
    surfaceContainerLow = Color(0xFF0F182A),
    outline = Color(0xFF3A4864),
    outlineVariant = KarColors.DarkBorder,
    error = Color(0xFFFF8A8E),
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

private val KarTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp),
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
