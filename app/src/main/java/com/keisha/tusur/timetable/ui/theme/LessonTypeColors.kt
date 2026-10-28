package com.keisha.tusur.timetable.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.keisha.tusur.timetable.domain.model.LessonType

/**
 * Цвета типа занятия, повторяющие палитру сайта timetable.tusur.ru.
 *
 * На сайте блок занятия целиком залит пастельным цветом:
 * лекция #E5FFD5, практика #D5F6FF, лабораторная #D7D7F4, НИР #F4E3D7,
 * курсовое проектирование #FFD5E5, экзамен/зачёт #FCC1B5.
 * В приложении этот цвет используется для бейджа, а более тёмный оттенок — для полосы.
 * Для тёмной темы пастель заменена приглушёнными контейнерами: сайт тёмной темы не имеет.
 */
@Immutable
data class LessonTypeColors(
    val accent: Color,
    val container: Color,
    val content: Color,
)

@Composable
fun lessonTypeColors(type: LessonType, rawType: String): LessonTypeColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val key = when {
        type == LessonType.OTHER && isDesign(rawType) -> Key.DESIGN
        type == LessonType.LECTURE -> Key.LECTURE
        type == LessonType.PRACTICE -> Key.PRACTICE
        type == LessonType.LABORATORY -> Key.LABORATORY
        type == LessonType.RESEARCH -> Key.RESEARCH
        type == LessonType.EXAM || type == LessonType.CREDIT -> Key.EXAM
        else -> Key.NEUTRAL
    }
    return if (dark) DarkColors.getValue(key) else LightColors.getValue(key)
}

private fun isDesign(rawType: String): Boolean =
    rawType.contains("курсов", ignoreCase = true) ||
        rawType.contains("проектирован", ignoreCase = true)

private enum class Key { LECTURE, PRACTICE, LABORATORY, RESEARCH, DESIGN, EXAM, NEUTRAL }

private val LightColors = mapOf(
    Key.LECTURE to LessonTypeColors(
        accent = Color(0xFF438F14),
        container = Color(0xFFE5FFD5),
        content = Color(0xFF1E3A0A),
    ),
    Key.PRACTICE to LessonTypeColors(
        accent = Color(0xFF14748F),
        container = Color(0xFFD5F6FF),
        content = Color(0xFF0B3440),
    ),
    Key.LABORATORY to LessonTypeColors(
        accent = Color(0xFF232380),
        container = Color(0xFFD7D7F4),
        content = Color(0xFF1C1C4D),
    ),
    Key.RESEARCH to LessonTypeColors(
        accent = Color(0xFF8A5A2B),
        container = Color(0xFFF4E3D7),
        content = Color(0xFF3D2513),
    ),
    Key.DESIGN to LessonTypeColors(
        accent = Color(0xFFA32A5C),
        container = Color(0xFFFFD5E5),
        content = Color(0xFF4A0F27),
    ),
    Key.EXAM to LessonTypeColors(
        accent = Color(0xFFA63A2A),
        container = Color(0xFFFCC1B5),
        content = Color(0xFF4A120A),
    ),
    // На сайте консультация белая; для видимого бейджа нужен нейтральный серый.
    Key.NEUTRAL to LessonTypeColors(
        accent = Color(0xFF6B6A76),
        container = Color(0xFFEDECF3),
        content = Color(0xFF3A3946),
    ),
)

private val DarkColors = mapOf(
    Key.LECTURE to LessonTypeColors(
        accent = Color(0xFF9BD46B),
        container = Color(0xFF21360F),
        content = Color(0xFFC7E8AD),
    ),
    Key.PRACTICE to LessonTypeColors(
        accent = Color(0xFF6FC6DE),
        container = Color(0xFF10333D),
        content = Color(0xFFB3E4F1),
    ),
    Key.LABORATORY to LessonTypeColors(
        accent = Color(0xFFA8A4F0),
        container = Color(0xFF232348),
        content = Color(0xFFC9C7F4),
    ),
    Key.RESEARCH to LessonTypeColors(
        accent = Color(0xFFD6A175),
        container = Color(0xFF3A2A1E),
        content = Color(0xFFEBCDB4),
    ),
    Key.DESIGN to LessonTypeColors(
        accent = Color(0xFFE784A7),
        container = Color(0xFF3D1B28),
        content = Color(0xFFF2B7CA),
    ),
    Key.EXAM to LessonTypeColors(
        accent = Color(0xFFE58E7C),
        container = Color(0xFF3D1F1A),
        content = Color(0xFFF3B9AD),
    ),
    Key.NEUTRAL to LessonTypeColors(
        accent = Color(0xFFA9A7B4),
        container = Color(0xFF2A2A32),
        content = Color(0xFFD6D5DE),
    ),
)
