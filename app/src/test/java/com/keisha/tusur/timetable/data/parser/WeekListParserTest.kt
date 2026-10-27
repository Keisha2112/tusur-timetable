package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.WeekKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.LocalDate

class WeekListParserTest {

    private val parser = WeekListParser()

    @Test
    fun `список недель разбирается со страницы группы`() {
        val weeks = parser.parse(readFixture("group_714-2.html"))

        assertEquals(23, weeks.size)
        assertEquals("id недель не должны повторяться", 23, weeks.map { it.id }.distinct().size)
        assertEquals("в списке должна быть ровно одна текущая неделя", 1, weeks.count { it.isCurrent })
        assertEquals((1..23).toList(), weeks.map { it.number })

        val first = weeks.first()
        assertEquals(839, first.id)
        assertEquals(1, first.number)
        assertEquals(LocalDate.of(2026, 8, 31), first.startDate)
        assertEquals(WeekKind.THEORETICAL, first.kind)
        assertFalse(first.isCurrent)
    }

    @Test
    fun `текущая неделя определяется по current-week`() {
        val weeks = parser.parse(readFixture("group_714-2.html"))

        val current = weeks.single { it.isCurrent }
        assertEquals(847, current.id)
        assertEquals(9, current.number)
        assertEquals(LocalDate.of(2026, 10, 26), current.startDate)
    }

    @Test
    fun `типы недель и даты через границу года`() {
        val weeks = parser.parse(readFixture("group_714-2.html")).associateBy { it.id }

        assertEquals(WeekKind.EXAM_SESSION, weeks.getValue(844).kind)
        assertEquals(LocalDate.of(2026, 10, 5), weeks.getValue(844).startDate)

        assertEquals(WeekKind.WEEKEND, weeks.getValue(856).kind)
        assertEquals(LocalDate.of(2026, 12, 28), weeks.getValue(856).startDate)

        assertEquals(WeekKind.WEEKEND, weeks.getValue(857).kind)
        assertEquals(LocalDate.of(2027, 1, 4), weeks.getValue(857).startDate)

        assertEquals(WeekKind.EXAM_SESSION, weeks.getValue(861).kind)
        assertEquals(LocalDate.of(2027, 2, 1), weeks.getValue(861).startDate)

        assertEquals(
            mapOf(
                WeekKind.THEORETICAL to 17,
                WeekKind.EXAM_SESSION to 4,
                WeekKind.WEEKEND to 2,
            ),
            weeks.values.groupingBy { it.kind }.eachCount(),
        )
    }

    @Test
    fun `русские месяцы и двойные пробелы в дате разбираются`() {
        val weeks = parser.parse(readFixture("group_714-2.html")).associateBy { it.number }

        // В разметке: «с  7 сентября 2026».
        assertEquals(LocalDate.of(2026, 9, 7), weeks.getValue(2).startDate)
        assertEquals(LocalDate.of(2026, 9, 14), weeks.getValue(3).startDate)
        assertEquals(LocalDate.of(2026, 11, 2), weeks.getValue(10).startDate)
    }

    @Test
    fun `список недель не зависит от группы`() {
        val from714 = parser.parse(readFixture("group_714-2.html"))
        val from716 = parser.parse(readFixture("group_716-1.html"))

        assertEquals(23, from716.size)
        assertEquals(from714, from716)
    }

    @Test
    fun `пустой html даёт пустой список`() {
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("<html><body></body></html>").isEmpty())
        assertTrue(parser.parse("""<ul class="weeks"></ul>""").isEmpty())
    }

    @Test
    fun `li без ссылки, week_id и с битой датой пропускается`() {
        val html = """
            <ul class="weeks">
              <li class="tile"><div>1 нечётная</div><div>с 31 августа 2026</div></li>
              <li class="tile"><a href="/faculties/fb/groups/714-2"><div>2 чётная</div><div>с 7 сентября 2026</div></a></li>
              <li class="tile"><a href="/faculties/fb/groups/714-2?week_id=900"><div>3 нечётная</div><div>с 31 февраля 2026</div></a></li>
              <li class="tile"><a href="/faculties/fb/groups/714-2?week_id=901"><div>4 чётная</div><div>с 14 сентября 2026</div></a></li>
            </ul>
        """.trimIndent()

        val weeks = parser.parse(html)

        assertEquals(listOf(901), weeks.map { it.id })
        assertEquals(LocalDate.of(2026, 9, 14), weeks.single().startDate)
    }

    @Test
    fun `дубли week_id устраняются`() {
        val html = """
            <ul class="weeks">
              <li class="tile day_color_theoretical">
                <a href="/faculties/fb/groups/714-2?week_id=839"><div>1 нечётная</div><div>с 31 августа 2026</div></a>
              </li>
              <li class="tile day_color_exam_session current-week">
                <a href="/faculties/fb/groups/714-2?week_id=839"><div>1 нечётная</div><div>с 31 августа 2026</div></a>
              </li>
            </ul>
        """.trimIndent()

        val weeks = parser.parse(html)

        assertEquals(1, weeks.size)
        assertEquals(839, weeks.single().id)
    }

    @Test
    fun `класс current без current-week не делает неделю текущей`() {
        val html = """
            <ul class="weeks">
              <li class="tile current">
                <a href="/faculties/fb/groups/714-2?week_id=839"><div>1 нечётная</div><div>с 31 августа 2026</div></a>
              </li>
              <li class="tile day_color_theoretical current-week">
                <a href="/faculties/fb/groups/714-2?week_id=840"><div>2 чётная</div><div>с 7 сентября 2026</div></a>
              </li>
            </ul>
        """.trimIndent()

        val weeks = parser.parse(html)

        assertFalse(weeks.first().isCurrent)
        assertEquals(WeekKind.THEORETICAL, weeks.first().kind)
        assertTrue(weeks.last().isCurrent)
    }

    /** Фикстуры — реальные снимки сайта в UTF-8, поэтому читаем их напрямую с явной кодировкой. */
    private fun readFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: error("Фикстура не найдена: fixtures/$name")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
