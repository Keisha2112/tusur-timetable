package com.keisha.tusur.timetable.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.ui.theme.LocalAppMotion

@Composable
internal fun LoadingSpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (LocalAppMotion.current.disabled) {
        CircularProgressIndicator(
            progress = { 0.72f },
            modifier = modifier.indeterminate(),
            strokeWidth = strokeWidth,
            color = color,
        )
    } else {
        CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth, color = color)
    }
}

@Composable
internal fun LoadingBar(modifier: Modifier = Modifier) {
    if (LocalAppMotion.current.disabled) {
        LinearProgressIndicator(progress = { 0.5f }, modifier = modifier.indeterminate())
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            if (LocalAppMotion.current.disabled) {
                if (isRefreshing) LoadingBar(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            } else {
                PullToRefreshDefaults.Indicator(
                    state = state,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
        content = content,
    )
}

private fun Modifier.indeterminate() = clearAndSetSemantics {
    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
}
