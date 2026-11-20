package com.keisha.tusur.timetable.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF0FF),
    onPrimaryContainer = Color(0xFF173A91),
    secondary = Color(0xFF425878),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0F8),
    onSecondaryContainer = Color(0xFF243A56),
    tertiary = Color(0xFF4A5869),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0F3F7),
    onTertiaryContainer = Color(0xFF344458),
    background = Color.White,
    onBackground = Color(0xFF151D2C),
    surface = Color.White,
    onSurface = Color(0xFF151D2C),
    surfaceVariant = Color(0xFFF1F4F8),
    onSurfaceVariant = Color(0xFF4E5F74),
    surfaceDim = Color(0xFFE2E8F0),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFECF0F6),
    surfaceContainerHighest = Color(0xFFE6ECF4),
    outline = Color(0xFF697A91),
    outlineVariant = Color(0xFFDCE3ED),
    error = Color(0xFFAF2C36),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEB),
    onErrorContainer = Color(0xFF791E28),
    surfaceTint = Color(0xFF1D4ED8),
    inverseSurface = Color(0xFF242E40),
    inverseOnSurface = Color(0xFFF3F6FB),
    inversePrimary = Color(0xFF8AB4FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0F274B),
    primaryContainer = Color(0xFF1E3153),
    onPrimaryContainer = Color(0xFFC9DCFF),
    secondary = Color(0xFFB4C9E7),
    onSecondary = Color(0xFF1B304B),
    secondaryContainer = Color(0xFF25344A),
    onSecondaryContainer = Color(0xFFD4E3F9),
    tertiary = Color(0xFFB5C3D6),
    onTertiary = Color(0xFF253245),
    tertiaryContainer = Color(0xFF253142),
    onTertiaryContainer = Color(0xFFD6E1F1),
    background = Color(0xFF11161E),
    onBackground = Color(0xFFF0F4FA),
    surface = Color(0xFF19212C),
    onSurface = Color(0xFFF0F4FA),
    surfaceVariant = Color(0xFF232D3B),
    onSurfaceVariant = Color(0xFFB5C2D4),
    surfaceDim = Color(0xFF11161E),
    surfaceBright = Color(0xFF303C4C),
    surfaceContainerLowest = Color(0xFF0D1118),
    surfaceContainerLow = Color(0xFF171E29),
    surfaceContainer = Color(0xFF1C2532),
    surfaceContainerHigh = Color(0xFF242F3D),
    surfaceContainerHighest = Color(0xFF2C3848),
    outline = Color(0xFF8798AF),
    outlineVariant = Color(0xFF344152),
    error = Color(0xFFFFB3B8),
    onError = Color(0xFF641823),
    errorContainer = Color(0xFF502731),
    onErrorContainer = Color(0xFFFFD9DB),
    surfaceTint = Color(0xFF8AB4FF),
    inverseSurface = Color(0xFFE5EDF9),
    inverseOnSurface = Color(0xFF1E3048),
    inversePrimary = Color(0xFF1D4ED8),
)

private val TusurShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TusurTimetableTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TusurTypography,
        shapes = TusurShapes,
        content = content,
    )
}
