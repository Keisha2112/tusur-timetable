package com.keisha.tusur.timetable.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.ui.theme.TusurTimetableTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class LessonCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun lesson(
        rooms: List<String> = listOf("улк 401"),
        teachers: List<String> = listOf("Иванов И.И.", "Петров П.П.", "Сидоров С.С."),
        type: LessonType = LessonType.LECTURE,
    ) = Lesson(
        id = "2026-10-26T08:50|Математический анализ",
        date = LocalDate.of(2026, 10, 26),
        start = LocalTime.of(8, 50),
        end = LocalTime.of(10, 25),
        pairNumber = 1,
        discipline = "Математический анализ",
        type = type,
        rawType = "Лекция",
        rooms = rooms,
        teachers = teachers,
    )

    @Test
    fun cardShowsTimePairDisciplineTypeRoomAndTeachers() {
        compose.setContent {
            TusurTimetableTheme {
                LessonCard(lesson = lesson(), onClick = {})
            }
        }

        compose.onNodeWithText("08:50 – 10:25").assertIsDisplayed()
        compose.onNodeWithText("Пара 1").assertIsDisplayed()
        compose.onNodeWithText("Математический анализ").assertIsDisplayed()
        compose.onNodeWithText("Лекция").assertIsDisplayed()
        compose.onNodeWithText("ауд. улк 401", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Иванов И.И.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("и ещё 1", substring = true).assertIsDisplayed()
    }

    @Test
    fun cardHidesRoomAndTeachersWhenTheyAreMissing() {
        compose.setContent {
            TusurTimetableTheme {
                LessonCard(lesson = lesson(rooms = emptyList(), teachers = emptyList()), onClick = {})
            }
        }

        compose.onNodeWithText("ауд.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("и ещё", substring = true).assertDoesNotExist()
    }

    @Test
    fun cardIsClickable() {
        var clicked = false
        compose.setContent {
            TusurTimetableTheme {
                LessonCard(lesson = lesson(), onClick = { clicked = true })
            }
        }

        compose.onNodeWithText("Математический анализ").performClick()
        compose.waitForIdle()
        assertTrue(clicked)
    }
}
