package org.murabbie.ahlalhadeeth.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF1F6F5C)
private val GreenDark = Color(0xFF144C3F)
private val Gold = Color(0xFFC9A227)
private val Cream = Color(0xFFF8F5EC)
private val Paper = Color(0xFFFFFDF7)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE9DF),
    onPrimaryContainer = GreenDark,
    secondary = Gold,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFF4E7BF),
    onSecondaryContainer = Color(0xFF4A3B05),
    tertiary = Color(0xFF7A4B1F),
    background = Cream,
    onBackground = Color(0xFF1D1B16),
    surface = Paper,
    onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFEDE7D6),
    onSurfaceVariant = Color(0xFF4A473E),
    outline = Color(0xFF9B9587),
    error = Color(0xFFB3261E),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8ED1BB),
    onPrimary = GreenDark,
    primaryContainer = Color(0xFF1F5A4A),
    onPrimaryContainer = Color(0xFFCFE9DF),
    secondary = Color(0xFFE8CB6B),
    onSecondary = Color(0xFF3E3000),
    secondaryContainer = Color(0xFF5A4A0F),
    onSecondaryContainer = Color(0xFFF4E7BF),
    background = Color(0xFF15161A),
    onBackground = Color(0xFFE6E2DA),
    surface = Color(0xFF1C1E23),
    onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF2B2E35),
    onSurfaceVariant = Color(0xFFC9C5BB),
    outline = Color(0xFF8B877D),
)

private val ArabicFont = FontFamily.Default

val AppTypography = Typography(
    bodyLarge = TextStyle(fontFamily = ArabicFont, fontSize = 18.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontFamily = ArabicFont, fontSize = 16.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontFamily = ArabicFont, fontSize = 14.sp, lineHeight = 22.sp),
    titleLarge = TextStyle(fontFamily = ArabicFont, fontSize = 22.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = ArabicFont, fontSize = 18.sp, lineHeight = 28.sp),
    titleSmall = TextStyle(fontFamily = ArabicFont, fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontFamily = ArabicFont, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = ArabicFont, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = ArabicFont, fontSize = 12.sp),
    headlineSmall = TextStyle(fontFamily = ArabicFont, fontSize = 24.sp, lineHeight = 34.sp),
)

@Composable
fun AppTheme(themeMode: Int, fontScale: Float, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}
