package com.keisha.tusur.timetable.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.NowSnapshot
import com.keisha.tusur.timetable.domain.time.NowState
import com.keisha.tusur.timetable.ui.schedule.NowContent
import com.keisha.tusur.timetable.ui.schedule.ScheduleWeekList
import com.keisha.tusur.timetable.ui.theme.TusurTimetableTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

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
}
