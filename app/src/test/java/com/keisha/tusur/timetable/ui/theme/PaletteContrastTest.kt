package com.keisha.tusur.timetable.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.keisha.tusur.timetable.domain.model.ColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    @Test
    fun `each light and dark palette has distinct accents and readable text roles`() {
        for (dark in listOf(false, true)) {
            val accents = ColorPalette.entries.map { palette ->
                val colors = paletteScheme(palette, dark)
                val roles = mapOf(
                    "background" to (colors.onBackground to colors.background),
                    "surface" to (colors.onSurface to colors.surface),
                    "surface variant" to (colors.onSurfaceVariant to colors.surfaceVariant),
                    "surface container" to (colors.onSurface to colors.surfaceContainerHigh),
                    "accent text" to (colors.primary to colors.surfaceContainerHigh),
                    "primary" to (colors.onPrimary to colors.primary),
                    "primary container" to (colors.onPrimaryContainer to colors.primaryContainer),
                    "secondary" to (colors.onSecondary to colors.secondary),
                    "secondary container" to (colors.onSecondaryContainer to colors.secondaryContainer),
                    "tertiary" to (colors.onTertiary to colors.tertiary),
                    "tertiary container" to (colors.onTertiaryContainer to colors.tertiaryContainer),
                    "error" to (colors.onErrorContainer to colors.errorContainer),
                )
                for ((role, pair) in roles) {
                    val ratio = contrast(pair.first, pair.second)
                    assertTrue("$palette dark=$dark $role has contrast $ratio", ratio >= 4.5f)
                }
                colors.primary
            }
            assertEquals(ColorPalette.entries.size, accents.toSet().size)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
