package com.keisha.tusur.timetable.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keisha.tusur.timetable.domain.model.AnimationMode
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.ui.schedule.WeekTransition
import com.keisha.tusur.timetable.ui.theme.AppMotion
import com.keisha.tusur.timetable.ui.theme.AppMotionDurationScale
import com.keisha.tusur.timetable.ui.theme.AppMotionProvider
import com.keisha.tusur.timetable.ui.theme.TusurTimetableTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WeekMotionTest {
    private val duration = AppMotionDurationScale(1f)
    @get:Rule val compose = createComposeRule(effectContext = duration)

    @Test
    fun normalTransitionIsStillRunningAtTwoHundredMilliseconds() {
        switchWeek(AnimationMode.NORMAL, waitMillis = 200)
        compose.onNodeWithText("Неделя 1").assertExists()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Неделя 1").assertDoesNotExist()
    }

    @Test
    fun fasterTransitionCompletesBeforeTwoHundredMilliseconds() {
        switchWeek(AnimationMode.FAST, waitMillis = 200)
        compose.onNodeWithText("Неделя 1").assertDoesNotExist()
    }

    @Test
    fun disabledAndAndroidDisabledTransitionsShowOnlyNewWeek() {
        switchWeek(AnimationMode.NORMAL, waitMillis = 64, systemScale = 0f)
        compose.onNodeWithText("Неделя 1").assertDoesNotExist()
    }

    @Test
    fun appDisabledTransitionShowsOnlyNewWeekAtTheNextFrames() {
        switchWeek(AnimationMode.OFF, waitMillis = 64)
        compose.onNodeWithText("Неделя 1").assertDoesNotExist()
    }

    private fun switchWeek(mode: AnimationMode, waitMillis: Long, systemScale: Float = 1f) {
        val first = Week(1, 1, LocalDate.of(2026, 11, 23), WeekKind.THEORETICAL, true)
        val week = mutableStateOf(first)
        val scale = mode.scale * systemScale
        duration.scaleFactor = scale
        compose.setContent {
            AppMotionProvider(AppMotion(mode, scale)) {
                TusurTimetableTheme { WeekTransition(week.value) { Text("Неделя ${it?.id}") } }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { week.value = first.copy(id = 2, startDate = first.startDate.plusWeeks(1)) }
        compose.mainClock.advanceTimeBy(waitMillis)
        compose.onNodeWithText("Неделя 2").assertExists()
    }
}
