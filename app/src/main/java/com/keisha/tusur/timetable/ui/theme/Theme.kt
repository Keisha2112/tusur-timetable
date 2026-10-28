package com.keisha.tusur.timetable.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = TusurPurple,
    onPrimary = Color.White,
    primaryContainer = TusurPurpleContainer,
    onPrimaryContainer = TusurPurpleDark,
    secondary = TusurGreen,
    onSecondary = Color.White,
    secondaryContainer = TusurGreenContainer,
    onSecondaryContainer = TusurGreenOnContainer,
    tertiary = Color(0xFF5B5D72),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E0F9),
    onTertiaryContainer = Color(0xFF181A2C),
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceTint = TusurPurple,
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF3F0F4),
    inversePrimary = TusurPurpleLight,
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = TusurPurpleLight,
    onPrimary = TusurPurpleDark,
    primaryContainer = TusurPurpleContainerDark,
    onPrimaryContainer = TusurPurpleContainer,
    secondary = TusurGreenLight,
    onSecondary = Color(0xFF243300),
    secondaryContainer = TusurGreenContainerDark,
    onSecondaryContainer = TusurGreenContainer,
    tertiary = Color(0xFFC4C5DD),
    onTertiary = Color(0xFF2D2F42),
    tertiaryContainer = Color(0xFF444559),
    onTertiaryContainer = Color(0xFFE0E0F9),
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    surfaceTint = TusurPurpleLight,
    inverseSurface = Color(0xFFE5E1E9),
    inverseOnSurface = Color(0xFF303036),
    inversePrimary = TusurPurple,
    scrim = Color(0xFF000000),
)

@Composable
fun TusurTimetableTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color намеренно отключён: фирменные цвета не должны подменяться системой.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TusurTypography,
        content = content,
    )
}
