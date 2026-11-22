package com.kannadadoc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val KadataLightColors = lightColorScheme(
    primary = Color(0xFF1A4D7A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E5F5),
    onPrimaryContainer = Color(0xFF07243D),
    secondary = Color(0xFF9E7128),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E2BE),
    onSecondaryContainer = Color(0xFF332104),
    tertiary = Color(0xFF50687A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3ECF2),
    onTertiaryContainer = Color(0xFF1B2B36),
    background = Color(0xFFFAF3E3),
    onBackground = Color(0xFF1D242B),
    surface = Color(0xFFFFFDF7),
    onSurface = Color(0xFF1D242B),
    surfaceVariant = Color(0xFFEFE5CE),
    onSurfaceVariant = Color(0xFF47535E),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFCF3),
    surfaceContainer = Color(0xFFF8F1E1),
    surfaceContainerHigh = Color(0xFFF2E9D5),
    surfaceContainerHighest = Color(0xFFECE0C8),
    outline = Color(0xFFCBBCA0)
)

/**
 * True Pure Black (OLED) Dark Theme — zero bluish-grey tint on backgrounds,
 * cards, menus, dialogs, or keyboard panels, paired with crisp Kadata accents.
 */
private val KadataPureBlackDarkColors = darkColorScheme(
    primary = Color(0xFF7CB7EA),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFE6F0FA),
    secondary = Color(0xFFE0B86A),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF221C10),
    onSecondaryContainer = Color(0xFFF9E8C4),
    tertiary = Color(0xFFC4C4C4),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF1C1C1C),
    onTertiaryContainer = Color(0xFFEAEAEA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF080808),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF151515),
    onSurfaceVariant = Color(0xFFB5B5B5),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF060606),
    surfaceContainer = Color(0xFF0C0C0C),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1E1E1E),
    outline = Color(0xFF2E2E2E)
)

private val TabletTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 26.sp
    ),
    labelLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun KannadaDocTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) KadataPureBlackDarkColors else KadataLightColors,
        typography = TabletTypography,
        content = content
    )
}
