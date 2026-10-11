package dev.jay.holdmyfiles.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF294700),
    onPrimary = Color(0xFFF8FFE8),
    primaryContainer = Color(0xFFD5FF83),
    onPrimaryContainer = Color(0xFF152700),
    secondary = Color(0xFF4F5D3A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E9C8),
    onSecondaryContainer = Color(0xFF1B2710),
    tertiary = Color(0xFF9D3B1C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCF),
    onTertiaryContainer = Color(0xFF3B0A00),
    background = Color(0xFFF4F3E8),
    onBackground = Color(0xFF191B16),
    surface = Color(0xFFFCFBF2),
    onSurface = Color(0xFF191B16),
    surfaceVariant = Color(0xFFE4E5D9),
    onSurfaceVariant = Color(0xFF45483E),
    outline = Color(0xFF76796D),
    outlineVariant = Color(0xFFC6C8BC),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC8FF62),
    onPrimary = Color(0xFF1C2F00),
    primaryContainer = Color(0xFF344D12),
    onPrimaryContainer = Color(0xFFD5FF83),
    secondary = Color(0xFFC4CEA9),
    onSecondary = Color(0xFF2E381F),
    secondaryContainer = Color(0xFF444F33),
    onSecondaryContainer = Color(0xFFE0E9C8),
    tertiary = Color(0xFFFFB59D),
    onTertiary = Color(0xFF5F1603),
    tertiaryContainer = Color(0xFF7D2D13),
    onTertiaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF10120F),
    onBackground = Color(0xFFE3E3DA),
    surface = Color(0xFF181B17),
    onSurface = Color(0xFFE3E3DA),
    surfaceVariant = Color(0xFF292D26),
    onSurfaceVariant = Color(0xFFC6C8BC),
    outline = Color(0xFF909388),
    outlineVariant = Color(0xFF45483E),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 38.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.7.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun HoldMyFilesTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) {
        DarkColors
    } else {
        LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
