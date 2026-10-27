package com.keisha.tusur.timetable.data.parser

import biweekly.component.VEvent
import biweekly.io.text.ICalReader
import biweekly.property.DateOrDateTimeProperty
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Занятия из экспорта .ics.
 *
 * UID в файле нет, поэтому идентификатор строим из даты, времени начала и дисциплины:
 * этого хватает, чтобы различать занятия и узнавать их при следующем обновлении.
 */
class IcsParser {

    fun parse(ics: String, zoneId: ZoneId = ScheduleTime.ZONE): List<Lesson> {
        if (ics.isBlank()) return emptyList()

        val calendar = ICalReader(ics).use { it.readNext() } ?: return emptyList()
        return calendar.events
            .mapNotNull { event -> parseEvent(event, zoneId) }
            // В экспорте встречаются полные дубли: одинаковые дата, время, дисциплина и участники.
            .distinct()
    }

    private fun parseEvent(event: VEvent, zoneId: ZoneId): Lesson? {
        val start = event.dateStart?.toDateTime(zoneId) ?: return null
        // Событие без названия показывать нечего.
        val discipline = event.summary?.value?.trim().orEmpty()
        if (discipline.isEmpty()) return null

        // Без корректного DTEND не выдумываем длительность, а показываем занятие нулевой длины.
        val end = event.dateEnd?.toDateTime(zoneId)?.takeIf { it.isAfter(start) } ?: start

        val description = splitValues(event.description?.value)
        val rawType = description.firstOrNull().orEmpty()
        val startTime = start.toLocalTime()

        return Lesson(
            id = "${start.toLocalDate()}T${startTime.format(ID_TIME)}|$discipline",
            date = start.toLocalDate(),
            start = startTime,
            end = end.toLocalTime(),
            pairNumber = ScheduleTime.pairNumber(startTime),
            discipline = discipline,
            type = LessonTypeMapper.map(rawType),
            rawType = rawType,
            rooms = splitValues(event.location?.value),
            teachers = description.drop(1),
        )
    }

    /**
     * Время в файле записано как местное для часового пояса расписания.
     * Берём компоненты как есть: результат не зависит от часового пояса устройства
     * и от того, удалось ли biweekly разрешить TZID. Исключение — значения с «Z»,
     * они действительно заданы в UTC.
     */
    private fun DateOrDateTimeProperty.toDateTime(zoneId: ZoneId): ZonedDateTime? {
        val date = value ?: return null
        val raw = date.rawComponents ?: return null
        return if (raw.isUtc) {
            Instant.ofEpochMilli(date.time).atZone(zoneId)
        } else {
            LocalDateTime
                .of(raw.year, raw.month, raw.date, raw.hour, raw.minute, raw.second)
                .atZone(zoneId)
        }
    }

    /**
     * Элементы списка в экспорте ТУСУР разделены экранированными запятыми (`\,`).
     * biweekly снимает экранирование, поэтому остаётся разделить строку по запятым.
     */
    private fun splitValues(value: String?): List<String> =
        value.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private companion object {
        val ID_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
