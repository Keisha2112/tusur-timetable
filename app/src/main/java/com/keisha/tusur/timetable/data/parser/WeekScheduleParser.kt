package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/**
 * Занятия одной недели со страницы группы.
 *
 * Сайт отдаёт `.ics` только для текущего учебного периода, а HTML умеет показать
 * любую опубликованную неделю. Для будущих недель это единственный источник,
 * поэтому расписание догружается по `?week_id=`.
 *
 * В HTML есть несколько копий расписания для разных экранов. Берём
 * `table.screen-reader-element`: в ней ровно по одному занятию на ячейку, без
 * повторов из десктопной и мобильной вёрстки.
 */
class WeekScheduleParser {

    data class ParsedWeek(
        val lessons: List<Lesson>,
        /**
         * На странице была таблица расписания. Если её нет и нет явной плашки
         * «расписание не опубликовано», ответ считается битым, а не пустой неделей.
         */
        val hasSchedule: Boolean,
    )

    fun parse(html: String, week: Week): ParsedWeek {
        if (html.isBlank()) return ParsedWeek(emptyList(), hasSchedule = false)

        val document = Jsoup.parse(html)
        val table = document.selectFirst("table.screen-reader-element")
            ?: return ParsedWeek(emptyList(), hasSchedule = false)

        // Проверяем дату первой строки. Если сайт проигнорировал `?week_id=`
        // и отдал другую неделю, нельзя сохранять её под датами запрошенной.
        val firstDay = table.selectFirst("tr td")?.text().orEmpty()
        if (!matchesWeek(firstDay, week)) {
            return ParsedWeek(emptyList(), hasSchedule = false)
        }

        val lessons = table.select("tr")
            .flatMap { row -> parseRow(row, week) }
            .sortedWith(compareBy<Lesson> { it.date }.thenBy { it.start }.thenBy { it.discipline })

        return ParsedWeek(lessons, hasSchedule = true)
    }

    private fun parseRow(row: Element, week: Week): List<Lesson> {
        val cells = row.select("td")
        if (cells.size < 3) return emptyList()

        val dayOffset = dayOffset(cells.firstOrNull()?.text().orEmpty()) ?: return emptyList()
        val date = week.startDate.plusDays(dayOffset.toLong())

        // После дня идут пары ячеек: время и занятия в это время.
        return (1 until cells.lastIndex step 2).flatMap { index ->
            val timeCell = cells[index]
            val lessonCell = cells[index + 1]
            val (start, end) = parseTimeRange(timeCell.text()) ?: return@flatMap emptyList()

            lessonCell.select(".lesson-cell .training").mapNotNull { training ->
                parseLesson(training, lessonCell, date, start, end)
            }
        }
    }

    private fun parseLesson(
        training: Element,
        lessonCell: Element,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
    ): Lesson? {
        val disciplineElement = training.selectFirst("span.discipline") ?: return null
        val discipline = disciplineElement.selectFirst("abbr[title]")?.attr("title")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: disciplineElement.text().trim()
        if (discipline.isEmpty()) return null

        val rawType = training.selectFirst("span.kind")?.text()?.trim().orEmpty()
        val lessonId = disciplineElement.attr("data-lesson-id")
            .trim()
            .ifEmpty { "$date" + "T" + ScheduleTime.formatTime(start) + "|" + discipline }

        return Lesson(
            id = lessonId,
            date = date,
            start = start,
            end = end,
            pairNumber = ScheduleTime.pairNumber(start),
            discipline = discipline,
            type = LessonTypeMapper.map(rawType),
            rawType = rawType,
            rooms = parseRooms(training),
            teachers = parseTeachers(training, lessonCell, lessonId),
        )
    }

    private fun parseRooms(training: Element): List<String> {
        val container = training.selectFirst("span.auditoriums") ?: return emptyList()
        val values = container.select("i").map { it.text() }
            .ifEmpty { container.select("a").map { it.text() } }
            .ifEmpty { container.text().split(',') }
        return values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun parseTeachers(
        training: Element,
        lessonCell: Element,
        lessonId: String,
    ): List<String> {
        // В модальном окне занятия лежат полные ФИО. В видимой карточке длинный
        // список преподавателей обрезается («Гужов Ф...»), поэтому берём полный
        // вариант, а при его отсутствии — скрытую печатную копию.
        val modal = if (lessonId.matches(NUMERIC_ID)) {
            training.ownerDocument()?.selectFirst(
                "div.js-lesson-info-modal[data-lesson-id='$lessonId'] .modal-info-teachers",
            )
        } else {
            null
        }
        val fullNames = modal?.select("a")?.map { it.text() }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (fullNames.isNotEmpty()) return fullNames.distinct()

        val container = lessonCell.selectFirst("div.for_print span.group")
            ?: training.selectFirst("span.group")
            ?: return emptyList()
        val values = container.select("a").map { it.text() }
            .ifEmpty { container.text().split(',') }
        return values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Сверяет дату из первой строки таблицы с запрошенной неделей.
     * Год на странице не печатается, поэтому сравниваем день и месяц.
     */
    private fun matchesWeek(text: String, week: Week): Boolean {
        val match = DATE.find(text) ?: return false
        val day = match.groupValues[1].toIntOrNull() ?: return false
        val month = MONTHS[match.groupValues[2].lowercase().take(3)] ?: return false
        return day == week.startDate.dayOfMonth && month == week.startDate.monthValue
    }

    /** В таблице дни подписаны как «пн, 26 окт.», «вт, 27 окт.» и т. д. */
    private fun dayOffset(text: String): Int? {
        val day = DAY.find(text)?.groupValues?.get(1)?.lowercase() ?: return null
        return DAYS[day]
    }

    /** В ячейке времени две строки: «08:50» и «10:25». */
    private fun parseTimeRange(text: String): Pair<LocalTime, LocalTime>? {
        val times = TIME.findAll(text).toList()
        if (times.size < 2) return null
        val start = toTime(times[0]) ?: return null
        val end = toTime(times[1]) ?: return null
        return start to end
    }

    private fun toTime(match: MatchResult): LocalTime? = try {
        LocalTime.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
        )
    } catch (_: Exception) {
        null
    }

    private companion object {
        val DAY = Regex("""(пн|вт|ср|чт|пт|сб|вс)""", RegexOption.IGNORE_CASE)
        val TIME = Regex("""(\d{1,2}):(\d{2})""")
        val DATE = Regex("""(\d{1,2})\s+([а-яё]+)""", RegexOption.IGNORE_CASE)
        val NUMERIC_ID = Regex("""\d+""")

        val MONTHS = mapOf(
            "янв" to 1,
            "фев" to 2,
            "мар" to 3,
            "апр" to 4,
            "май" to 5,
            "мая" to 5,
            "июн" to 6,
            "июл" to 7,
            "авг" to 8,
            "сен" to 9,
            "окт" to 10,
            "ноя" to 11,
            "дек" to 12,
        )

        val DAYS = mapOf(
            "пн" to 0,
            "вт" to 1,
            "ср" to 2,
            "чт" to 3,
            "пт" to 4,
            "сб" to 5,
            "вс" to 6,
        )
    }
}
