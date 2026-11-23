package com.keisha.tusur.timetable.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.ColorPalette
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.ScheduleEntries
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.NowSnapshot
import com.keisha.tusur.timetable.domain.time.NowState
import com.keisha.tusur.timetable.domain.time.NowStateCalculator
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.schedule.NowContent
import com.keisha.tusur.timetable.ui.schedule.ScheduleWeekList
import com.keisha.tusur.timetable.ui.theme.TusurTimetableTheme
import com.keisha.tusur.timetable.ui.theme.paletteScheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class HolidayAndNowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unpublishedWeekStillShowsHolidayInLightTheme() {
        val date = LocalDate.of(2026, 11, 4)
        val week = Week(848, 10, date.minusDays(2), WeekKind.THEORETICAL, false)
        compose.setContent {
            TusurTimetableTheme(darkTheme = false) {
                ScheduleWeekList(
                    lessons = emptyList(),
                    week = week,
                    contentKey = "714-2",
                    currentWeekId = null,
                    canGoPrevious = true,
                    canGoNext = true,
                    showTeachers = true,
                    showGroups = false,
                    onPreviousWeek = {},
                    onNextWeek = {},
                    onOpenLesson = {},
                    holidays = listOf(HolidayDay(date)),
                    publication = WeekPublication.UNPUBLISHED,
                )
            }
        }
        compose.onNodeWithText("Среда, 4 ноября").assertIsDisplayed()
        compose.onNodeWithText("Праздничный день").assertIsDisplayed()
        compose.onNodeWithText("Занятий нет", substring = false).assertDoesNotExist()
    }

    @Test
    fun nowShowsHolidayAndTheEndOfLoadedScheduleInDarkTheme() {
        val snapshot = NowSnapshot(
            LocalDate.of(2026, 11, 4),
            true,
            NowState.DayOff(NowState.DayOff.Reason.HOLIDAY, null),
            Instant.parse("2026-11-04T17:00:00Z"),
        )
        compose.setContent {
            TusurTimetableTheme(darkTheme = true) { NowContent(snapshot, onLesson = {}) }
        }
        compose.onNodeWithText("Праздничный день").assertIsDisplayed()
        compose.onNodeWithText("В загруженном расписании больше занятий нет").assertIsDisplayed()
    }

    @Test
    fun nowShowsCurrentAndNextRoomLessonsTogether() {
        val date = LocalDate.of(2026, 11, 23)
        val current = Lesson("current", date, LocalTime.of(8, 50), LocalTime.of(10, 25),
            discipline = "Текущая лекция", type = LessonType.LECTURE, rawType = "Лекция")
        val next = current.copy(id = "next", start = LocalTime.of(10, 40), end = LocalTime.of(12, 15),
            discipline = "Следующая лекция", groups = listOf("714-2"))
        val entry = ScheduleEntries(emptyList(), listOf(current, next), emptyList(), emptyMap())
        val snapshot = NowStateCalculator().calculateEntries(entry, date.atTime(9, 0).atZone(ScheduleTime.ZONE).toInstant())
        var opened: Lesson? = null
        compose.setContent {
            TusurTimetableTheme(darkTheme = false) { NowContent(snapshot, onLesson = { opened = it }, isRoom = true) }
        }
        compose.onNodeWithText("Текущая пара").assertIsDisplayed()
        compose.onNodeWithText("Следующая пара").assertIsDisplayed()
        compose.onNodeWithText("До начала 1 ч 40 мин").assertIsDisplayed()
        compose.onNodeWithText("Следующая лекция").performClick()
        compose.runOnIdle { assertEquals(next, opened) }
    }

    @Test
    fun lessonCardUsesEveryPaletteInBothAppearances() {
        val chosen = mutableStateOf(ColorPalette.ORIGINAL to false)
        val lesson = Lesson(
            "lab", LocalDate.of(2026, 11, 23), LocalTime.of(9, 0), LocalTime.of(10, 0),
            discipline = "Схемотехника", type = LessonType.LABORATORY, rawType = "Лабораторная",
        )
        var applied = Color.Unspecified
        compose.setContent {
            val (palette, dark) = chosen.value
            TusurTimetableTheme(darkTheme = dark, palette = palette) {
                val primary = MaterialTheme.colorScheme.primary
                SideEffect { applied = primary }
                LessonCard(lesson, onClick = {})
            }
        }
        for (dark in listOf(false, true)) {
            for (palette in ColorPalette.entries) {
                compose.runOnIdle { chosen.value = palette to dark }
                compose.onNodeWithText("Схемотехника").assertIsDisplayed()
                compose.runOnIdle { assertEquals(paletteScheme(palette, dark).primary, applied) }
            }
        }
    }
}
