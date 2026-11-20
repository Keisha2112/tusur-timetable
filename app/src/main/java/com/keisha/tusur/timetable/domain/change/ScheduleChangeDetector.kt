package com.keisha.tusur.timetable.domain.change

import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonNotes
import com.keisha.tusur.timetable.domain.model.LessonStatus
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.LocalDateSerializer
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Serializable
internal enum class ChangeKind {
    ADDED, REMOVED, CANCELLED, RESTORED, DATE, TIME, ROOM, TEACHER, TYPE, NOTE, SCHEDULE,
}

@Serializable
internal data class ScheduleChange(
    val kinds: Set<ChangeKind>,
    val before: Lesson? = null,
    val after: Lesson? = null,
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
) {
    fun isRelevantAt(now: Instant): Boolean =
        listOfNotNull(before, after).any { ScheduleTime.endsAt(it).isAfter(now) }
}

@Serializable
internal data class ComparedWeek(
    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,
    val checkedAt: Long,
    val lessons: List<Lesson>,
    val holidays: List<HolidayDay>,
    val missing: List<MissingLesson> = emptyList(),
)

@Serializable
internal data class MissingLesson(val lesson: Lesson, val firstSeenAt: Long)

@Serializable
internal data class ScheduleBaseline(
    val generation: String,
    val weeks: Map<Int, ComparedWeek> = emptyMap(),
)

internal data class ScheduleComparison(val baseline: ScheduleBaseline, val changes: List<ScheduleChange>)

/** Сравнивает только проверенные HTML-недели. Даты оповещений определяются в поясе расписания. */
internal class ScheduleChangeDetector {
    fun compare(
        generation: String,
        previous: ScheduleBaseline?,
        verified: Map<Int, ComparedWeek>,
        unavailable: List<Week>,
        now: Instant,
    ): ScheduleComparison {
        val existing = previous?.takeIf { it.generation == generation }?.weeks.orEmpty()
        val checkedAt = now.toEpochMilli()
        val comparable = verified.filter { (id, latest) ->
            val old = existing[id]
            old != null && old.startDate == latest.startDate &&
                checkedAt - old.checkedAt in 0..MAX_BASELINE_AGE
        }
        val old = occurrences(comparable.keys.associateWith(existing::getValue))
            .filterNot { occurrence -> comparable.getValue(occurrence.weekId).blocks(occurrence.lesson) }
            .toMutableSet()
        val new = occurrences(comparable).filterNot { occurrence ->
            existing.getValue(occurrence.weekId).blocks(occurrence.lesson)
        }.toMutableSet()
        val matches = mutableListOf<Pair<Occurrence, Occurrence>>()
        matchUnique(old, new, matches) { occurrence ->
            occurrence.lesson.siteSeriesId?.let { it to slot(occurrence.lesson) }
        }
        matchIdentical(old, new)
        matchUnique(old, new, matches) { slot(it.lesson) }

        val newByDiscipline = new.groupBy { subject(it.lesson) }
        val candidates = old.associateWith { before ->
            newByDiscipline[subject(before.lesson)].orEmpty().filter { after ->
                !nearUnavailable(before.lesson.date, unavailable) &&
                    !nearUnavailable(after.lesson.date, unavailable) &&
                    plausible(before.lesson, after.lesson) &&
                    confidentMove(before.lesson, after.lesson)
            }
        }
        val candidateCounts = candidates.values.flatten().groupingBy { it }.eachCount()
        for ((before, choices) in candidates) {
            val after = choices.singleOrNull() ?: continue
            if (candidateCounts[after] != 1) continue
            old.remove(before)
            new.remove(after)
            matches += before to after
        }

        val changes = matches.mapNotNull { (before, after) -> difference(before.lesson, after.lesson) }
            .filter { it.isRelevantAt(now) }
            .toMutableList()

        val ambiguousBefore = mutableSetOf<Occurrence>()
        val ambiguousAfter = mutableSetOf<Occurrence>()
        val remainingByDiscipline = new.groupBy { subject(it.lesson) }
        for (before in old) {
            if (nearUnavailable(before.lesson.date, unavailable)) continue
            for (after in remainingByDiscipline[subject(before.lesson)].orEmpty()) {
                if (!plausible(before.lesson, after.lesson) || nearUnavailable(after.lesson.date, unavailable)) {
                    continue
                }
                ambiguousBefore += before
                ambiguousAfter += after
            }
        }
        val uncertainLessons = (ambiguousBefore + ambiguousAfter).map { it.lesson }
            .filter { ScheduleTime.endsAt(it).isAfter(now) }
        uncertainLessons.minByOrNull { it.date }?.let { earliest ->
            changes += ScheduleChange(setOf(ChangeKind.SCHEDULE), after = earliest, date = earliest.date)
        }

        val missing = mutableMapOf<Int, MutableList<MissingLesson>>()
        for (before in old - ambiguousBefore) {
            val lesson = before.lesson
            if (lesson.status == LessonStatus.CANCELLED || !ScheduleTime.endsAt(lesson).isAfter(now)) continue
            val recorded = existing.getValue(before.weekId).missing.firstOrNull { equivalent(it.lesson, lesson) }
            val unavailableNeighbor = nearUnavailable(lesson.date, unavailable)
            if (!unavailableNeighbor && recorded != null && checkedAt - recorded.firstSeenAt >= REMOVAL_DELAY) {
                changes += ScheduleChange(setOf(ChangeKind.REMOVED), before = lesson, date = lesson.date)
            } else {
                val since = if (unavailableNeighbor) checkedAt else recorded?.firstSeenAt ?: checkedAt
                missing.getOrPut(before.weekId, ::mutableListOf) += MissingLesson(lesson, since)
            }
        }
        val postponedAdds = mutableMapOf<Int, MutableSet<Int>>()
        for (after in new - ambiguousAfter) {
            val lesson = after.lesson
            if (!ScheduleTime.endsAt(lesson).isAfter(now)) continue
            if (nearUnavailable(lesson.date, unavailable)) {
                postponedAdds.getOrPut(after.weekId, ::mutableSetOf) += after.index
            } else if (lesson.status == LessonStatus.SCHEDULED) {
                changes += ScheduleChange(setOf(ChangeKind.ADDED), after = lesson, date = lesson.date)
            }
        }

        val earliestStored = now.atZone(ScheduleTime.ZONE).toLocalDate().minusDays(7)
        val updated = verified.mapValues { (id, week) ->
            week.copy(
                lessons = week.lessons.filterIndexed { index, _ -> index !in postponedAdds[id].orEmpty() },
                missing = missing[id].orEmpty(),
            )
        }
        return ScheduleComparison(
            baseline = ScheduleBaseline(
                generation,
                (existing + updated).filterValues { !it.startDate.isBefore(earliestStored) },
            ),
            changes = changes.sortedWith(compareBy<ScheduleChange> { it.date }.thenBy {
                (it.after ?: it.before)?.start
            }),
        )
    }

