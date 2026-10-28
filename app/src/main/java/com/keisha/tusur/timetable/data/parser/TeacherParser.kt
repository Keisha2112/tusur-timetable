package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.Teacher
import org.jsoup.Jsoup

/**
 * Преподаватель на сайте: разбор страницы `/teachers/<id>` и результатов общего поиска.
 *
 * Поиск по фамилии либо сразу перенаправляет на страницу преподавателя (единственное
 * совпадение), либо показывает список. Во втором случае нужного выбираем по инициалам:
 * в расписании преподаватель записан как «Петров Г.К.».
 */
class TeacherParser {

    data class Candidate(val id: String, val fullName: String)

    /** ФИО и короткое имя со страницы преподавателя. */
    fun parsePage(html: String, id: String, fallbackShortName: String): Teacher {
        val document = Jsoup.parse(html)
        val fullName = document.selectFirst("h1")
            ?.text()
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val breadcrumb = document.select("div.breadcrumbs span")
            .lastOrNull()
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 40 }

        val shortName = when {
            breadcrumb != null -> breadcrumb
            fullName != null -> shortNameFromFull(fullName)
            else -> fallbackShortName.trim()
        }
        return Teacher(
            id = id,
            shortName = shortName,
            fullName = fullName ?: fallbackShortName.trim(),
        )
    }

    /** Ссылки на преподавателей на странице результатов поиска. */
    fun parseSearchResults(html: String): List<Candidate> {
        val document = Jsoup.parse(html)
        return document.select("ul.list-inline li a[href]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val id = TEACHER_ID.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val fullName = link.text().trim()
                if (fullName.isEmpty()) null else Candidate(id = id, fullName = fullName)
            }
            .distinctBy { it.id }
    }

    /**
     * Выбирает преподавателя из результатов поиска. Если фамилия уникальна, сайт
     * вообще не показывает список, поэтому сюда попадаем только при совпадениях.
     */
    fun match(candidates: List<Candidate>, lessonTeacher: String): Candidate? {
        if (candidates.isEmpty()) return null
        val queryKey = initialsKey(lessonTeacher)
        val exact = candidates.filter { initialsKey(it.fullName) == queryKey }
        return when {
            exact.size == 1 -> exact.single()
            exact.isNotEmpty() -> exact.first()
            candidates.size == 1 -> candidates.single()
            else -> null
        }
    }

    fun teacherFromCandidate(candidate: Candidate, fallbackShortName: String): Teacher = Teacher(
        id = candidate.id,
        shortName = shortNameFromFull(candidate.fullName).ifBlank { fallbackShortName.trim() },
        fullName = candidate.fullName,
    )

    /** «Петров Глеб Константинович» → «Петров Г.К.». */
    fun shortNameFromFull(fullName: String): String {
        val parts = fullName.split(WHITESPACE).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts.single()
        val initials = parts.drop(1)
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("." )
        return "${parts.first()} $initials."
    }

    /**
     * Ключ для сравнения «Петров Г.К.» и «Петров Глеб Константинович»:
     * фамилия без регистра и первые буквы имени и отчества.
     */
    private fun initialsKey(value: String): String {
        val parts = value.replace('.', ' ')
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val surname = parts.first().lowercase().replace('ё', 'е')
        val initials = parts.drop(1)
            .take(2)
            .mapNotNull { it.firstOrNull()?.lowercaseChar() }
            .joinToString("")
        return surname + initials
    }

    private companion object {
        val TEACHER_ID = Regex("""/teachers/(\d+)""")
        val WHITESPACE = Regex("""\s+""")
    }
}
