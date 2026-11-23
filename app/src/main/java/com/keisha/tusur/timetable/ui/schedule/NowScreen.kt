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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.time.NowSnapshot
import com.keisha.tusur.timetable.domain.time.NowState
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.components.LessonCard
import com.keisha.tusur.timetable.ui.components.OfflineBanner
import com.keisha.tusur.timetable.ui.components.ScheduleErrorState
import com.keisha.tusur.timetable.ui.components.ScheduleLoadingState
import java.time.Duration

@Composable
internal fun NowScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenTeacher: (String) -> Unit,
    source: ScheduleSource = ScheduleSource.GROUP,
    selectionOverride: UserSelection? = null,
) {
    val viewModel: NowViewModel = viewModel(
        key = selectionOverride?.subject(source)?.key ?: source.name,
        factory = viewModelFactory {
            initializer {
                NowViewModel(container.scheduleFor(source), container.settingsRepository, source, selectionOverride)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val schedule = state?.schedule
    val snapshot = state?.snapshot
    val context = LocalContext.current
    var isOnline by remember { mutableStateOf(context.isOnline()) }
    var details by remember(schedule?.subject?.key) { mutableStateOf<Lesson?>(null) }
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

    val date = snapshot?.date
    val fromRooms = source == ScheduleSource.GROUP_FROM_ROOMS
    val groupKey = schedule?.subject?.key
    var roomLoading by remember(groupKey, date) { mutableStateOf(fromRooms) }
    var roomError by remember(groupKey, date) { mutableStateOf<AppError?>(null) }
    var nextWeekError by remember(groupKey, date) { mutableStateOf<AppError?>(null) }
    var retry by remember(groupKey, date) { mutableStateOf(0) }
    LaunchedEffect(viewModel, groupKey, date, retry, isOnline) {
        if (!fromRooms || date == null || groupKey == null) return@LaunchedEffect
        roomLoading = true
        try {
            roomError = viewModel.load(forceRefresh = retry > 0)
        } finally {
            roomLoading = false
        }
    }
    val nextWeek = state?.nextRoomWeek()?.takeIf { fromRooms && !roomLoading && roomError == null }
    LaunchedEffect(viewModel, groupKey, nextWeek, retry, isOnline) {
        nextWeekError = nextWeek?.let { viewModel.load(it, forceRefresh = retry > 0) }
    }

    Scaffold(
        topBar = {
            ScheduleHeader(
                title = stringResource(R.string.schedule_now),
                subtitle = schedule?.subject?.title,
                onBack = onBack,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val week = schedule?.entries?.let { entries -> date?.let { ScheduleTime.findWeek(entries.weeks, it) } }
        val roomWeekPending = fromRooms && (week == null || week.id !in schedule?.entries?.publications.orEmpty())
        val coverage = week?.id?.let { schedule?.roomCoverage?.get(it) }
        val error = roomError
        if (snapshot == null || roomLoading && schedule?.entries == null) {
            ScheduleLoadingState(Modifier.padding(padding))
        } else if (schedule?.entries == null && error != null) {
            ScheduleErrorState(
                error = error,
                groupNotFound = false,
                onRetry = { retry++ },
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                if (schedule?.entries != null &&
                    (!isOnline || error != null || nextWeekError != null || week?.id in schedule.staleWeeks ||
                        nextWeek?.id in schedule.staleWeeks)
                ) {
                    OfflineBanner(
                        message = stringResource(
                            if (!isOnline || error is AppError.Network || nextWeekError is AppError.Network) {
                                R.string.schedule_offline
                            } else {
                                R.string.schedule_stale
                            },
                        ),
                        updatedAt = schedule.fetchedAt,
                    )
                    if (error != null || nextWeekError != null) {
                        TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                val checking = coverage?.loading == true || roomLoading && roomWeekPending
                if (fromRooms && (checking || coverage != null && !coverage.complete)) {
                    RoomWeekProgress(coverage, checking)
                }
                NowContent(
                    snapshot,
                    onLesson = { details = it },
                    fromRooms = fromRooms,
                    partial = roomWeekPending,
                    loadingUpcoming = nextWeek != null && nextWeekError == null,
                )
            }
        }
    }
    val lesson = details
    if (lesson != null && schedule != null) {
        LessonDetailsSheet(
            lesson = lesson,
            sourceUrl = schedule.subject.sourceUrl(lesson),
            onDismiss = { details = null },
            onTeacherClick = { teacher ->
                details = null
                onOpenTeacher(teacher)
            },
        )
    }
}

@Composable
internal fun NowContent(
    snapshot: NowSnapshot,
    onLesson: (Lesson) -> Unit,
    fromRooms: Boolean = false,
    partial: Boolean = false,
    loadingUpcoming: Boolean = false,
) {
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
                StateTitle(stringResource(R.string.now_current_lesson))
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
            }
            is NowState.Break -> {
                StateTitle(stringResource(R.string.now_break))
                Text(
                    stringResource(R.string.now_break_length, durationText(state.length)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is NowState.Finished -> {
                if (!partial) StateTitle(stringResource(R.string.now_finished))
            }
            is NowState.DayOff -> {
                if (!partial) {
                    StateTitle(stringResource(when (state.reason) {
                        NowState.DayOff.Reason.HOLIDAY -> R.string.schedule_holiday
                        NowState.DayOff.Reason.CANCELLED -> R.string.now_all_cancelled
                        NowState.DayOff.Reason.EMPTY -> if (fromRooms) R.string.room_no_entries else R.string.now_empty
                        NowState.DayOff.Reason.UNPUBLISHED -> R.string.now_unpublished
                        NowState.DayOff.Reason.UNKNOWN -> R.string.now_unknown_day
                    }))
                }
            }
            is NowState.UncertainTime -> {
                StateTitle(stringResource(
                    if (state.lesson.note.isNullOrBlank()) R.string.now_time_unknown else R.string.now_time_note,
                ))
                if (snapshot.upcoming?.lessons?.none { it.lesson == state.lesson } != false) {
                    LessonCard(state.lesson, onClick = { onLesson(state.lesson) })
                }
            }
        }
        if (state != NowState.NoSchedule) Upcoming(snapshot, onLesson, loadingUpcoming, partial)
    }
}

@Composable
private fun StateTitle(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Upcoming(
    snapshot: NowSnapshot,
    onLesson: (Lesson) -> Unit,
    loading: Boolean,
    partial: Boolean,
) {
    val upcoming = snapshot.upcoming
    if (upcoming == null) {
        if (partial && !loading) return
        Text(
            stringResource(if (loading) R.string.now_loading_next_week else R.string.now_no_following),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    StateTitle(stringResource(R.string.now_next_lesson))
    val date = upcoming.lessons.first().lesson.date
    if (date != snapshot.date) {
        Text(
            text = ScheduleTime.formatDayTitle(date),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    upcoming.remaining?.let {
        Text(stringResource(R.string.now_until, durationText(it)), style = MaterialTheme.typography.titleMedium)
    }
    upcoming.lessons.forEach { entry ->
        if (entry.timeConflict) Text(stringResource(R.string.now_time_note))
        LessonCard(entry.lesson, onClick = { onLesson(entry.lesson) })
    }
}

@Composable
private fun durationText(duration: Duration): String {
    val minutes = ((duration.toMillis().coerceAtLeast(0) + 59_999) / 60_000).toInt()
    return when {
        minutes >= 1_440 -> stringResource(R.string.now_days_hours_minutes, minutes / 1_440, minutes / 60 % 24, minutes % 60)
        minutes >= 60 -> stringResource(R.string.now_hours_minutes, minutes / 60, minutes % 60)
        else -> stringResource(R.string.now_minutes, minutes)
    }
}

private fun Context.isOnline(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
    return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
