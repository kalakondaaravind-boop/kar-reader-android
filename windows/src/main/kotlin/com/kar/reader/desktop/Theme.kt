package com.kar.reader.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The clean modern blue palette shared with the Android app. */
object KarColors {
    val Primary = Color(0xFF1A5CFF)
    val PrimaryDeep = Color(0xFF0B3FCC)
    val Accent = Color(0xFF00A3FF)
    val Background = Color(0xFFF6F8FC)
    val Surface = Color(0xFFFFFFFF)
    val Border = Color(0xFFDCE3EE)
    val Ink = Color(0xFF0E1A2B)
    val Muted = Color(0xFF5A6B85)
    val Success = Color(0xFF12A150)
    val Danger = Color(0xFFE5484D)
    val DarkBackground = Color(0xFF0B1220)
    val DarkSurface = Color(0xFF131C2E)
    val DarkPrimary = Color(0xFF6EA8FF)
    val SpeakerPalette = listOf(Color(0xFF1A5CFF), Color(0xFF00A3FF), Color(0xFF12A150), Color(0xFFE08A00), Color(0xFF8B5CF6), Color(0xFFE5484D), Color(0xFF0E9AA7), Color(0xFFB4488E))
}

private val Light = lightColorScheme(
    primary = KarColors.Primary, onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EBFF), onPrimaryContainer = KarColors.PrimaryDeep,
    secondary = KarColors.Accent, onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF1FF), onSecondaryContainer = Color(0xFF004B73),
    tertiary = KarColors.Success, onTertiary = Color.White,
    background = KarColors.Background, onBackground = KarColors.Ink,
    surface = KarColors.Surface, onSurface = KarColors.Ink,
    surfaceVariant = Color(0xFFEEF2F9), onSurfaceVariant = KarColors.Muted,
    surfaceContainerLow = Color(0xFFF1F4FA), surfaceContainer = Color(0xFFEBF0F8), surfaceContainerHigh = Color(0xFFE4EAF4),
    outline = KarColors.Border, outlineVariant = Color(0xFFE8EDF5),
    error = KarColors.Danger, onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = KarColors.DarkPrimary, onPrimary = Color(0xFF06214F),
    primaryContainer = Color(0xFF173B86), onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF56C2FF), onSecondary = Color(0xFF00334D),
    secondaryContainer = Color(0xFF0B3A57), onSecondaryContainer = Color(0xFFCDEBFF),
    tertiary = Color(0xFF4FD08B), onTertiary = Color(0xFF00391C),
    background = KarColors.DarkBackground, onBackground = Color(0xFFE6ECF7),
    surface = KarColors.DarkSurface, onSurface = Color(0xFFE6ECF7),
    surfaceVariant = Color(0xFF1B2740), onSurfaceVariant = Color(0xFF9FB0CC),
    surfaceContainerLow = Color(0xFF101A2C), surfaceContainer = Color(0xFF16233A), surfaceContainerHigh = Color(0xFF1C2B47),
    outline = Color(0xFF2B3B59), outlineVariant = Color(0xFF223250),
    error = Color(0xFFFF8A8E), onError = Color(0xFF4A0006),
)

private val Sans = FontFamily.SansSerif
private val KarTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun KarTheme(setting: String, content: @Composable () -> Unit) {
    val dark = when (setting) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = KarTypography, content = content)
}
