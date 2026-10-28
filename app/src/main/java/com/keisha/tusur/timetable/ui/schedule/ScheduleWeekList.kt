package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.Week
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
) {
    val days = remember(lessons, week?.startDate) {
        week?.let { ScheduleTime.daysForWeek(lessons, it.startDate) }.orEmpty()
    }
    val hasLessons = days.any { it.lessons.isNotEmpty() }
    val maxPair = remember(days) { weekMaxPair(days) }
    val listState = rememberLazyListState()

    // Текущая неделя открывается на сегодняшнем дне, остальные — с понедельника.
    // Пустая неделя показывает единственный элемент, поэтому для неё всегда 0.
    val startIndex = remember(days, week?.id, currentWeekId, maxPair) {
        if (hasLessons) {
            weekStartIndex(days, week?.id, currentWeekId, ScheduleTime.today(), maxPair)
        } else {
            0
        }
    }
    val resetKey = "${week?.id}/$contentKey"
    // Ключ уже обработанной прокрутки переживает поворот экрана: rememberLazyListState
    // восстановит позицию, а повторная прокрутка к сегодняшнему дню её бы сбросила.
    var handledResetKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(resetKey) {
        if (handledResetKey == resetKey) return@LaunchedEffect
        handledResetKey = resetKey
        // До первой раскладки список ещё пуст — ждём, когда появятся элементы.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(startIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (week == null || !hasLessons) {
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
            days.forEach { day ->
                item(key = "day-${day.date}") {
                    DayHeader(date = day.date)
                }
                if (day.lessons.isEmpty()) {
                    item(key = "empty-${day.date}") {
                        EmptyDayRow()
                    }
                } else {
                    for (pair in 1..maxPair) {
                        val pairLessons = day.lessons.filter { it.pairNumber == pair }
                        if (pairLessons.isEmpty()) {
                            item(key = "free-${day.date}-$pair") {
                                EmptyPairRow(pairNumber = pair)
                            }
                        } else {
                            items(pairLessons, key = { "lesson-${it.id}" }) { lesson ->
                                LessonCard(
                                    lesson = lesson,
                                    onClick = { onOpenLesson(lesson) },
                                    showTeachers = showTeachers,
                                    showGroups = showGroups,
                                )
                            }
                        }
                    }
                    // Занятия с нестандартным временем не привязаны к паре — показываем после сетки.
                    val unslotted = day.lessons.filter { it.pairNumber == null }
                    if (unslotted.isNotEmpty()) {
                        items(unslotted, key = { "lesson-${it.id}" }) { lesson ->
                            LessonCard(
                                lesson = lesson,
                                onClick = { onOpenLesson(lesson) },
                                showTeachers = showTeachers,
                                showGroups = showGroups,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
    val isToday = date == ScheduleTime.today()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ScheduleTime.formatDayTitle(date),
            style = MaterialTheme.typography.titleMedium,
            color = if (isToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (isToday) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.schedule_today),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyDayRow() {
    Text(
        text = stringResource(R.string.schedule_empty_day),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

/**
 * Свободная пара — «окно». Компактная строка, чтобы полная сетка не вытеснила занятия.
 */
@Composable
private fun EmptyPairRow(pairNumber: Int) {
    val pair = ScheduleTime.PAIRS.firstOrNull { it.number == pairNumber }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.schedule_free_pair, pairNumber),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
