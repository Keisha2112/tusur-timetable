package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonNotes
import com.keisha.tusur.timetable.domain.model.LessonStatus
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Занятия и календарные отметки выбранной недели на странице ТУСУРа. */
class WeekScheduleParser(
    private val weekListParser: WeekListParser = WeekListParser(),
) {
    data class ParsedWeek(
        val lessons: List<Lesson> = emptyList(),
        val publication: WeekPublication? = null,
        val holidays: List<HolidayDay> = emptyList(),
        val calendarDates: Set<LocalDate> = emptySet(),
        val weeks: List<Week> = emptyList(),
    ) {
        val hasSchedule: Boolean get() = publication == WeekPublication.PUBLISHED
    }

    fun parse(html: String, week: Week, groupUrl: String? = null): ParsedWeek =
        parse(Jsoup.parse(html), week, groupUrl)

    fun parseRoom(html: String, room: Auditorium, week: Week?): Pair<Week, ParsedWeek>? {
        val document = Jsoup.parse(html)
        val expected = week ?: weekListParser.selected(document) ?: return null
        val parsed = parse(document, expected, Urls.auditorium(room), room)
        return parsed.takeIf { it.publication != null }?.let { expected to it }
    }

    private fun parse(
        document: Element,
        week: Week,
        scheduleUrl: String?,
        room: Auditorium? = null,
    ): ParsedWeek {
        val selected = weekListParser.selected(document)
        if (selected != null && (selected.id != week.id || selected.startDate != week.startDate)) {
            return ParsedWeek()
        }
        if (scheduleUrl != null && (selected == null ||
                !matchesSchedule(weekListParser.selectedLink(document), scheduleUrl))
        ) {
            return ParsedWeek()
        }

        val table = document.selectFirst("table.screen-reader-element")
        val weeks = weekListParser.parse(document)
        if (table == null) {
            if (!isUnpublished(document)) return ParsedWeek()
            val calendar = readCalendar(document, week, unpublished = true)
            return ParsedWeek(
                publication = WeekPublication.UNPUBLISHED,
                holidays = calendar.holidays,
                calendarDates = calendar.dates,
                weeks = weeks,
            )
        }

        val calendar = readCalendar(document, week, unpublished = false)
        val rows = table.select("tr").filter { it.children().any { cell -> cell.tagName() == "td" } }
        val dates = rows.map { row ->
            val text = row.children().first { it.tagName() == "td" }.text()
            dateOf(text, week) ?: return ParsedWeek()
        }
        if (dates.firstOrNull() != week.startDate ||
            scheduleUrl != null && (dates.size !in 6..7 || dates != dates.indices.map { week.startDate.plusDays(it.toLong()) })
        ) {
            return ParsedWeek()
        }
        // Каждая раскрытая карточка имеет несколько одинаковых копий для разных представлений.
        val modals = document.select(".js-lesson-info-modal[data-lesson-id]")
            .groupBy { it.attr("data-lesson-id") }
        val holidaysByDate = calendar.holidays.associateBy { it.date }
        val lessons = mutableListOf<Lesson>()
        for ((row, date) in rows.zip(dates)) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size < 3 || cells.size % 2 == 0) return ParsedWeek()
            val holiday = holidaysByDate[date]
            if (holiday != null && holiday.closedStarts.isEmpty()) continue
            for (index in 1 until cells.lastIndex step 2) {
                val trainings = cells[index + 1].select(".lesson-cell .training")
                if (trainings.isEmpty()) continue
                val (start, end) = timeRange(cells[index].text()) ?: return ParsedWeek()
                if (holiday != null && start in holiday.closedStarts) continue
                for (training in trainings) {
                    lessons += readLesson(training, modals, date, start, end, room) ?: return ParsedWeek()
                }
            }
        }

        val entries = if (room == null) lessons else lessons.distinct()
        return ParsedWeek(
            lessons = entries.sortedWith(compareBy<Lesson> { it.date }.thenBy { it.start }.thenBy { it.discipline }),
            publication = WeekPublication.PUBLISHED,
            holidays = calendar.holidays,
            calendarDates = calendar.dates,
            weeks = weeks,
        )
    }

    private fun matchesSchedule(selectedLink: String?, scheduleUrl: String): Boolean {
        if (selectedLink.isNullOrBlank()) return false
        return runCatching { URI(selectedLink).path == URI(scheduleUrl).path }.getOrDefault(false)
    }

    private fun isUnpublished(document: Element): Boolean =
        document.select(".empty-schedule, div.text-danger > strong").any {
            val text = LessonNotes.normalize(it.text())
            text.trimEnd('.') in UNPUBLISHED
        }

    private data class DayMark(val date: LocalDate, val holiday: HolidayDay?)

    private data class Calendar(
        val dates: Set<LocalDate>,
        val holidays: List<HolidayDay>,
    )

    private fun readCalendar(document: Element, week: Week, unpublished: Boolean): Calendar {
        val selector = if (unpublished) {
            ".table-without-timetable-wrapper table.table-lessons"
        } else {
            "table.table-lessons"
        }
        val marker = if (unpublished) ".training.day_color_weekend" else ".training.custom-holiday-day"
        val marks = document.select(selector).flatMap { table ->
            val header = table.selectFirst("thead") ?: table.selectFirst("tr:has(th)")
            val cells = table.select("td.lesson_cell").groupBy { cell ->
                cell.classNames().firstNotNullOfOrNull { name ->
                    DAY_CELL.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            header?.select("th").orEmpty().mapNotNull { column ->
                val date = dateOf(column.text(), week) ?: return@mapNotNull null
                val dayCells = cells[date.dayOfWeek.value] ?: return@mapNotNull null
                val marked = dayCells.filter { cell ->
                    cell.select(marker).any { LessonNotes.normalize(it.text()) == "праздничный день" }
                }
                val holiday = when {
                    marked.isEmpty() -> null
                    unpublished -> HolidayDay(date)
                    else -> {
                        val starts = marked.map { cell ->
                            val number = cell.parent()?.classNames()?.firstNotNullOfOrNull { name ->
                                PAIR_ROW.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
                            }
                            ScheduleTime.PAIRS.firstOrNull { it.number == number }?.start
                                ?: return@mapNotNull null
                        }.distinct().sorted()
                        HolidayDay(date, starts.takeUnless { it.size == ScheduleTime.PAIRS.size }.orEmpty())
                    }
                }
                DayMark(date, holiday)
            }
        }
        // Если мобильная и настольная версии расходятся, не обновляем этот день.
        val agreed = marks.groupBy { it.date }.values.mapNotNull { copies ->
            copies.distinct().singleOrNull()
        }
        return Calendar(agreed.map { it.date }.toSet(), agreed.mapNotNull { it.holiday })
    }

    private fun readLesson(
        training: Element,
        modals: Map<String, List<Element>>,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        room: Auditorium?,
    ): Lesson? {
        val subject = training.selectFirst("span.discipline") ?: return null
        val discipline = subject.selectFirst("abbr[title]")?.attr("title")?.trim()
            ?.takeIf(String::isNotEmpty) ?: subject.text().trim()
        if (discipline.isEmpty()) return null

        val rawType = training.selectFirst("span.kind")?.text()?.trim().orEmpty()
        val seriesId = subject.attr("data-lesson-id").trim().takeIf(String::isNotEmpty)
        val modal = seriesId?.let(modals::get).orEmpty().firstOrNull {
            modalValue(it, "Дата проведения:") == date.format(MODAL_DATE) &&
                modalValue(it, "Время проведения:")?.let(::timeRange)?.first == start
        }
        val print = training.parent()?.takeIf { it.select(".training").size == 1 }
            ?.selectFirst(".for_print")
        val note = modal?.let { modalValue(it, "Комментарий:") }
            ?: training.selectFirst("span.note[title]")?.attr("title")?.trim()?.takeIf(String::isNotEmpty)
            ?: print?.selectFirst(".note")?.text()?.trim()?.takeIf(String::isNotEmpty)

        val teacherContainer = modal?.selectFirst(".modal-info-teachers")
        val teacherLinks = teacherContainer?.select("a[href]").orEmpty()
        val roomTeacherLinks = if (room == null) emptyList() else training.select("a[href]")
            .filter { TEACHER_ID.containsMatchIn(it.attr("href")) }
        val shortTeachers = if (room == null) training.selectFirst("span.group") else null
        val printedGroups = print?.select("span.group").orEmpty()
        val teachers = teacherLinks.map { it.text() }.ifEmpty {
            if (room == null) printedGroups.firstOrNull()?.text()?.split(',').orEmpty()
            else roomTeacherLinks.map(Element::text).ifEmpty {
                printedGroups.lastOrNull()?.text()?.takeUnless { it.startsWith("гр.") }?.split(',').orEmpty()
            }
        }.ifEmpty { textValues(shortTeachers, "a") }.cleanValues()
        val ids = teacherLinks.ifEmpty { roomTeacherLinks.ifEmpty { shortTeachers?.select("a[href]").orEmpty() } }
            .mapNotNull { TEACHER_ID.find(it.attr("href"))?.groupValues?.get(1) }.distinct().sorted()
        val groupLinks = if (room == null) emptyList() else training.select("a[href]").mapNotNull(::groupLink)
        val groups = if (room == null) emptyList() else roomGroups(training, printedGroups, groupLinks)

        return Lesson(
            id = seriesId ?: "$date-$start-$discipline",
            date = date,
            start = start,
            end = end,
            pairNumber = ScheduleTime.pairNumber(start),
            discipline = discipline,
            type = LessonTypeMapper.map(rawType),
            rawType = rawType,
            rooms = if (room == null) textValues(training.selectFirst("span.auditoriums"), "i, a") else listOf(room.label),
            teachers = teachers,
            groups = groups,
            groupLinks = groupLinks.distinct(),
            siteSeriesId = seriesId,
            teacherIds = ids,
            note = note,
            status = if (LessonNotes.isCancellation(note)) LessonStatus.CANCELLED else LessonStatus.SCHEDULED,
        )
    }

    private fun groupLink(element: Element): Group? {
        val url = Urls.BASE.toHttpUrl().resolve(element.attr("href")) ?: return null
        val path = url.pathSegments
        if (url.host != "timetable.tusur.ru" || path.size != 4 ||
            path[0] != "faculties" || path[2] != "groups" || path[1].isBlank() || path[3].isBlank()
        ) return null
        return Group(path[1], path[3], element.text().trim())
    }

    private fun roomGroups(training: Element, printed: List<Element>, links: List<Group>): List<String> {
        val fullList = printed.firstOrNull { it.text().startsWith("гр.") }?.text()
            ?: training.select("span.group[title]").map { it.attr("title") }.firstOrNull { it.startsWith("гр.") }
        return (fullList.orEmpty().split(',') + links.map(Group::name))
            .map { it.trim().removePrefix("гр.") }
            .cleanValues().filterNot { it.all { char -> char == '.' || char == '…' } }
    }

    private fun modalValue(modal: Element, label: String): String? {
        val row = modal.select(".modal-body p").firstOrNull { it.selectFirst("strong")?.text() == label }
            ?: return null
        val value = row.clone().apply { selectFirst("strong")?.remove() }
        return value.text().trim().takeIf(String::isNotEmpty)
    }

    private fun textValues(container: Element?, selector: String): List<String> =
        container?.let { element ->
            element.select(selector).map { it.text() }.ifEmpty { element.text().split(',') }.cleanValues()
        }.orEmpty()

    private fun List<String>.cleanValues(): List<String> =
        map(String::trim).filter(String::isNotEmpty).distinct()

    private fun dateOf(text: String, week: Week): LocalDate? {
        val match = DAY.matchEntire(text.trim()) ?: return null
        val offset = DAYS.indexOf(match.groupValues[1].lowercase())
        if (offset < 0) return null
        val date = week.startDate.plusDays(offset.toLong())
        val monthText = match.groupValues[3].lowercase().take(3).let { if (it == "мая") "май" else it }
        val month = MONTHS.indexOf(monthText) + 1
        return date.takeIf { it.dayOfMonth == match.groupValues[2].toInt() && it.monthValue == month }
    }

    private fun timeRange(text: String): Pair<LocalTime, LocalTime>? {
        val times = TIME.findAll(text).take(2).toList().map { match ->
            val hours = match.groupValues[1].toInt()
            val minutes = match.groupValues[2].toInt()
            if (hours >= 24 || minutes >= 60) return null
            LocalTime.of(hours, minutes)
        }.toList()
        if (times.size != 2 || times[1].isBefore(times[0])) return null
        return times[0] to times[1]
    }

    private companion object {
        val DAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
        val MONTHS = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
        val DAY = Regex("""([а-яё]{2}),?\s+(\d{1,2})\s+([а-яё]+)\.?""", RegexOption.IGNORE_CASE)
        val TIME = Regex("""(\d{1,2}):(\d{2})""")
        val DAY_CELL = Regex("""day_([1-7])""")
        val PAIR_ROW = Regex("""lesson_(\d+)""")
        val TEACHER_ID = Regex("""/teachers/(\d+)""")
        val MODAL_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu")
        val UNPUBLISHED = setOf(
            "на эту неделю расписание еще не опубликовано",
            "на эту неделю еще не опубликовано расписание",
            "на данную неделю расписание еще не опубликовано",
            "на данную неделю еще не опубликовано расписание",
        )
    }
}
