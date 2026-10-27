package com.keisha.tusur.timetable.domain.time

import com.keisha.tusur.timetable.domain.model.DaySchedule
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.Week
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Вся работа с датами идёт в часовом поясе Новосибирска,
 * независимо от часового пояса устройства.
 */
object ScheduleTime {

    val ZONE: ZoneId = ZoneId.of("Asia/Novosibirsk")

    private val RU: Locale = Locale("ru", "RU")

    private val DAY_TITLE = DateTimeFormatter.ofPattern("EEEE", RU)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", RU)

    /**
     * Месяцы заданы явно: ICU на Android 6 отдаёт для «MMMM» именительный падеж
     * («26 октябрь»), а в русской дате нужен родительный («26 октября»).
     */
    private val MONTHS_GENITIVE = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    private val MONTHS_SHORT = listOf(
        "янв", "фев", "мар", "апр", "май", "июн",
        "июл", "авг", "сен", "окт", "ноя", "дек",
    )

    data class Pair(val number: Int, val start: LocalTime, val end: LocalTime)

    val PAIRS: List<Pair> = listOf(
        Pair(1, LocalTime.of(8, 50), LocalTime.of(10, 25)),
        Pair(2, LocalTime.of(10, 40), LocalTime.of(12, 15)),
        Pair(3, LocalTime.of(13, 15), LocalTime.of(14, 50)),
        Pair(4, LocalTime.of(15, 0), LocalTime.of(16, 35)),
        Pair(5, LocalTime.of(16, 45), LocalTime.of(18, 20)),
        Pair(6, LocalTime.of(18, 30), LocalTime.of(20, 5)),
        Pair(7, LocalTime.of(20, 15), LocalTime.of(21, 50)),
    )

    fun today(): LocalDate = LocalDate.now(ZONE)

    fun now(): ZonedDateTime = ZonedDateTime.now(ZONE)

    /** Номер пары по времени начала, либо null для нестандартного времени. */
    fun pairNumber(start: LocalTime): Int? = PAIRS.firstOrNull { it.start == start }?.number

    fun weekStart(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun weekEnd(startDate: LocalDate): LocalDate = startDate.plusDays(6)

    fun formatTime(time: LocalTime): String = time.format(TIME)

    private fun monthGenitive(date: LocalDate): String = MONTHS_GENITIVE[date.monthValue - 1]

    private fun monthShort(date: LocalDate): String = MONTHS_SHORT[date.monthValue - 1]

    fun formatDayTitle(date: LocalDate): String {
        val weekday = date.format(DAY_TITLE).replaceFirstChar { it.titlecase(RU) }
        return "$weekday, ${date.dayOfMonth} ${monthGenitive(date)}"
    }

    fun formatWeekRange(startDate: LocalDate): String {
        val end = weekEnd(startDate)
        return "${startDate.dayOfMonth} ${monthShort(startDate)} — " +
            "${end.dayOfMonth} ${monthShort(end)}"
    }

    fun formatWeekStart(startDate: LocalDate): String =
        "${startDate.dayOfMonth} ${monthGenitive(startDate)} ${startDate.year}"

    fun formatUpdatedAt(instant: Instant): String {
        val dateTime = instant.atZone(ZONE)
        val time = dateTime.toLocalTime().format(TIME)
        return "${dateTime.dayOfMonth} ${monthGenitive(dateTime.toLocalDate())}, $time"
    }

    fun formatFullDate(date: LocalDate): String =
        "${date.dayOfMonth} ${monthGenitive(date)} ${date.year}"

    fun weekNumberParity(number: Int?): Boolean? = number?.let { it % 2 == 0 }

    /**
     * Раскладывает занятия по дням недели: понедельник — воскресенье.
     * Воскресенье попадает в результат только если там есть занятия.
     */
    fun daysForWeek(lessons: List<Lesson>, weekStart: LocalDate): List<DaySchedule> {
        val weekEnd = weekEnd(weekStart)
        val byDate = lessons
            .filter { !it.date.isBefore(weekStart) && !it.date.isAfter(weekEnd) }
            .sortedWith(compareBy<Lesson> { it.start }.thenBy { it.discipline })
            .groupBy { it.date }

        val days = (0L..6L).map { offset ->
            val date = weekStart.plusDays(offset)
            DaySchedule(date, byDate[date].orEmpty())
        }
        return days.filter { it.lessons.isNotEmpty() || it.date.dayOfWeek != DayOfWeek.SUNDAY }
    }

    /** Неделя, в которую попадает дата, с допуском по границам. */
    fun findWeek(weeks: List<Week>, date: LocalDate): Week? =
        weeks.firstOrNull { !date.isBefore(it.startDate) && !date.isAfter(weekEnd(it.startDate)) }

    /**
     * Текущая неделя: сначала ищем неделю, содержащую сегодняшнюю дату.
     * Флаг с сервера используется только как подсказка, приоритет у расчёта по датам.
     */
    fun currentWeek(weeks: List<Week>, today: LocalDate = today()): Week? =
        findWeek(weeks, today) ?: weeks.firstOrNull { it.isCurrent }

    /** Округление "обновлено N назад" не требуется: показываем дату и время. */
    fun isSameDay(first: Instant, second: Instant): Boolean =
        first.atZone(ZONE).toLocalDate() == second.atZone(ZONE).toLocalDate()

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
}
