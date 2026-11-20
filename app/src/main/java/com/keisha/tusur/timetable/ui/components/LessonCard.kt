package com.keisha.tusur.timetable.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.theme.lessonTypeColors

/**
 * Карточка занятия в списке дня. Все данные читаются текстом, цвет — только подсказка.
 */
@Composable
fun LessonCard(
    lesson: Lesson,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTeachers: Boolean = true,
    showGroups: Boolean = false,
) {
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

    OutlinedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = timeText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (lesson.pairNumber != null) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = stringResource(R.string.lesson_pair, lesson.pairNumber),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = lesson.discipline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LessonTypeBadge(lesson = lesson, modifier = Modifier.align(Alignment.CenterVertically))
                if (room != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MeetingRoom,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.lesson_room, room),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showGroups && lesson.groups.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                GroupsLine(groups = lesson.groups)
            }

            if (showTeachers && lesson.teachers.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                TeachersLine(teachers = lesson.teachers)
            }
        }
    }
}

@Composable
fun LessonTypeBadge(
    lesson: Lesson,
    modifier: Modifier = Modifier,
) {
    val colors = lessonTypeColors(lesson.type, lesson.rawType)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(colors.accent, CircleShape))
            Text(
                text = lessonTypeLabel(lesson),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun GroupsLine(groups: List<String>) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Outlined.Groups,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = groups.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
