package com.keisha.tusur.timetable.ui.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.time.NowSnapshot
import com.keisha.tusur.timetable.domain.time.NowState
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.components.LessonCard
import com.keisha.tusur.timetable.ui.components.OfflineBanner
import com.keisha.tusur.timetable.ui.components.ScheduleLoadingState
import java.time.Duration

@Composable
internal fun NowScreen(container: AppContainer, onBack: () -> Unit, onOpenTeacher: (String) -> Unit) {
    val viewModel: NowViewModel = viewModel(factory = viewModelFactory {
        initializer { NowViewModel(container.scheduleRepository, container.settingsRepository) }
    })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val schedule = state?.schedule
    val context = LocalContext.current
    var isOnline by remember { mutableStateOf(context.isOnline()) }
    var details by remember(schedule?.group) { mutableStateOf<Lesson?>(null) }
    LifecycleStartEffect(viewModel) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                isOnline = context.isOnline()
                viewModel.onTimeChanged()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        isOnline = context.isOnline()
        viewModel.onTimeChanged()
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }

    Scaffold(
        topBar = {
            ScheduleHeader(
                title = stringResource(R.string.schedule_now),
                subtitle = schedule?.group?.name,
                onBack = onBack,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val snapshot = state?.snapshot
        if (snapshot == null) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) { ScheduleLoadingState() }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                val week = schedule?.let { ScheduleTime.findWeek(it.weeks, snapshot.date) }
                if (schedule != null && (!isOnline || week?.id in schedule.staleWeeks)) {
                    OfflineBanner(
                        message = stringResource(if (isOnline) R.string.schedule_stale else R.string.schedule_offline),
                        updatedAt = schedule.fetchedAt,
                    )
                }
                NowContent(snapshot, onLesson = { details = it })
            }
        }
    }
    val lesson = details
    if (lesson != null && schedule != null) {
        LessonDetailsSheet(
            lesson = lesson,
            sourceUrl = Urls.group(schedule.group.facultyCode, schedule.group.slug),
            onDismiss = { details = null },
            onTeacherClick = { teacher ->
                details = null
                onOpenTeacher(teacher)
            },
        )
    }
}

@Composable
internal fun NowContent(snapshot: NowSnapshot, onLesson: (Lesson) -> Unit) {
    val state = snapshot.state
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = ScheduleTime.formatDayTitle(snapshot.date),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snapshot.isHoliday && state !is NowState.DayOff) {
            Text(stringResource(R.string.schedule_holiday), color = MaterialTheme.colorScheme.primary)
        }
        when (state) {
            NowState.NoSchedule -> StateTitle(stringResource(R.string.now_no_schedule))
            is NowState.Running -> {
                StateTitle(stringResource(R.string.now_in_progress))
                for (entry in state.current) {
                    LessonCard(entry.lesson, onClick = { onLesson(entry.lesson) })
                    Text(
                        stringResource(R.string.now_remaining, durationText(entry.remaining)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            is NowState.BeforeFirst -> {
                StateTitle(stringResource(R.string.now_before_first))
                Upcoming(state.next, state.remaining, onLesson)
            }
            is NowState.Break -> {
                StateTitle(stringResource(R.string.now_break))
                Upcoming(state.next, state.remaining, onLesson)
                Text(
                    stringResource(R.string.now_break_length, durationText(state.length)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is NowState.Finished -> {
                StateTitle(stringResource(R.string.now_finished))
                Following(state.following, onLesson)
            }
            is NowState.DayOff -> {
                StateTitle(stringResource(when (state.reason) {
                    NowState.DayOff.Reason.HOLIDAY -> R.string.schedule_holiday
                    NowState.DayOff.Reason.CANCELLED -> R.string.now_all_cancelled
                    NowState.DayOff.Reason.EMPTY -> R.string.now_empty
                    NowState.DayOff.Reason.UNPUBLISHED -> R.string.now_unpublished
                    NowState.DayOff.Reason.UNKNOWN -> R.string.now_unknown_day
                }))
                Following(state.following, onLesson)
            }
            is NowState.UncertainTime -> {
                StateTitle(stringResource(
                    if (state.lesson.note.isNullOrBlank()) R.string.now_time_unknown else R.string.now_time_note,
                ))
                LessonCard(state.lesson, onClick = { onLesson(state.lesson) })
            }
        }
    }
}

@Composable
private fun StateTitle(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Upcoming(lesson: Lesson, remaining: Duration, onLesson: (Lesson) -> Unit) {
    Text(stringResource(R.string.now_until, durationText(remaining)), style = MaterialTheme.typography.titleMedium)
    LessonCard(lesson, onClick = { onLesson(lesson) })
}

@Composable
private fun Following(lesson: Lesson?, onLesson: (Lesson) -> Unit) {
    if (lesson == null) {
        Text(stringResource(R.string.now_no_following), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(
            stringResource(R.string.now_following, ScheduleTime.formatDayTitle(lesson.date)),
            style = MaterialTheme.typography.titleMedium,
        )
        LessonCard(lesson, onClick = { onLesson(lesson) })
    }
}

@Composable
private fun durationText(duration: Duration): String {
    val minutes = ((duration.toMillis().coerceAtLeast(0) + 59_999) / 60_000).toInt()
    return if (minutes >= 60) stringResource(R.string.now_hours_minutes, minutes / 60, minutes % 60)
    else stringResource(R.string.now_minutes, minutes)
}

private fun Context.isOnline(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
    return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
