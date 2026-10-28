package com.keisha.tusur.timetable.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.AppError

@Composable
fun ScheduleLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.schedule_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Первый запуск или сброшенный выбор группы. */
@Composable
fun NoSelectionState(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyMessage(
        icon = { EmptyIcon(Icons.Outlined.CalendarMonth) },
        title = stringResource(R.string.schedule_no_selection_title),
        message = stringResource(R.string.schedule_no_selection_hint),
        modifier = modifier.fillMaxSize(),
    ) {
        Button(
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.schedule_open_settings))
        }
    }
}

/** Ошибка без кэша: сообщение по типу и повтор. */
@Composable
fun ScheduleErrorState(
    error: AppError?,
    groupNotFound: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    notFoundMessageRes: Int = R.string.schedule_group_not_found,
    modifier: Modifier = Modifier,
) {
    val notFound = groupNotFound || error is AppError.NotFound
    val messageRes = when {
        notFound -> notFoundMessageRes
        error is AppError.Network -> R.string.error_network
        error is AppError.Server -> R.string.error_server
        error is AppError.Parse -> R.string.error_parse
        else -> R.string.error_unknown
    }
    val icon = if (error is AppError.Network) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline
    // Подсказка про интернет уместна только для сетевой ошибки.
    val hint = if (error is AppError.Network) stringResource(R.string.schedule_error_no_cache) else null

    EmptyMessage(
        icon = { EmptyIcon(icon) },
        title = stringResource(R.string.schedule_error_title),
        message = stringResource(messageRes),
        hint = hint,
        modifier = modifier.fillMaxSize(),
    ) {
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.action_retry))
        }
        if (notFound && onOpenSettings != null) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.schedule_open_settings))
            }
        }
    }
}

/** На выбранной неделе нет ни одного занятия. */
@Composable
fun EmptyWeekState(
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyMessage(
        icon = { EmptyIcon(Icons.Outlined.EventBusy) },
        title = stringResource(R.string.schedule_empty_week),
        message = stringResource(R.string.schedule_empty_week_hint),
        modifier = modifier,
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.schedule_previous_week))
        }
        OutlinedButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.schedule_next_week))
        }
    }
}

@Composable
private fun EmptyIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(56.dp),
    )
}

@Composable
private fun EmptyMessage(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}
