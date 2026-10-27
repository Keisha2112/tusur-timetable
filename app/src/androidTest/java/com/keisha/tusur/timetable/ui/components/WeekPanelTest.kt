package com.keisha.tusur.timetable.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.ui.theme.TusurTimetableTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class WeekPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun week(id: Int, start: String, current: Boolean = false) = Week(
        id = id,
        number = id,
        startDate = LocalDate.parse(start),
        kind = WeekKind.THEORETICAL,
        isCurrent = current,
    )

    private val weeks = listOf(
        week(8, "2026-10-19"),
        week(9, "2026-10-26", current = true),
        week(10, "2026-11-02"),
    )

    @Test
    fun titleContainsNumberRangeAndParity() {
        compose.setContent {
            TusurTimetableTheme {
                WeekPanel(
                    week = weeks[1],
                    weeks = weeks,
                    weeksWithLessons = setOf(9),
                    currentWeekId = 9,
                    canGoPrevious = true,
                    canGoNext = true,
                    onPrevious = {},
                    onNext = {},
                    onToday = {},
                    onChooseWeek = {},
                )
            }
        }

        compose.onNodeWithText("Неделя 9", substring = true).assertIsDisplayed()
        compose.onNodeWithText("26 окт — 1 ноя", substring = true).assertIsDisplayed()
        compose.onNodeWithText("нечётная", substring = true).assertIsDisplayed()
    }

    @Test
    fun arrowsAndWeekPickerAreClickable() {
        var previous = 0
        var next = 0
        var picker = 0

        compose.setContent {
            TusurTimetableTheme {
                WeekPanel(
                    week = weeks[1],
                    weeks = weeks,
                    weeksWithLessons = emptySet(),
                    currentWeekId = 9,
                    canGoPrevious = true,
                    canGoNext = true,
                    onPrevious = { previous++ },
                    onNext = { next++ },
                    onToday = {},
                    onChooseWeek = { picker++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Предыдущая неделя").performClick()
        compose.onNodeWithContentDescription("Следующая неделя").performClick()
        compose.onNodeWithText("Неделя 9", substring = true).performClick()
        compose.waitForIdle()

        assertEquals(1, previous)
        assertEquals(1, next)
        assertEquals(1, picker)
    }

    @Test
    fun todayButtonAppearsOnlyForAnotherWeek() {
        compose.setContent {
            TusurTimetableTheme {
                WeekPanel(
                    week = weeks[2],
                    weeks = weeks,
                    weeksWithLessons = emptySet(),
                    currentWeekId = 9,
                    canGoPrevious = true,
                    canGoNext = false,
                    onPrevious = {},
                    onNext = {},
                    onToday = {},
                    onChooseWeek = {},
                )
            }
        }

        compose.onNodeWithText("Сегодня").assertIsDisplayed()
    }
}
