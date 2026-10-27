package com.keisha.tusur.timetable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.time.ScheduleTime

/**
 * Карточка занятия в списке дня. Все данные читаются текстом, цвет — только подсказка.
 */
@Composable
fun LessonCard(
    lesson: Lesson,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = lessonTypeAccent(lesson.type)
    val typeLabel = lessonTypeLabel(lesson)
    val timeText = stringResource(
        R.string.lesson_time,
        ScheduleTime.formatTime(lesson.start),
        ScheduleTime.formatTime(lesson.end),
    )
    val description = stringResource(
        R.string.cd_lesson_card,
        lesson.discipline,
        typeLabel,
        timeText,
    )
    val room = lesson.rooms.firstOrNull()

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (lesson.pairNumber != null) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.lesson_pair, lesson.pairNumber),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = lesson.discipline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    LessonTypeBadge(lesson = lesson)
                    if (room != null) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.Outlined.MeetingRoom,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.lesson_room, room),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (lesson.teachers.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    TeachersLine(teachers = lesson.teachers)
                }
            }
        }
    }
}

@Composable
fun LessonTypeBadge(
    lesson: Lesson,
    modifier: Modifier = Modifier,
) {
    val accent = lessonTypeAccent(lesson.type)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.14f),
    ) {
        Text(
            text = lessonTypeLabel(lesson),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
}

@Composable
private fun TeachersLine(teachers: List<String>) {
    val shown = teachers.take(2).joinToString(", ")
    val rest = teachers.size - 2
    val text = if (rest > 0) {
        shown + " " + stringResource(R.string.lesson_more_teachers, rest)
    } else {
        shown
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Название типа занятия. Для неизвестного типа показываем исходный текст сайта. */
@Composable
fun lessonTypeLabel(lesson: Lesson): String =
    if (lesson.type == LessonType.OTHER && lesson.rawType.isNotBlank()) {
        lesson.rawType
    } else {
        stringResource(
            when (lesson.type) {
                LessonType.LECTURE -> R.string.lesson_type_lecture
                LessonType.PRACTICE -> R.string.lesson_type_practice
                LessonType.LABORATORY -> R.string.lesson_type_laboratory
                LessonType.RESEARCH -> R.string.lesson_type_research
                LessonType.CONSULTATION -> R.string.lesson_type_consultation
                LessonType.EXAM -> R.string.lesson_type_exam
                LessonType.CREDIT -> R.string.lesson_type_credit
                LessonType.OTHER -> R.string.lesson_type_other
            },
        )
    }

/**
 * Акцент типа занятия берём из цветовой схемы, чтобы он автоматически
 * подстраивался под светлую и тёмную тему.
 */
@Composable
fun lessonTypeAccent(type: LessonType): Color = when (type) {
    LessonType.LECTURE -> MaterialTheme.colorScheme.primary
    LessonType.PRACTICE -> MaterialTheme.colorScheme.secondary
    LessonType.LABORATORY -> MaterialTheme.colorScheme.tertiary
    LessonType.EXAM, LessonType.CREDIT -> MaterialTheme.colorScheme.error
    LessonType.RESEARCH, LessonType.CONSULTATION, LessonType.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}
