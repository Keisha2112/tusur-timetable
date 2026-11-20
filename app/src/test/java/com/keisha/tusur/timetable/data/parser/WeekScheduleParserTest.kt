package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.model.LessonStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WeekScheduleParserTest {

    private val parser = WeekScheduleParser()

    @Test
    fun `занятия недели берутся из html-таблицы`() {
        val week = Week(
            id = 847,
            number = 9,
            startDate = LocalDate.of(2026, 10, 26),
            kind = WeekKind.THEORETICAL,
            isCurrent = true,
        )

        val parsed = parser.parse(FixtureLoader.text("group_714-2.html"), week)

        assertTrue(parsed.hasSchedule)
        assertEquals(23, parsed.lessons.size)
        assertEquals(23, parsed.lessons.map { it.id }.distinct().size)

        val monday = parsed.lessons.single {
            it.date == LocalDate.of(2026, 10, 26) &&
                it.start == LocalTime.of(8, 50)
        }
        assertEquals("Элективные дисциплины по физической культуре и спорту", monday.discipline)
        assertEquals("Практика", monday.rawType)
        assertEquals(LessonType.PRACTICE, monday.type)
        assertEquals(1, monday.pairNumber)
        assertEquals(LocalTime.of(10, 25), monday.end)
        assertTrue(monday.teachers.any { it.startsWith("Головчук ") })

        val lecture = parsed.lessons.single {
            it.date == LocalDate.of(2026, 10, 26) &&
                it.start == LocalTime.of(13, 15)
        }
        assertEquals("Безопасность операционных систем", lecture.discipline)
        assertEquals(LessonType.LECTURE, lecture.type)
        assertEquals(listOf("улк 808"), lecture.rooms)
        assertTrue(lecture.teachers.single().startsWith("Новохрестов "))
    }

    @Test
    fun `будущая неделя получает даты от переданной недели`() {
        val week = Week(
            id = 848,
            number = 10,
            startDate = LocalDate.of(2026, 11, 2),
            kind = WeekKind.THEORETICAL,
            isCurrent = false,
        )
        val html = """
            <html><body>
              <table class="screen-reader-element">
                <tr>
                  <td>пн, 02 нояб.</td>
                  <td><span>10:40</span><span>12:15</span></td>
                  <td>
                    <div class="lessons-wrapper">
                      <div class="lesson-cell">
                        <div class="training training_type_practice">
                          <span class="discipline" data-lesson-id="42">
                            <abbr title="Структуры данных">СД</abbr>
                          </span>
                          <span class="kind">Практика</span>
                          <span class="auditoriums"><a href="/x"><i>улк 401</i></a></span>
                          <span class="group"><a href="/teachers/1">Иванов И.И.</a></span>
                        </div>
                      </div>
                    </div>
                  </td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = parser.parse(html, week)

        val lesson = parsed.lessons.single()
        assertEquals("42", lesson.id)
        assertEquals(LocalDate.of(2026, 11, 2), lesson.date)
        assertEquals("Структуры данных", lesson.discipline)
        assertEquals(2, lesson.pairNumber)
        assertEquals(LocalTime.of(10, 40), lesson.start)
        assertEquals(LocalTime.of(12, 15), lesson.end)
        assertEquals(listOf("улк 401"), lesson.rooms)
        assertEquals(listOf("Иванов И.И."), lesson.teachers)
    }

    @Test
    fun `чужая неделя не разбирается под датами запрошенной`() {
        val week = Week(
            id = 848,
            number = 10,
            startDate = LocalDate.of(2026, 11, 2),
            kind = WeekKind.THEORETICAL,
            isCurrent = false,
        )

        val parsed = parser.parse(FixtureLoader.text("group_714-2.html"), week)

        assertFalse(parsed.hasSchedule)
        assertTrue(parsed.lessons.isEmpty())
    }

    @Test
    fun `страница без таблицы не считается опубликованной неделей`() {
        val week = Week(1, 1, LocalDate.of(2026, 9, 1), WeekKind.THEORETICAL, false)
        val parsed = parser.parse(FixtureLoader.text("group_empty.html"), week)

        assertFalse(parsed.hasSchedule)
        assertTrue(parsed.lessons.isEmpty())
    }

    @Test
    fun `видимый праздник исключает пару из скрытой таблицы и экспорта`() {
        val date = LocalDate.of(2026, 11, 4)
        val week = Week(848, 10, date.minusDays(2), WeekKind.THEORETICAL, false)
        val parsed = parser.parse(
            FixtureLoader.text("holiday_714-2_november.html"), week,
            "https://timetable.tusur.ru/faculties/fb/groups/714-2",
        )

        assertEquals(WeekPublication.PUBLISHED, parsed.publication)
        assertEquals(date, parsed.holidays.single().date)
        assertTrue(parsed.holidays.single().closedStarts.isEmpty())
        assertTrue(parsed.holidays.single().blocks(IcsParser().parse(FixtureLoader.text("holiday_714-2.ics")).single()))
        assertFalse(parsed.lessons.any { it.date == date })
    }

    @Test
    fun `из неопубликованной недели извлекаются календарные даты через границу года`() {
        val week = Week(856, 18, LocalDate.of(2026, 12, 28), WeekKind.WEEKEND, false)
        val parsed = parser.parse(FixtureLoader.text("holiday_714-2_new_year.html"), week)
        assertEquals(WeekPublication.UNPUBLISHED, parsed.publication)
        assertEquals((3L..5L).map { week.startDate.plusDays(it) }, parsed.holidays.map { it.date })
        assertFalse(parsed.holidays.any { it.date == week.startDate })
        assertTrue(parsed.lessons.isEmpty())
    }

    @Test
    fun `праздник в неопубликованном расписании читается без таблицы занятий`() {
        val week = Week(848, 10, LocalDate.of(2026, 11, 2), WeekKind.THEORETICAL, false)
        val parsed = parser.parse(FixtureLoader.text("holiday_716-1_unpublished.html"), week)
        assertEquals(WeekPublication.UNPUBLISHED, parsed.publication)
        assertEquals(listOf(week.startDate.plusDays(2)), parsed.holidays.map { it.date })
    }

    @Test
    fun `комментарий о времени сохраняется отдельно в html и ics`() {
        val week = Week(849, 11, LocalDate.of(2026, 11, 9), WeekKind.THEORETICAL, false)
        val html = parser.parse(FixtureLoader.text("notes_016-1.html"), week).lessons.single()
        val ics = IcsParser().parse(FixtureLoader.text("notes_016-1.ics")).single()
        listOf(html, ics).forEach { lesson ->
            assertEquals("с 12:30", lesson.note)
            assertEquals(LocalTime.of(13, 15), lesson.start)
            assertEquals(LessonStatus.SCHEDULED, lesson.status)
            assertTrue(lesson.teachers.single().startsWith("Бирюкова"))
        }
    }

    @Test
    fun `явная отметка отмены сохраняет существующее занятие со статусом`() {
        val week = Week(849, 11, LocalDate.of(2026, 11, 9), WeekKind.THEORETICAL, false)
        val html = FixtureLoader.text("notes_016-1.html").replace("с 12:30", "Занятие отменено")
        val ics = FixtureLoader.text("notes_016-1.ics").replace("END:VEVENT", "STATUS:CANCELLED\nEND:VEVENT")

        assertEquals(LessonStatus.CANCELLED, parser.parse(html, week).lessons.single().status)
        assertEquals(LessonStatus.CANCELLED, IcsParser().parse(ics).single().status)
    }
}
