package com.keisha.tusur.timetable.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParserTest {

    private val parser = CatalogParser()

    @Test
    fun `факультеты разбираются со страницы каталога`() {
        val faculties = parser.parseFaculties(FixtureLoader.text("faculties.html"))

        assertEquals(11, faculties.size)
        assertTrue(faculties.none { it.name.isBlank() || it.code.isBlank() })
        assertEquals("коды факультетов не должны повторяться", faculties.size, faculties.map { it.code }.distinct().size)

        val fb = faculties.single { it.code == "fb" }
        assertEquals("Факультет безопасности", fb.name)
    }

    @Test
    fun `факультеты без названия и дубли отбрасываются`() {
        val html = """
            <ul class="faculties">
              <li><a href="/faculties/fb">Факультет безопасности</a></li>
              <li><a href="/faculties/fb">Факультет безопасности (дубль)</a></li>
              <li><a href="/faculties/fvs">   </a></li>
              <li><a href="/other/place">Не факультет</a></li>
            </ul>
        """.trimIndent()

        val faculties = parser.parseFaculties(html)

        assertEquals(listOf("fb"), faculties.map { it.code })
        assertEquals("Факультет безопасности", faculties.single().name)
    }

    @Test
    fun `группы разбираются вместе с курсами`() {
        val groups = parser.parseGroups(FixtureLoader.text("groups_fb.html"), "fb")

        assertEquals(40, groups.size)
        assertTrue(groups.all { it.facultyCode == "fb" })
        assertTrue("названия групп не должны быть пустыми", groups.none { it.name.isBlank() || it.slug.isBlank() })
        assertEquals(groups.size, groups.map { it.slug }.distinct().size)

        // На странице пять курсов: 12 + 11 + 6 + 6 + 5 групп.
        assertEquals(listOf(12, 11, 6, 6, 5), (1..5).map { course -> groups.count { it.course == course } })
        assertTrue(groups.all { it.course != null })

        val third = groups.single { it.slug == "714-2" }
        assertEquals("714-2", third.name)
        assertEquals(3, third.course)

        val first = groups.single { it.slug == "716-1" }
        assertEquals("716-1", first.name)
        assertEquals(1, first.course)
    }

    @Test
    fun `курс берётся из ближайшего заголовка перед списком`() {
        // Между h2 и ul специально оставлены текст и лишние обёртки:
        // жёсткий селектор «h2 + ul» на такой разметке не работает.
        val html = """
            <h2>2 курс</h2>
            <p>Список групп:</p>
            <div><div>
              <ul class="list-inline">
                <li><a href="/faculties/fb/groups/715-1">715-1</a></li>
              </ul>
            </div></div>
            <h2>3 курс</h2>
            <ul class="list-inline">
              <li><a href="/faculties/fb/groups/714-2?week_id=847">714-2</a></li>
            </ul>
        """.trimIndent()

        val groups = parser.parseGroups(html, "fb")

        assertEquals(listOf(2, 3), groups.map { it.course })
        assertEquals(listOf("715-1", "714-2"), groups.map { it.slug })
    }

    @Test
    fun `без заголовка курса группа получает course = null`() {
        val html = """
            <div class="faculty">
              <ul class="list-inline">
                <li><a href="/faculties/fb/groups/700-1">700-1</a></li>
              </ul>
            </div>
        """.trimIndent()

        val group = parser.parseGroups(html, "fb").single()

        assertEquals("700-1", group.slug)
        assertNull(group.course)
    }

    @Test
    fun `посторонние списки на странице игнорируются`() {
        val html = """
            <h2>1 курс</h2>
            <ul class="list-inline">
              <li><a href="/faculties">Расписание</a></li>
              <li><a href="/faculties/fb/groups/716-1">716-1</a></li>
            </ul>
        """.trimIndent()

        val groups = parser.parseGroups(html, "fb")

        assertEquals(listOf("716-1"), groups.map { it.slug })
    }

    @Test
    fun `повторный разбор возвращает тот же результат`() {
        val facultiesHtml = FixtureLoader.text("faculties.html")
        val groupsHtml = FixtureLoader.text("groups_fb.html")

        // Парсер не хранит состояние между вызовами: кэш каталога можно обновлять повторно.
        assertEquals(parser.parseFaculties(facultiesHtml), parser.parseFaculties(facultiesHtml))
        assertEquals(parser.parseGroups(groupsHtml, "fb"), parser.parseGroups(groupsHtml, "fb"))
    }
}
