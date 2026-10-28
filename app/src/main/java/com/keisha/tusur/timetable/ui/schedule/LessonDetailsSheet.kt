package com.keisha.tusur.timetable.ui.schedule

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.components.LessonTypeBadge

/**
 * Подробности занятия: полные списки аудиторий, преподавателей или групп, ссылка на сайт.
 *
 * Диалог вместо ModalBottomSheet: лист Material3 падает на Android 6–7.
 */
@Composable
fun LessonDetailsSheet(
    lesson: Lesson,
    sourceUrl: String,
    onDismiss: () -> Unit,
    showTeachers: Boolean = true,
    showGroups: Boolean = false,
    onTeacherClick: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current

    val timeText = stringResource(
        R.string.lesson_time,
        ScheduleTime.formatTime(lesson.start),
        ScheduleTime.formatTime(lesson.end),
    )
    val pairText = lesson.pairNumber?.let { stringResource(R.string.lesson_pair, it) }
    val timeWithPair = if (pairText != null) "$timeText · $pairText" else timeText

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                ) {
                    Text(
                        text = lesson.discipline,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    LessonTypeBadge(lesson = lesson)
                    Spacer(Modifier.height(20.dp))

                    DetailRow(
                        label = stringResource(R.string.lesson_details_date),
                        value = ScheduleTime.formatFullDate(lesson.date),
                    )
                    DetailRow(
                        label = stringResource(R.string.lesson_details_time),
                        value = timeWithPair,
                    )
                    if (lesson.rooms.isNotEmpty()) {
                        DetailRow(
                            label = stringResource(R.string.lesson_rooms_title),
                            value = lesson.rooms.joinToString(", "),
                        )
                    }
                    if (showGroups && lesson.groups.isNotEmpty()) {
                        DetailRow(
                            label = stringResource(R.string.lesson_groups_title),
                            value = lesson.groups.joinToString(", "),
                        )
                    }
                    if (showTeachers) {
                        TeachersRow(
                            teachers = lesson.teachers,
                            onTeacherClick = onTeacherClick,
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { openSource(context, sourceUrl) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(R.string.lesson_details_source))
                    }
                }
            }
        }
    }
}

@Composable
private fun TeachersRow(
    teachers: List<String>,
    onTeacherClick: ((String) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.lesson_teachers_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        if (teachers.isEmpty()) {
            Text(
                text = stringResource(R.string.lesson_teacher_unknown),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            teachers.forEach { teacher ->
                if (onTeacherClick != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.cd_open_teacher),
                                onClick = { onTeacherClick(teacher) },
                            )
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = teacher,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Text(
                        text = teacher,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun openSource(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.lesson_source_unavailable, Toast.LENGTH_SHORT).show()
    }
}
