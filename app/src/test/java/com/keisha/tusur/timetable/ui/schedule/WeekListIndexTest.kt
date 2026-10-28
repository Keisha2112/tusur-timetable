package com.keisha.tusur.timetable.ui.schedule

import com.keisha.tusur.timetable.domain.model.DaySchedule
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.time.ScheduleTime
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

    private fun pairLesson(date: LocalDate, pair: Int) = Lesson(
        id = "$date-$pair",
        date = date,
        start = ScheduleTime.PAIRS[pair - 1].start,
        end = ScheduleTime.PAIRS[pair - 1].end,
        pairNumber = pair,
        discipline = "Математический анализ",
        type = LessonType.LECTURE,
        rawType = "Лекция",
    )

    private fun daysWithWindows() = listOf(
        // Понедельник: 1-я и 3-я пары, между ними окно.
        DaySchedule(monday, listOf(pairLesson(monday, 1), pairLesson(monday, 3))),
        // Вторник без занятий.
        DaySchedule(monday.plusDays(1), emptyList()),
        // Среда: только 2-я пара, до и после неё окна.
        DaySchedule(monday.plusDays(2), listOf(pairLesson(monday.plusDays(2), 2))),
    )

    @Test
    fun `последняя пара недели определяет сетку окон`() {
        assertEquals(3, weekMaxPair(daysWithWindows()))
        assertEquals(0, weekMaxPair(daysWithEmptyTuesday()))
    }

    @Test
    fun `свободные пары входят в число элементов дня`() {
        val days = daysWithWindows()
        val maxPair = weekMaxPair(days)

        // Пн: заголовок + 2 занятия + окно 2-й пары.
        assertEquals(4, dayItemCount(days[0], maxPair))
        // Вт: заголовок + заглушка.
        assertEquals(2, dayItemCount(days[1], maxPair))
        // Ср: заголовок + занятие + окна 1-й и 3-й пар.
        assertEquals(4, dayItemCount(days[2], maxPair))
    }

    @Test
    fun `прокрутка к сегодняшнему дню учитывает окна`() {
        val days = daysWithWindows()
        val maxPair = weekMaxPair(days)

        // Вторник идёт после четырёх элементов понедельника.
        assertEquals(4, dayHeaderIndex(days, monday.plusDays(1), maxPair))
        assertEquals(6, dayHeaderIndex(days, monday.plusDays(2), maxPair))
        assertEquals(
            4,
            weekStartIndex(
                days,
                weekId = 9,
                currentWeekId = 9,
                today = monday.plusDays(1),
                maxPair = maxPair,
            ),
        )
    }
}
