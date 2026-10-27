package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.ui.components.weekTitleText
import kotlinx.coroutines.flow.first

/**
 * Список всех недель семестра.
 *
 * Это диалог, а не ModalBottomSheet: у листа Material3 на Android 6–7
 * падает инициализация якорей прокрутки, диалог ведёт себя одинаково на всех версиях.
 */
@Composable
fun WeekPickerSheet(
    weeks: List<Week>,
    selectedWeekId: Int?,
    currentWeekId: Int?,
    weeksWithLessons: Set<Int>,
    onSelectWeek: (Week) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedWeekId, weeks) {
        val index = weeks.indexOfFirst { it.id == selectedWeekId }
        if (index < 0) return@LaunchedEffect
        // До первой раскладки offset списка ещё не инициализирован.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(index)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(horizontal = 12.dp, vertical = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.weeks_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(weeks, key = { it.id }) { week ->
                            WeekPickerRow(
                                week = week,
                                isSelected = week.id == selectedWeekId,
                                isCurrent = week.id == currentWeekId,
                                hasLessons = week.id in weeksWithLessons,
                                onClick = {
                                    onSelectWeek(week)
                                    onDismiss()
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun WeekPickerRow(
    week: Week,
    isSelected: Boolean,
    isCurrent: Boolean,
    hasLessons: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val titleColor = if (isCurrent && !isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        contentColor
    }
    val currentLabelColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .selectable(
                selected = isSelected,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = shape,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = weekTitleText(week),
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = weekKindLabel(week.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.week_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = currentLabelColor,
                        )
                    }
                }
            }

            if (hasLessons) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.week_has_lessons),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryColor,
                )
            }
        }
    }
}

@Composable
private fun weekKindLabel(kind: WeekKind): String = stringResource(
    when (kind) {
        WeekKind.THEORETICAL -> R.string.week_kind_theoretical
        WeekKind.EXAM_SESSION -> R.string.week_kind_exam_session
        WeekKind.WEEKEND -> R.string.week_kind_weekend
    },
)
