package com.keisha.tusur.timetable.domain.time

import com.keisha.tusur.timetable.domain.model.DaySchedule
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleTimeTest {

    private fun lesson(
        date: LocalDate,
        start: String = "08:50",
        end: String = "10:25",
        discipline: String = "Математический анализ",
    ) = Lesson(
        id = "$date-$start-$discipline",
        date = date,
        start = LocalTime.parse(start),
        end = LocalTime.parse(end),
        pairNumber = ScheduleTime.pairNumber(LocalTime.parse(start)),
        discipline = discipline,
        type = LessonType.LECTURE,
        rawType = "Лекция",
    )

    private fun week(
        id: Int,
        start: String,
        isCurrent: Boolean = false,
        number: Int? = id,
    ) = Week(
        id = id,
        number = number,
        startDate = LocalDate.parse(start),
        kind = WeekKind.THEORETICAL,
        isCurrent = isCurrent,
    )

    @Test
    fun `pair numbers follow the bell schedule`() {
        assertEquals(1, ScheduleTime.pairNumber(LocalTime.of(8, 50)))
        assertEquals(2, ScheduleTime.pairNumber(LocalTime.of(10, 40)))
        assertEquals(3, ScheduleTime.pairNumber(LocalTime.of(13, 15)))
        assertEquals(4, ScheduleTime.pairNumber(LocalTime.of(15, 0)))
        assertEquals(5, ScheduleTime.pairNumber(LocalTime.of(16, 45)))
        assertEquals(6, ScheduleTime.pairNumber(LocalTime.of(18, 30)))
        assertEquals(7, ScheduleTime.pairNumber(LocalTime.of(20, 15)))
    }

    @Test
    fun `non standard start time has no pair number`() {
        assertNull(ScheduleTime.pairNumber(LocalTime.of(9, 5)))
        assertNull(ScheduleTime.pairNumber(LocalTime.of(12, 40)))
    }

    @Test
    fun `week starts on monday and ends on sunday`() {
        val thursday = LocalDate.of(2026, 10, 29)
        val monday = ScheduleTime.weekStart(thursday)
        assertEquals(LocalDate.of(2026, 10, 26), monday)
        assertEquals(LocalDate.of(2026, 11, 1), ScheduleTime.weekEnd(monday))
    }

    @Test
    fun `week start is idempotent for monday`() {
        val monday = LocalDate.of(2026, 10, 26)
        assertEquals(monday, ScheduleTime.weekStart(monday))
    }

    @Test
    fun `days for week hides empty sunday but keeps other days`() {
        val monday = LocalDate.of(2026, 10, 26)
        val lessons = listOf(
            lesson(monday, "10:40", "12:15"),
            lesson(monday, "08:50", "10:25"),
            lesson(monday.plusDays(2), "13:15", "14:50"),
        )

        val days: List<DaySchedule> = ScheduleTime.daysForWeek(lessons, monday)

        assertEquals(6, days.size)
        assertFalse(days.any { it.date.dayOfWeek.value == 7 })
        assertEquals(listOf("08:50", "10:40"), days.first().lessons.map { it.start.toString() })
        assertTrue(days[2].lessons.isNotEmpty())
        assertTrue(days[1].lessons.isEmpty())
    }

    @Test
    fun `sunday with lessons is displayed`() {
        val monday = LocalDate.of(2026, 10, 26)
        val sundayLesson = lesson(monday.plusDays(6), "10:40", "12:15")

        val days = ScheduleTime.daysForWeek(listOf(sundayLesson), monday)

        assertEquals(7, days.size)
        assertEquals(1, days.last().lessons.size)
    }

    @Test
    fun `lessons outside the week are ignored`() {
        val monday = LocalDate.of(2026, 10, 26)
        val lessons = listOf(
            lesson(monday.minusDays(1)),
            lesson(monday.plusDays(7)),
            lesson(monday.plusDays(1)),
        )

        val days = ScheduleTime.daysForWeek(lessons, monday)

        assertEquals(1, days.sumOf { it.lessons.size })
    }

    @Test
    fun `find week covers monday through sunday`() {
        val weeks = listOf(
            week(8, "2026-10-19"),
            week(9, "2026-10-26"),
            week(10, "2026-11-02"),
        )

        assertEquals(9, ScheduleTime.findWeek(weeks, LocalDate.of(2026, 10, 26))?.id)
        assertEquals(9, ScheduleTime.findWeek(weeks, LocalDate.of(2026, 11, 1))?.id)
        assertEquals(10, ScheduleTime.findWeek(weeks, LocalDate.of(2026, 11, 2))?.id)
        assertNull(ScheduleTime.findWeek(weeks, LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `current week is calculated by dates even if server flag points elsewhere`() {
        val weeks = listOf(
            week(9, "2026-10-26", isCurrent = true),
            week(10, "2026-11-02", isCurrent = false),
        )

        val current = ScheduleTime.currentWeek(weeks, LocalDate.of(2026, 11, 4))

        assertEquals(10, current?.id)
    }

    @Test
    fun `current week falls back to server flag when date is outside the list`() {
        val weeks = listOf(
            week(9, "2026-10-26", isCurrent = true),
            week(10, "2026-11-02"),
        )

        val current = ScheduleTime.currentWeek(weeks, LocalDate.of(2027, 3, 1))

        assertEquals(9, current?.id)
    }

    @Test
    fun `russian date formatting keeps cyrillic`() {
        val date = LocalDate.of(2026, 10, 26)

        assertEquals("Понедельник, 26 октября", ScheduleTime.formatDayTitle(date))
        assertEquals("26 окт — 1 ноя", ScheduleTime.formatWeekRange(date))
        assertEquals("26 октября 2026", ScheduleTime.formatFullDate(date))
    }
}
