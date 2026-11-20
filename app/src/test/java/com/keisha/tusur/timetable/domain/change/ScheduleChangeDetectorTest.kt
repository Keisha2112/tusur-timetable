package com.keisha.tusur.timetable.domain.change

import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonStatus
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ScheduleChangeDetectorTest {
    private val detector = ScheduleChangeDetector()
    private val monday = LocalDate.of(2026, 11, 23)
    private val now = monday.atStartOfDay(ScheduleTime.ZONE).toInstant()
    private val lesson = Lesson(
        id = "42",
        siteSeriesId = "42",
        date = monday.plusDays(2),
        start = LocalTime.of(10, 40),
        end = LocalTime.of(12, 15),
        discipline = "Структуры данных",
        type = LessonType.PRACTICE,
        rawType = "Практика",
        rooms = listOf("улк 312", "рк 406"),
        teachers = listOf("Иванов И. И.", "Петрова А.В."),
    )

    @Test
    fun `first snapshot and normalization produce no events`() {
        val baseline = apply(null, snapshot(now, lesson))
        assertTrue(baseline.changes.isEmpty())
        val same = lesson.copy(
            note = "Информация для студентов",
            rawType = " ПРАКТИКА",
            rooms = listOf(" рк406 ", "УЛК 312"),
            teachers = listOf("Петрова Анна Владимировна", "Иванов Иван Иванович"),
        )
        assertTrue(apply(baseline.baseline, snapshot(now.plusSeconds(60), same)).changes.isEmpty())
        assertTrue(apply(baseline.baseline, snapshot(now.plusSeconds(60), same), generation = "new").changes.isEmpty())
    }

    @Test
    fun `room teacher type and operative note produce one change`() {
        val baseline = apply(null, snapshot(now, lesson)).baseline
        val changed = lesson.copy(
            rooms = listOf("гк 312"),
            teachers = listOf("Сидоров О.П."),
            type = LessonType.LABORATORY,
            rawType = "Лабораторная работа",
            note = "в ЭИОС",
        )
        val result = apply(baseline, snapshot(now.plusSeconds(60), changed))
        assertEquals(setOf(ChangeKind.ROOM, ChangeKind.TEACHER, ChangeKind.TYPE, ChangeKind.NOTE), result.changes.single().kinds)
        assertEquals(lesson, result.changes.single().before)
        assertEquals(changed, result.changes.single().after)
        assertTrue(apply(result.baseline, snapshot(now.plusSeconds(120), changed.copy(note = "реализуется в ЭИОС"))).changes.isEmpty())
    }

    @Test
    fun `same series in another week is preserved when one occurrence moves`() {
        val original = lesson.copy(date = monday.plusDays(5))
        val other = lesson.copy(date = monday.plusDays(7))
        val moved = original.copy(date = monday.plusDays(8), start = LocalTime.of(13, 15), end = LocalTime.of(14, 50))
        val initial = mapOf(
            WEEK to week(monday, now, original),
            WEEK + 1 to week(monday.plusWeeks(1), now, other),
        )
        val baseline = apply(null, initial).baseline
        val time = now.plusSeconds(30)
        val latest = mapOf(
            WEEK to week(monday, time),
            WEEK + 1 to week(monday.plusWeeks(1), time, moved, other),
        )
        val result = apply(baseline, latest)
        assertEquals(setOf(ChangeKind.DATE, ChangeKind.TIME), result.changes.single().kinds)
        assertEquals(moved, result.changes.single().after)
    }

    @Test
    fun `equally plausible rescheduling is reported once without claiming a specific move`() {
        val first = lesson.copy(id = "1", siteSeriesId = null)
        val second = lesson.copy(id = "2", siteSeriesId = null, start = LocalTime.of(13, 15))
        val baseline = apply(null, snapshot(now, first, second)).baseline
        val later = first.copy(date = first.date.plusDays(1))
        val latest = snapshot(now.plusSeconds(60), later)
        val result = apply(baseline, latest)
        assertEquals(setOf(ChangeKind.SCHEDULE), result.changes.single().kinds)
        assertTrue(apply(result.baseline, latest).changes.isEmpty())
    }

    @Test
    fun `deletion needs a second verification and a returned row is silent`() {
        val baseline = apply(null, snapshot(now, lesson)).baseline
        val firstMissing = apply(baseline, snapshot(now.plusSeconds(60)))
        assertTrue(firstMissing.changes.isEmpty())
        val stillTooEarly = apply(firstMissing.baseline, snapshot(now.plusSeconds(2 * 60)))
        assertTrue(stillTooEarly.changes.isEmpty())
        val confirmed = apply(stillTooEarly.baseline, snapshot(now.plusSeconds(16 * 60)))
        assertEquals(setOf(ChangeKind.REMOVED), confirmed.changes.single().kinds)
        assertTrue(apply(stillTooEarly.baseline, snapshot(now.plusSeconds(8 * 60), lesson)).changes.isEmpty())
        assertTrue(apply(confirmed.baseline, snapshot(now.plusSeconds(17 * 60))).changes.isEmpty())
    }

    @Test
    fun `explicit cancellation restoration and solitary addition are distinct`() {
        val baseline = apply(null, snapshot(now, lesson)).baseline
        val cancelled = lesson.copy(status = LessonStatus.CANCELLED, note = "Занятие отменено")
        val cancellation = apply(baseline, snapshot(now.plusSeconds(60), cancelled))
        assertEquals(setOf(ChangeKind.CANCELLED), cancellation.changes.single().kinds)
        assertTrue(apply(cancellation.baseline, snapshot(now.plusSeconds(120))).changes.isEmpty())
        val another = lesson.copy(id = "9", siteSeriesId = "9", discipline = "Физика")
        val restored = apply(cancellation.baseline, snapshot(now.plusSeconds(120), lesson, another))
        assertEquals(
            setOf(setOf(ChangeKind.RESTORED), setOf(ChangeKind.ADDED)),
            restored.changes.map { it.kinds }.toSet(),
        )
    }

    @Test
    fun `holiday masks and expired baselines produce no false additions or removals`() {
        val baseline = apply(null, snapshot(now, lesson)).baseline
        val calendar = mapOf(WEEK to week(monday, now.plusSeconds(60)).copy(holidays = listOf(HolidayDay(lesson.date))))
        val masked = apply(baseline, calendar)
        val restored = apply(masked.baseline, snapshot(now.plusSeconds(120), lesson))
        assertTrue(masked.changes.isEmpty())
        assertTrue(restored.changes.isEmpty())

        val late = now.plus(Duration.ofHours(73))
        assertTrue(apply(baseline, snapshot(late, lesson.copy(date = monday.plusDays(4), rooms = listOf("гк 101")))).changes.isEmpty())
    }

    @Test
    fun `unavailable adjacent week postpones unmatched changes`() {
        val adjacent = Week(WEEK + 1, 2, monday.plusWeeks(1), WeekKind.THEORETICAL, false)
        val initial = lesson.copy(date = monday.plusDays(6))
        val baseline = apply(null, snapshot(now, initial)).baseline
        val time = now.plusSeconds(20 * 60)
        val latest = snapshot(time, initial.copy(rooms = listOf("гк 309")))
        val benign = apply(baseline, latest, unavailable = listOf(adjacent))
        assertEquals(setOf(ChangeKind.ROOM), benign.changes.single().kinds)
        assertTrue(apply(benign.baseline, snapshot(time.plusSeconds(60)), unavailable = listOf(adjacent)).changes.isEmpty())
    }

    private fun apply(
        previous: ScheduleBaseline?,
        latest: Map<Int, ComparedWeek>,
        unavailable: List<Week> = emptyList(),
        generation: String = "generation",
    ): ScheduleComparison = detector.compare(
        generation, previous, latest, unavailable, Instant.ofEpochMilli(latest.values.maxOf { it.checkedAt }),
    )

    private fun snapshot(time: Instant, vararg lessons: Lesson) = mapOf(WEEK to week(monday, time, *lessons))

    private fun week(start: LocalDate, time: Instant, vararg lessons: Lesson) =
        ComparedWeek(start, time.toEpochMilli(), lessons.toList(), emptyList())

    private companion object { const val WEEK = 851 }
}
