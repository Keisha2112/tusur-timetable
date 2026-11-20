package com.keisha.tusur.timetable.domain.time

import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonNotes
import com.keisha.tusur.timetable.domain.model.LessonStatus
import com.keisha.tusur.timetable.domain.model.WeekPublication
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal sealed interface NowState {
    data object NoSchedule : NowState

    data class Running(val current: List<RunningLesson>) : NowState

    data class BeforeFirst(val next: Lesson, val remaining: Duration) : NowState

    data class Break(val next: Lesson, val remaining: Duration, val length: Duration) : NowState

    data class Finished(val following: Lesson?) : NowState

    data class DayOff(val reason: Reason, val following: Lesson?) : NowState {
        enum class Reason { HOLIDAY, CANCELLED, EMPTY, UNPUBLISHED, UNKNOWN }
    }

    data class UncertainTime(val lesson: Lesson) : NowState
}

internal data class RunningLesson(val lesson: Lesson, val remaining: Duration, val progress: Float)

internal data class NowSnapshot(
    val date: LocalDate,
    val isHoliday: Boolean,
    val state: NowState,
    val nextUpdate: Instant,
)

internal class NowStateCalculator {
    fun calculate(schedule: GroupSchedule?, now: Instant): NowSnapshot {
        val today = now.atZone(ScheduleTime.ZONE).toLocalDate()
        val midnight = today.plusDays(1).atStartOfDay(ScheduleTime.ZONE).toInstant()
        if (schedule == null) return NowSnapshot(today, false, NowState.NoSchedule, midnight)

        val holidays = schedule.holidays.associateBy { it.date }
        val holiday = holidays[today]
        val lessons = schedule.lessons.filterNot { holidays[it.date]?.blocks(it) == true }
        val scheduled = lessons.asSequence().filter { it.status == LessonStatus.SCHEDULED }
            .map(::LessonTiming).sortedBy { it.possibleStart }.toList()
        val todays = scheduled.filter { it.lesson.date == today }
        val nextKnownDay = scheduled.firstOrNull { it.lesson.date.isAfter(today) }?.lesson
        val active = scheduled.filter { it.isReliable && now >= it.start && now < it.end }
        val nextStart = todays.firstOrNull { it.possibleStart.isAfter(now) }
        val uncertain = todays.firstOrNull {
            !it.isReliable && it.possibleStart <= now &&
                (!it.hasKnownEnd || now < it.end)
        }
        val lastFinished = todays.filter { it.isReliable && it.end <= now }.maxByOrNull { it.end }

        val state = when {
            active.isNotEmpty() -> NowState.Running(active.map { timing ->
                val fraction = Duration.between(timing.start, now).toMillis().toDouble() /
                    Duration.between(timing.start, timing.end).toMillis()
                RunningLesson(timing.lesson, Duration.between(now, timing.end), fraction.coerceIn(0.0, 1.0).toFloat())
            })
            uncertain != null -> NowState.UncertainTime(uncertain.lesson)
            nextStart != null -> {
                val remaining = Duration.between(now, nextStart.start)
                when {
                    nextStart.hasTimeConflict -> NowState.UncertainTime(nextStart.lesson)
                    lastFinished != null -> NowState.Break(
                        nextStart.lesson, remaining, Duration.between(lastFinished.end, nextStart.start),
                    )
                    else -> NowState.BeforeFirst(nextStart.lesson, remaining)
                }
            }
            todays.isNotEmpty() -> NowState.Finished(nextKnownDay)
            else -> NowState.DayOff(reason(schedule, today, holiday != null, lessons), nextKnownDay)
        }

        val minute = if (state is NowState.Running || state is NowState.BeforeFirst || state is NowState.Break) {
            now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
        } else null
        val nextUpdate = listOfNotNull(minute, nextStart?.possibleStart, midnight)
            .plus(active.map { it.end })
            .plus(todays.filter { !it.isReliable && it.end > now }.map { it.end })
            .minOrNull() ?: midnight
        return NowSnapshot(today, holiday != null, state, nextUpdate)
    }

    private fun reason(
        schedule: GroupSchedule,
        today: LocalDate,
        holiday: Boolean,
        lessons: List<Lesson>,
    ): NowState.DayOff.Reason {
        if (holiday) return NowState.DayOff.Reason.HOLIDAY
        if (lessons.any { it.date == today && it.status == LessonStatus.CANCELLED }) {
            return NowState.DayOff.Reason.CANCELLED
        }
        val publication = ScheduleTime.findWeek(schedule.weeks, today)?.id?.let(schedule.publications::get)
        return when (publication) {
            WeekPublication.PUBLISHED -> NowState.DayOff.Reason.EMPTY
            WeekPublication.UNPUBLISHED -> NowState.DayOff.Reason.UNPUBLISHED
            null -> NowState.DayOff.Reason.UNKNOWN
        }
    }

    private class LessonTiming(val lesson: Lesson) {
        val start = ScheduleTime.startsAt(lesson)
        val end = ScheduleTime.endsAt(lesson)
        private val noteStart = LessonNotes.statedStart(lesson.note)
            ?.let { lesson.date.atTime(it).atZone(ScheduleTime.ZONE).toInstant() }
        val hasTimeConflict = noteStart != null && noteStart != start
        val possibleStart = minOf(start, noteStart ?: start)
        val hasKnownEnd = end > maxOf(start, noteStart ?: start)
        val isReliable = !hasTimeConflict && end > start
    }
}
