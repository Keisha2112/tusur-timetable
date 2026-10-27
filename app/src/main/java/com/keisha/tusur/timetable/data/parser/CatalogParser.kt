package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.Faculty
import com.keisha.tusur.timetable.domain.model.Group
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Каталог сайта: факультеты и группы факультета.
 * Здесь только Jsoup и доменные модели, поэтому парсер проверяется обычными JVM-тестами.
 */
class CatalogParser {

    fun parseFaculties(html: String): List<Faculty> {
        val document = Jsoup.parse(html)
        return document.select("ul.faculties li a")
            .mapNotNull { link ->
                val code = FACULTY_HREF.find(link.attr("href"))?.groupValues?.get(1).orEmpty()
                val name = link.text().trim()
                if (code.isEmpty() || name.isEmpty()) null else Faculty(code, name)
            }
            // Один и тот же факультет иногда попадает в разметку дважды.
            .distinctBy { it.code }
    }

    /**
     * Курс задаёт заголовок h2 («1 курс»), он действует на следующие за ним списки групп.
     * Идём по документу в порядке появления h2 и ul, а не через жёсткую цепочку
     * «h2 + ul»: между заголовком и списком встречается текст и лишняя вложенность.
     */
    fun parseGroups(html: String, facultyCode: String): List<Group> {
        val document = Jsoup.parse(html)
        val groups = mutableListOf<Group>()
        var course: Int? = null

        for (element in document.select("h2, ul")) {
            when {
                element.tagName() == "h2" -> {
                    // Заголовок без слова «курс» сбрасывает курс:
                    // группы под ним уже нельзя привязать к предыдущему заголовку.
                    course = COURSE.find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                }

                element.hasClass("list-inline") -> groups += parseGroupList(element, facultyCode, course)
            }
        }
        return groups.distinctBy { it.slug }
    }

    private fun parseGroupList(list: Element, facultyCode: String, course: Int?): List<Group> =
        list.select("li a").mapNotNull { link ->
            // Ссылки на другие разделы (навигация, хлебные крошки) отсекаем по адресу группы.
            val slug = GROUP_HREF.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            Group(
                facultyCode = facultyCode,
                slug = slug,
                name = link.text().trim().ifEmpty { slug },
                course = course,
            )
        }

    private companion object {
        val FACULTY_HREF = Regex("""/faculties/([^/?#]+)""")
        val GROUP_HREF = Regex("""/groups/([^/?#]+)""")
        val COURSE = Regex("""(\d+)\s*курс""", RegexOption.IGNORE_CASE)
    }
}
