package com.keisha.tusur.timetable.ui.schedule

import com.keisha.tusur.timetable.domain.model.DaySchedule
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WeekListIndexTest {

    private val monday = LocalDate.of(2026, 10, 26)
    private val sunday = monday.plusDays(6)

    private fun lesson(date: LocalDate, index: Int = 0) = Lesson(
        id = "$date-$index",
        date = date,
        start = LocalTime.of(8, 50).plusMinutes(index * 95L),
        end = LocalTime.of(10, 25).plusMinutes(index * 95L),
        discipline = "Математический анализ",
        type = LessonType.LECTURE,
        rawType = "Лекция",
    )

    private fun day(date: LocalDate, lessons: Int = 0) =
        DaySchedule(date, List(lessons) { lesson(date, it) })

    /** Понедельник с двумя занятиями, вторник без занятий, среда с занятием. */
    private fun daysWithEmptyTuesday() = listOf(
        day(monday, lessons = 2),
        day(monday.plusDays(1)),
        day(monday.plusDays(2), lessons = 1),
    )

    @Test
    fun `индекс первого дня равен нулю`() {
        assertEquals(0, dayHeaderIndex(daysWithEmptyTuesday(), monday))
    }

    @Test
    fun `день после дня с занятиями сдвигается на число карточек`() {
        val days = daysWithEmptyTuesday()

        // Понедельник: заголовок + 2 карточки.
        assertEquals(3, dayHeaderIndex(days, monday.plusDays(1)))
    }

    @Test
    fun `день после пустого дня сдвигается на заглушку`() {
        val days = daysWithEmptyTuesday()

        // Вторник: заголовок + заглушка «Занятий нет».
        assertEquals(5, dayHeaderIndex(days, monday.plusDays(2)))
    }

    @Test
    fun `отсутствующая дата не имеет заголовка`() {
        assertNull(dayHeaderIndex(daysWithEmptyTuesday(), monday.plusDays(7)))
    }

    @Test
    fun `текущая неделя открывается на сегодняшнем дне`() {
        val days = daysWithEmptyTuesday()

        assertEquals(
            5,
            weekStartIndex(days, weekId = 9, currentWeekId = 9, today = monday.plusDays(2)),
        )
    }

    @Test
    fun `другая неделя открывается с понедельника`() {
        val days = daysWithEmptyTuesday()

        assertEquals(
            0,
            weekStartIndex(days, weekId = 8, currentWeekId = 9, today = monday.plusDays(2)),
        )
    }

    @Test
    fun `если сегодняшнего дня нет в списке неделя открывается с понедельника`() {
        // Воскресенье без занятий daysForWeek не включает.
        val days = daysWithEmptyTuesday()

        assertEquals(
            0,
            weekStartIndex(days, weekId = 9, currentWeekId = 9, today = sunday),
        )
    }
}