    private fun occurrences(weeks: Map<Int, ComparedWeek>): List<Occurrence> =
        weeks.flatMap { (id, week) ->
            (week.lessons + week.missing.map { it.lesson }).mapIndexedNotNull { index, lesson ->
                Occurrence(id, index, lesson).takeUnless { week.blocks(lesson) }
            }
        }

    private fun matchUnique(
        old: MutableSet<Occurrence>,
        new: MutableSet<Occurrence>,
        matches: MutableList<Pair<Occurrence, Occurrence>>,
        key: (Occurrence) -> Any?,
    ) {
        val oldByKey = old.groupBy(key)
        val newByKey = new.groupBy(key)
        for ((value, before) in oldByKey) {
            if (value == null || before.size != 1) continue
            val after = newByKey[value]?.singleOrNull() ?: continue
            matches += before.single() to after
            old.remove(before.single())
            new.remove(after)
        }
    }

    private fun matchIdentical(old: MutableSet<Occurrence>, new: MutableSet<Occurrence>) {
        val bySlot = new.groupBy { slot(it.lesson) }.mapValues { it.value.toMutableList() }
        val iterator = old.iterator()
        while (iterator.hasNext()) {
            val before = iterator.next()
            val candidates = bySlot[slot(before.lesson)] ?: continue
            val index = candidates.indexOfFirst { equivalent(before.lesson, it.lesson) }
            if (index >= 0) {
                new.remove(candidates.removeAt(index))
                iterator.remove()
            }
        }
    }

    private fun equivalent(before: Lesson, after: Lesson): Boolean =
        slot(before) == slot(after) && difference(before, after) == null

