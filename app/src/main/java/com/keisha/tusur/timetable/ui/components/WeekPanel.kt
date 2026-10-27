package com.keisha.tusur.timetable.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime

/**
 * Панель недели: стрелки, заголовок с диапазоном и чётностью, точки недель с занятиями.
 */
@Composable
fun WeekPanel(
    week: Week,
    weeks: List<Week>,
    weeksWithLessons: Set<Int>,
    currentWeekId: Int?,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onChooseWeek: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showToday = currentWeekId != null && week.id != currentWeekId

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = canGoPrevious,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.cd_previous_week),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        onClickLabel = stringResource(R.string.cd_choose_week),
                        role = Role.Button,
                        onClick = onChooseWeek,
                    )
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = weekTitleText(week),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                WeekDots(
                    weeks = weeks,
                    selectedWeekId = week.id,
                    weeksWithLessons = weeksWithLessons,
                )
            }

            IconButton(
                onClick = onNext,
                enabled = canGoNext,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.cd_next_week),
                )
            }
        }

        AnimatedVisibility(visible = showToday) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onToday) {
                    Icon(
                        imageVector = Icons.Outlined.Today,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = stringResource(R.string.schedule_today))
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Точки пяти соседних недель: заполненная — есть занятия, вытянутая — выбранная. */
@Composable
private fun WeekDots(
    weeks: List<Week>,
    selectedWeekId: Int,
    weeksWithLessons: Set<Int>,
) {
    val window = weekWindow(weeks, selectedWeekId)
    if (window.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        window.forEach { item ->
            val isSelected = item.id == selectedWeekId
            val hasLessons = item.id in weeksWithLessons
            val color = when {
                isSelected -> MaterialTheme.colorScheme.primary
                hasLessons -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .width(if (isSelected) 16.dp else 6.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** До пяти недель вокруг выбранной, чтобы точки не растягивались на весь экран. */
private fun weekWindow(
    weeks: List<Week>,
    selectedWeekId: Int,
    radius: Int = 2,
): List<Week> {
    val index = weeks.indexOfFirst { it.id == selectedWeekId }
    if (index < 0) return emptyList()
    val from = (index - radius).coerceAtLeast(0)
    val to = (index + radius).coerceAtMost(weeks.lastIndex)
    return weeks.subList(from, to + 1)
}

/** «Неделя 9 · 26 окт — 1 ноя · нечётная». */
@Composable
internal fun weekTitleText(week: Week): String {
    val range = ScheduleTime.formatWeekRange(week.startDate)
    val number = week.number
    val base = if (number != null) {
        stringResource(R.string.schedule_week_title, number, range)
    } else {
        stringResource(R.string.schedule_week_title_no_number, range)
    }
    val parity = when (ScheduleTime.weekNumberParity(number)) {
        true -> stringResource(R.string.schedule_week_even)
        false -> stringResource(R.string.schedule_week_odd)
        null -> null
    }
    return if (parity != null) "$base · $parity" else base
}
