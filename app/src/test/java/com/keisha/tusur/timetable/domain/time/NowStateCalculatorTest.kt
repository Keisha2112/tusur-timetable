package com.keisha.tusur.timetable.domain.time

import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonStatus
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.model.WeekPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class NowStateCalculatorTest {
    private val calculator = NowStateCalculator()
    private val monday = LocalDate.of(2026, 11, 23)
    private val week = Week(851, 13, monday, WeekKind.THEORETICAL, true)
    private val first = lesson("Физика", monday, 8, 50, 10, 25)
    private val next = lesson("Математика", monday, 10, 40, 12, 15)
    private val tomorrow = lesson("История", monday.plusDays(1), 15, 0, 16, 35)

    @Test
    fun `state changes at exact start and end and shows the full break`() {
        val schedule = schedule(first, next, tomorrow)
        val before = calculator.calculate(schedule, at(monday, 8, 49, 59))
        assertEquals(NowState.BeforeFirst(first, Duration.ofSeconds(1)), before.state)
        assertEquals(at(monday, 8, 50), before.nextUpdate)
        val running = calculator.calculate(schedule, at(monday, 8, 50)).state as NowState.Running
        assertEquals(0f, running.current.single().progress, 0f)
        assertEquals(Duration.ofMinutes(95), running.current.single().remaining)
        assertEquals(
            NowState.Break(next, Duration.ofMinutes(15), Duration.ofMinutes(15)),
            calculator.calculate(schedule, at(monday, 10, 25)).state,
        )
        val finished = calculator.calculate(schedule, at(monday, 12, 15))
        assertEquals(NowState.Finished(tomorrow), finished.state)
        assertEquals(at(monday.plusDays(1), 0, 0), finished.nextUpdate)
    }

    @Test
    fun `holiday removes cached lessons and provides the next valid day`() {
        val schedule = schedule(first, tomorrow).copy(holidays = listOf(HolidayDay(monday)))
        val result = calculator.calculate(schedule, at(monday, 9, 0))
        assertEquals(NowState.DayOff(NowState.DayOff.Reason.HOLIDAY, tomorrow), result.state)
        assertTrue(result.isHoliday)
        assertEquals(at(monday.plusDays(1), 0, 0), result.nextUpdate)
    }

    @Test
    fun `confirmed empty unpublished and cancelled days are distinguishable`() {
        val time = at(monday, 9, 0)
        assertEquals(NowState.DayOff.Reason.EMPTY, (calculator.calculate(schedule(), time).state as NowState.DayOff).reason)
        val unpublished = schedule().copy(publications = mapOf(week.id to WeekPublication.UNPUBLISHED))
        assertEquals(NowState.DayOff.Reason.UNPUBLISHED, (calculator.calculate(unpublished, time).state as NowState.DayOff).reason)
        val cancelled = schedule(first.copy(status = LessonStatus.CANCELLED), tomorrow)
        assertEquals(NowState.DayOff(NowState.DayOff.Reason.CANCELLED, tomorrow), calculator.calculate(cancelled, time).state)
    }

    @Test
    fun `tomsk midnight keeps a lesson with an explicit next-day ending active`() {
        val lesson = lesson("Ночная работа", monday, 23, 55, 0, 20).copy(endDate = monday.plusDays(1))
        val result = calculator.calculate(schedule(lesson), Instant.parse("2026-11-23T17:00:00Z"))
        assertEquals(monday.plusDays(1), result.date)
        assertEquals(lesson, (result.state as NowState.Running).current.single().lesson)
    }

    @Test
    fun `conflicting notes and unknown endings do not produce a false progress bar`() {
        val meeting = lesson("Проектирование", monday, 13, 15, 14, 50).copy(note = "с 12:30")
        assertEquals(NowState.UncertainTime(meeting), calculator.calculate(schedule(meeting), at(monday, 12, 35)).state)
        val noEnd = first.copy(end = first.start)
        assertEquals(NowState.UncertainTime(noEnd), calculator.calculate(schedule(noEnd), at(monday, 9, 15)).state)
    }

    @Test
    fun `gap starts at the last actual end of overlapping lessons`() {
        val parallel = lesson("Практика", monday, 8, 55, 9, 40)
        val schedule = schedule(first, parallel, next)
        assertEquals(2, (calculator.calculate(schedule, at(monday, 9, 0)).state as NowState.Running).current.size)
        val breakState = calculator.calculate(schedule, at(monday, 10, 30)).state as NowState.Break
        assertEquals(Duration.ofMinutes(15), breakState.length)
        assertEquals(Duration.ofMinutes(10), breakState.remaining)
    }

    private fun at(date: LocalDate, hour: Int, minute: Int, second: Int = 0): Instant =
        date.atTime(hour, minute, second).atZone(ScheduleTime.ZONE).toInstant()

    private fun schedule(vararg lessons: Lesson) = GroupSchedule(
        group = Group("fb", "714-2", "714-2"),
        weeks = listOf(week),
        lessons = lessons.toList(),
        fetchedAt = at(monday, 0, 0),
        publications = mapOf(week.id to WeekPublication.PUBLISHED),
    )

    private fun lesson(name: String, date: LocalDate, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) =
        Lesson(
            id = name,
            date = date,
            start = LocalTime.of(startHour, startMinute),
            end = LocalTime.of(endHour, endMinute),
            discipline = name,
            type = LessonType.PRACTICE,
            rawType = "Практика",
        )
}