    private fun difference(before: Lesson, after: Lesson): ScheduleChange? {
        val kinds = buildSet {
            if (before.status != after.status) {
                add(if (after.status == LessonStatus.CANCELLED) ChangeKind.CANCELLED else ChangeKind.RESTORED)
            }
            if (before.date != after.date) add(ChangeKind.DATE)
            if (before.start != after.start || before.end != after.end ||
                endDay(before) != endDay(after)
            ) add(ChangeKind.TIME)
            if (rooms(before) != rooms(after)) add(ChangeKind.ROOM)
            if (!sameTeachers(before, after)) add(ChangeKind.TEACHER)
            if (type(before) != type(after)) add(ChangeKind.TYPE)
            if (LessonNotes.operationalKey(before.note) != LessonNotes.operationalKey(after.note)) add(ChangeKind.NOTE)
        }
        return if (kinds.isEmpty()) null else ScheduleChange(kinds, before, after, after.date)
    }

    private fun endDay(lesson: Lesson): Long =
        ChronoUnit.DAYS.between(lesson.date, lesson.endDate ?: lesson.date)

    private fun plausible(before: Lesson, after: Lesson): Boolean {
        val fromSubgroup = LessonNotes.subgroupKey(before.note)
        val toSubgroup = LessonNotes.subgroupKey(after.note)
        return before.status == LessonStatus.SCHEDULED &&
            after.status == LessonStatus.SCHEDULED &&
            abs(ChronoUnit.DAYS.between(before.date, after.date)) <= MAX_MOVE_DAYS &&
            (fromSubgroup == null || toSubgroup == null || fromSubgroup == toSubgroup)
    }

    private fun confidentMove(before: Lesson, after: Lesson): Boolean {
        if (before.siteSeriesId != null && before.siteSeriesId == after.siteSeriesId) return true
        val subgroup = LessonNotes.subgroupKey(before.note)
        val samePlace = rooms(before).isNotEmpty() && rooms(before) == rooms(after)
        return type(before) == type(after) && before.teachers.isNotEmpty() &&
            sameTeachers(before, after) && (samePlace || subgroup != null && subgroup == LessonNotes.subgroupKey(after.note))
    }

    private fun nearUnavailable(date: LocalDate, weeks: List<Week>): Boolean =
        weeks.any { date in it.startDate.minusDays(MAX_MOVE_DAYS)..it.startDate.plusDays(6 + MAX_MOVE_DAYS) }

    private fun ComparedWeek.blocks(lesson: Lesson): Boolean = holidays.any { it.blocks(lesson) }

    private fun slot(lesson: Lesson): Slot = Slot(lesson.date, lesson.start, subject(lesson))

    private fun subject(lesson: Lesson): String = LessonNotes.normalize(lesson.discipline)

    private fun rooms(lesson: Lesson): Set<String> =
        lesson.rooms.map { LessonNotes.normalize(it).replace(ROOM_PREFIX, "$1 $2") }.toSet()

    private fun type(lesson: Lesson): String =
        if (lesson.type == LessonType.OTHER) LessonNotes.normalize(lesson.rawType) else lesson.type.name

    private fun sameTeachers(before: Lesson, after: Lesson): Boolean =
        if (before.teacherIds.size == before.teachers.size && after.teacherIds.size == after.teachers.size &&
            before.teacherIds.isNotEmpty() && after.teacherIds.isNotEmpty()
        ) {
            before.teacherIds.toSet() == after.teacherIds.toSet()
        } else {
            before.teachers.map(::normalizeTeacher).toSet() == after.teachers.map(::normalizeTeacher).toSet()
        }

    private fun normalizeTeacher(value: String): String {
        val words = LessonNotes.normalize(value).replace('.', ' ').split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return ""
        val initials = words.drop(1).flatMap { word ->
            if (word.length == 2 && words.size == 2) word.toList() else listOf(word.first())
        }
        return words.first() + initials.joinToString("")
    }

    private data class Occurrence(val weekId: Int, val index: Int, val lesson: Lesson)

    private data class Slot(val date: LocalDate, val start: LocalTime, val subject: String)

    private companion object {
        const val MAX_MOVE_DAYS = 7L
        val MAX_BASELINE_AGE = Duration.ofHours(72).toMillis()
        val REMOVAL_DELAY = Duration.ofMinutes(15).toMillis()
        val ROOM_PREFIX = Regex("""^(гк|ф|мк|рк|улк)\s*(\d)""")
    }
}
