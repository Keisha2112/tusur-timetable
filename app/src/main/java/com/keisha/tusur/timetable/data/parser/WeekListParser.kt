package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month

/**
 * Список недель со страницы группы или преподавателя.
 *
 * У группы ссылки содержат `?week_id=`, у преподавателя — путь `/weeks/<id>`.
 * Месяцы разбираем по явной таблице, а не через DateTimeFormatter: названия в родительном
 * падеже («31 августа») зависят от данных CLDR в конкретной версии Android и легко
 * ломаются при смене локали. Таблица даёт одинаковый результат на JVM и на устройстве.
 */
class WeekListParser {

    fun parse(html: String): List<Week> {
        val document = Jsoup.parse(html)
        return document.select("ul.weeks li")
            .mapNotNull { item -> parseWeek(item) }
            // Одна и та же неделя иногда попадает в разметку дважды.
            .distinctBy { it.id }
    }

    private fun parseWeek(item: Element): Week? {
        // Ссылку ищем по week_id: в li может оказаться иконка или другая ссылка без параметра.
        val link = item.select("a[href]").firstOrNull { WEEK_ID.containsMatchIn(it.attr("href")) }
            ?: return null
        // Без week_id неделю нельзя запросить у сайта, поэтому такие элементы пропускаем.
        val id = WEEK_ID.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val text = item.text().replace(WHITESPACE, " ").trim()
        val startDate = parseStartDate(text) ?: return null

        return Week(
            id = id,
            number = NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            startDate = startDate,
            kind = weekKind(item),
            // Только current-week. Класс current означает неделю, выбранную в запросе,
            // а не текущую по календарю.
            isCurrent = item.hasClass("current-week"),
        )
    }

    private fun weekKind(item: Element): WeekKind = when {
        item.hasClass("day_color_exam_session") -> WeekKind.EXAM_SESSION
        item.hasClass("day_color_weekend") -> WeekKind.WEEKEND
        else -> WeekKind.THEORETICAL
    }

    /** Текст вида «с 31 августа 2026»; в разметке встречаются двойные пробелы. */
    private fun parseStartDate(text: String): LocalDate? {
        val match = START_DATE.find(text) ?: return null
        val month = MONTHS[match.groupValues[2].lowercase()] ?: return null
        return try {
            LocalDate.of(match.groupValues[3].toInt(), month, match.groupValues[1].toInt())
        } catch (e: DateTimeException) {
            // Например, 31 февраля: такую неделю лучше пропустить, чем уронить весь список.
            null
        }
    }

    private companion object {
        val WEEK_ID = Regex("""(?:[?&]week_id=|/teachers/\d+/weeks/)(\d+)""")
        val NUMBER = Regex("""^(\d+)""")
        val START_DATE = Regex(
            """с\s+(\d{1,2})\s+([а-яёА-ЯЁ]+)\s+(\d{4})""",
            RegexOption.IGNORE_CASE,
        )
        val WHITESPACE = Regex("""\s+""")

        val MONTHS = mapOf(
            "января" to Month.JANUARY,
            "февраля" to Month.FEBRUARY,
            "марта" to Month.MARCH,
            "апреля" to Month.APRIL,
            "мая" to Month.MAY,
            "июня" to Month.JUNE,
            "июля" to Month.JULY,
            "августа" to Month.AUGUST,
            "сентября" to Month.SEPTEMBER,
            "октября" to Month.OCTOBER,
            "ноября" to Month.NOVEMBER,
            "декабря" to Month.DECEMBER,
        )
    }
}
