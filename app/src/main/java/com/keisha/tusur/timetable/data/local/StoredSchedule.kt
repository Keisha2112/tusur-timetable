package com.keisha.tusur.timetable.data.local

import com.keisha.tusur.timetable.domain.change.ScheduleBaseline
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LocalDateSerializer
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/** Хранит независимо проверенные представления сайта и данные для офлайн-показа. */
@Serializable
internal data class StoredSchedule(
    val format: Int,
    val group: Group,
    val weeks: List<Week> = emptyList(),
    val icsLessons: List<Lesson> = emptyList(),
    val olderLessons: List<Lesson> = emptyList(),
    val htmlWeeks: Map<Int, StoredWeek> = emptyMap(),
    val weekListCheckedAt: Long = 0,
    val baseline: ScheduleBaseline? = null,
) {
    fun display(updatedAt: Long): GroupSchedule {
        val knownWeeks = weeks.mapNotNull { week ->
            htmlWeeks[week.id]?.takeIf { it.startDate == week.startDate }?.let { week.id to it }
        }.toMap()
        val published = knownWeeks.values.filter { it.lessons != null }
        val publishedStarts = published.map { it.startDate }.toSet()
        val holidays = knownWeeks.values.flatMap { it.holidays }.sortedBy { it.date }
        val holidaysByDate = holidays.associateBy { it.date }
        val fallback = (olderLessons + icsLessons).filter {
            ScheduleTime.weekStart(it.date) !in publishedStarts
        }
        return GroupSchedule(
            group = group,
            weeks = weeks,
            lessons = (fallback + published.flatMap { it.lessons.orEmpty() })
                .filterNot { holidaysByDate[it.date]?.blocks(it) == true }
                .sortedWith(LESSON_ORDER),
            fetchedAt = Instant.ofEpochMilli(updatedAt),
            checkedWeeks = knownWeeks.mapValues { it.value.checkedAt },
            holidays = holidays,
            publications = knownWeeks.mapValues { it.value.publication },
            staleWeeks = knownWeeks.filter { (_, stored) ->
                stored.publication == WeekPublication.UNPUBLISHED &&
                    (stored.lessons != null || fallback.any { ScheduleTime.weekStart(it.date) == stored.startDate })
            }.keys,
        )
    }

    companion object {
        const val CURRENT_FORMAT = 1
        val LESSON_ORDER = compareBy<Lesson> { it.date }.thenBy { it.start }.thenBy { it.discipline }

        fun empty(group: Group): StoredSchedule = StoredSchedule(CURRENT_FORMAT, group)

        fun fromLegacy(schedule: GroupSchedule): StoredSchedule =
            empty(schedule.group).copy(weeks = schedule.weeks, olderLessons = schedule.lessons)
    }
}

@Serializable
internal data class StoredWeek(
    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,
    val checkedAt: Long,
    val etag: String? = null,
    val publication: WeekPublication,
    /** Последняя опубликованная версия, в том числе подтверждённая пустая неделя. */
    val lessons: List<Lesson>? = null,
    val holidays: List<HolidayDay> = emptyList(),
)
