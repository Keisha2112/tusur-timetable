package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.ui.components.weekTitleText
import kotlinx.coroutines.flow.first

/** Список всех недель семестра. */
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
        listState.scrollToItem((index - 1).coerceAtLeast(0))
    }

    ScheduleSheet(onDismiss = onDismiss) {
        ScheduleSheetTitle(
            title = stringResource(R.string.weeks_title),
            onDismiss = onDismiss,
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .selectableGroup(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .selectable(selected = isSelected, role = Role.Button, onClick = onClick),
        shape = shape,
        color = if (isSelected) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
        border = if (isSelected) BorderStroke(1.dp, colors.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = weekTitleText(week),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val secondary = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant
                    Text(
                        text = weekKindLabel(week.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                    )
                    if (hasLessons) {
                        Text(
                            text = stringResource(R.string.week_has_lessons),
                            style = MaterialTheme.typography.bodySmall,
                            color = secondary,
                        )
                    }
                    if (isCurrent) {
                        Text(
                            text = stringResource(R.string.week_current),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                        )
                    }
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
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
