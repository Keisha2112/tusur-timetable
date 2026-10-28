package com.keisha.tusur.timetable.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeacherParserTest {

    private val parser = TeacherParser()

    @Test
    fun `страница преподавателя даёт id, короткое и полное имя`() {
        val teacher = parser.parsePage(PAGE, id = "35144", fallbackShortName = "Жидова Л.А.")

        assertEquals("35144", teacher.id)
        assertEquals("Жидова Л.А.", teacher.shortName)
        assertEquals("Жидова Любовь Александровна", teacher.fullName)
    }

    @Test
    fun `без хлебных крошек короткое имя собирается из полного`() {
        val teacher = parser.parsePage(
            html = "<h1>Петров Глеб Константинович, доцент</h1>",
            id = "35757",
            fallbackShortName = "Петров Г.К.",
        )

        assertEquals("Петров Г.К.", teacher.shortName)
        assertEquals("Петров Глеб Константинович", teacher.fullName)
    }

    @Test
    fun `результаты поиска разбираются по ссылкам на преподавателей`() {
        val candidates = parser.parseSearchResults(RESULTS)

        assertEquals(listOf("35757", "35399"), candidates.map { it.id })
        assertEquals("Петров Глеб Константинович", candidates.first().fullName)
    }

    @Test
    fun `преподаватель выбирается по инициалам`() {
        val candidates = parser.parseSearchResults(RESULTS)

        assertEquals("35757", parser.match(candidates, "Петров Г.К.")?.id)
        assertEquals("35399", parser.match(candidates, "Петров Ю.А.")?.id)
        assertNull(parser.match(candidates, "Петров А.А."))
    }

    @Test
    fun `единственный результат берётся без совпадения инициалов`() {
        val candidates = parser.parseSearchResults(
            """<ul class="list-inline"><li><h2><a href="/teachers/1">Иванов Иван Иванович</a></h2></li></ul>""",
        )

        assertEquals("1", parser.match(candidates, "Иванов П.П.")?.id)
    }

    @Test
    fun `короткое имя собирается из полного ФИО`() {
        assertEquals("Петров Г.К.", parser.shortNameFromFull("Петров Глеб Константинович"))
        assertEquals("Иванов И.", parser.shortNameFromFull("Иванов Иван"))
        assertEquals("Платон", parser.shortNameFromFull("Платон"))
    }

    private companion object {
        val PAGE = """
            <html><body>
              <div class="breadcrumbs"><a href="/faculties">Расписание</a> &rarr; <span>Жидова Л.А.</span></div>
              <h1>Жидова Любовь Александровна, старший преподаватель</h1>
            </body></html>
        """.trimIndent()

        val RESULTS = """
            <html><body>
              <h1>Поиск: «Петров»</h1>
              <ul class="list-inline">
                <li><h2><a href="/teachers/35757">Петров Глеб Константинович</a></h2></li>
                <li><h2><a href="/teachers/35399">Петров Юрий Аркадьевич</a></h2></li>
              </ul>
            </body></html>
        """.trimIndent()
    }
}
