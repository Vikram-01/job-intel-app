package com.cipherpath.ui.theme

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

val Teal = Color(0xFF14B8A6)
val TealDeep = Color(0xFF0F766E)
val Ink = Color(0xFF0B1220)
val InkRaised = Color(0xFF111A2C)
val InkLine = Color(0xFF22304A)
val Amber = Color(0xFFF59E0B)
val Rose = Color(0xFFF43F5E)
val Sky = Color(0xFF38BDF8)

private val Dark = darkColorScheme(
    primary = Teal, onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0E3B37), onPrimaryContainer = Color(0xFFB6F5EC),
    secondary = Sky, onSecondary = Color(0xFF00243A),
    secondaryContainer = Color(0xFF13324A), onSecondaryContainer = Color(0xFFCDE9FF),
    tertiary = Amber, onTertiary = Color(0xFF2B1700),
    background = Ink, onBackground = Color(0xFFE5EAF2),
    surface = Ink, onSurface = Color(0xFFE5EAF2),
    surfaceVariant = InkRaised, onSurfaceVariant = Color(0xFF9AA7BD),
    surfaceContainer = InkRaised, surfaceContainerHigh = Color(0xFF16213A), surfaceContainerLow = Color(0xFF0E1627),
    outline = InkLine, outlineVariant = Color(0xFF1B2740),
    error = Rose, onError = Color.White,
)

private val Light = lightColorScheme(
    primary = TealDeep, onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF5EF), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF0369A1), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF0FF), onSecondaryContainer = Color(0xFF001E30),
    tertiary = Color(0xFFB45309), onTertiary = Color.White,
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF0B1220),
    surface = Color(0xFFF6F8FB), onSurface = Color(0xFF0B1220),
    surfaceVariant = Color.White, onSurfaceVariant = Color(0xFF526077),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEEF2F7), surfaceContainerLow = Color(0xFFF9FBFD),
    outline = Color(0xFFD5DCE6), outlineVariant = Color(0xFFE6EBF2),
    error = Color(0xFFBE123C), onError = Color.White,
)

private val Type = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
)

@Composable
fun CipherPathTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}

fun scoreColor(score: Int?, dark: Boolean = true): Color = when {
    score == null -> Color(0xFF7C8AA3)
    score >= 75 -> if (dark) Teal else TealDeep
    score >= 50 -> Amber
    else -> Rose
}
