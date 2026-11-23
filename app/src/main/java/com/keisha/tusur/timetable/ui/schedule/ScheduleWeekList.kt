package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.components.EmptyWeekState
import com.keisha.tusur.timetable.ui.components.LessonCard
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Список дней одной недели: занятия, свободные пары и заглушки пустых дней.
 * Общий для расписания группы и преподавателя — отличается только содержимым карточек.
 */
@Composable
internal fun ScheduleWeekList(
    lessons: List<Lesson>,
    week: Week?,
    contentKey: String,
    currentWeekId: Int?,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    showTeachers: Boolean,
    showGroups: Boolean,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onOpenLesson: (Lesson) -> Unit,
    modifier: Modifier = Modifier,
    holidays: List<HolidayDay> = emptyList(),
    publication: WeekPublication? = null,
    focusDate: LocalDate? = null,
    onDateFocused: (LocalDate) -> Unit = {},
    isRoom: Boolean = false,
) {
    val days = remember(lessons, holidays, week?.startDate, publication) {
        val scheduledDays = week?.let { ScheduleTime.daysForWeek(lessons, it.startDate, holidays) }.orEmpty()
        if (publication == WeekPublication.UNPUBLISHED && scheduledDays.none { it.lessons.isNotEmpty() }) {
            scheduledDays.filter { it.isHoliday }
        } else scheduledDays
    }
    val hasDays = week != null && (publication == WeekPublication.PUBLISHED ||
        days.any { it.isHoliday || it.lessons.isNotEmpty() })
    val maxPair = remember(days, isRoom) {
        if (isRoom) ScheduleTime.PAIRS.last().number else weekMaxPair(days)
    }
    val hasUnpublishedHeader = hasDays && publication == WeekPublication.UNPUBLISHED

    // Текущая неделя открывается на сегодняшнем дне, остальные — с понедельника.
    // Пустая неделя показывает единственный элемент, поэтому для неё всегда 0.
    val startIndex = remember(days, week?.id, currentWeekId, maxPair, hasUnpublishedHeader) {
        if (hasDays) {
            val start = weekStartIndex(days, week?.id, currentWeekId, ScheduleTime.today(), maxPair)
            if (hasUnpublishedHeader && start > 0) start + 1 else start
        } else {
            0
        }
    }
    // Стартовая позиция задаётся сразу, иначе при анимации неделя успевает
    // показаться с понедельника и прыгнуть на сегодняшний день.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val resetKey = "${week?.id}/$contentKey"
    // Ключ уже обработанной прокрутки переживает поворот экрана: rememberLazyListState
    // восстановит позицию, а повторная прокрутка к сегодняшнему дню её бы сбросила.
    var handledResetKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(resetKey, hasDays) {
        if (handledResetKey == resetKey || !hasDays) return@LaunchedEffect
        handledResetKey = resetKey
        // До первой раскладки список ещё пуст — ждём, когда появятся элементы.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        if (focusDate == null) listState.scrollToItem(startIndex)
    }

    val focusIndex = focusDate?.let { dayHeaderIndex(days, it, maxPair) }
    LaunchedEffect(focusDate, focusIndex, hasDays) {
        if (focusDate != null && hasDays) {
            val index = (focusIndex ?: 0) + if (hasUnpublishedHeader) 1 else 0
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > index }
            listState.scrollToItem(index)
            onDateFocused(focusDate)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!hasDays) {
            item {
                EmptyWeekState(
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    onPrevious = onPreviousWeek,
                    onNext = onNextWeek,
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        } else {
            if (hasUnpublishedHeader) {
                item(key = "unpublished") {
                    Text(
                        text = stringResource(R.string.schedule_empty_week),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            days.forEach { day ->
                item(key = "day-${day.date}") {
                    DayHeader(date = day.date, showHoliday = day.isHoliday && day.lessons.isNotEmpty())
                }
                if (day.lessons.isEmpty()) {
                    item(key = "empty-${day.date}") {
                        EmptyDayRow(day.isHoliday, isRoom)
                    }
                } else if (day.isHoliday) {
                    lessonRows(day.lessons, showTeachers, showGroups, onOpenLesson)
                } else {
                    for (pair in 1..maxPair) {
                        val pairLessons = day.lessons.filter { it.pairNumber == pair }
                        if (pairLessons.isEmpty()) {
                            item(key = "free-${day.date}-$pair") {
                                EmptyPairRow(pairNumber = pair, isRoom = isRoom)
                            }
                        } else {
                            lessonRows(pairLessons, showTeachers, showGroups, onOpenLesson)
                        }
                    }
                    // Занятия с нестандартным временем не привязаны к паре — показываем после сетки.
                    val unslotted = day.lessons.filter { it.pairNumber == null }
                    lessonRows(unslotted, showTeachers, showGroups, onOpenLesson)
                }
            }
        }
    }
}

private fun LazyListScope.lessonRows(
    lessons: List<Lesson>,
    showTeachers: Boolean,
    showGroups: Boolean,
    onOpenLesson: (Lesson) -> Unit,
) {
    itemsIndexed(lessons, key = { index, lesson -> "lesson-${lesson.date}-${lesson.start}-${lesson.id}-$index" }) { _, lesson ->
        LessonCard(
            lesson = lesson,
            onClick = { onOpenLesson(lesson) },
            showTeachers = showTeachers,
            showGroups = showGroups,
        )
    }
}

@Composable
private fun DayHeader(date: LocalDate, showHoliday: Boolean) {
    val isToday = date == ScheduleTime.today()
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = ScheduleTime.formatDayTitle(date),
            modifier = Modifier.align(Alignment.CenterVertically).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = if (isToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (isToday) {
            Surface(
                modifier = Modifier.align(Alignment.CenterVertically),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.schedule_today),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (showHoliday) {
            Text(
                text = stringResource(R.string.schedule_holiday),
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptyDayRow(isHoliday: Boolean, isRoom: Boolean) {
    Text(
        text = stringResource(when {
            isHoliday -> R.string.schedule_holiday
            isRoom -> R.string.room_no_entries
            else -> R.string.schedule_empty_day
        }),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isHoliday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

/**
 * Свободная пара — «окно». Компактная строка, чтобы полная сетка не вытеснила занятия.
 */
@Composable
private fun EmptyPairRow(pairNumber: Int, isRoom: Boolean) {
    val pair = ScheduleTime.PAIRS.firstOrNull { it.number == pairNumber }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (pair != null) {
                stringResource(
                    R.string.lesson_time,
                    ScheduleTime.formatTime(pair.start),
                    ScheduleTime.formatTime(pair.end),
                )
            } else {
                stringResource(R.string.lesson_pair, pairNumber)
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (isRoom) stringResource(R.string.room_no_entries)
            else stringResource(R.string.schedule_free_pair, pairNumber),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
