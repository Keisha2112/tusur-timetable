package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.ui.components.LoadingBar
import com.keisha.tusur.timetable.ui.components.ScheduleRefreshBox
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.RoomWeekCoverage
import com.keisha.tusur.timetable.domain.model.ScheduleEntries
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.components.NoSelectionState
import com.keisha.tusur.timetable.ui.components.OfflineBanner
import com.keisha.tusur.timetable.ui.components.ScheduleErrorState
import com.keisha.tusur.timetable.ui.components.ScheduleLoadingState
import com.keisha.tusur.timetable.ui.components.WeekPanel
import java.time.Instant
import java.time.LocalDate

@Composable
fun ScheduleScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenTeacher: (String) -> Unit,
    onOpenNow: () -> Unit,
    source: ScheduleSource = ScheduleSource.GROUP,
    selectionOverride: UserSelection? = null,
    requestedDate: LocalDate? = null,
    onDateHandled: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val viewModel: ScheduleViewModel = viewModel(
        key = selectionOverride?.subject(source)?.key ?: source.name,
        factory = viewModelFactory {
            initializer {
                ScheduleViewModel(
                    scheduleRepository = container.scheduleFor(source),
                    settingsRepository = container.settingsRepository,
                    refreshManually = { group, weekId ->
                        container.scheduleNotifications.refreshManually(group, weekId, source)
                    },
                    source = source,
                    selectionOverride = selectionOverride,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Возврат из фона или настроек должен проверить, не появилось ли расписание.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onScreenShown()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onScreenHidden()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onScreenHidden() }
    }

    // Будущие недели отсутствуют в .ics — догружаем выбранную неделю из HTML.
    LaunchedEffect(viewModel, state.selectedWeekId, state.schedule?.weeks, state.isRefreshing) {
        state.selectedWeekId?.let { viewModel.ensureWeekLoaded(it) }
    }
    LaunchedEffect(requestedDate, state.schedule?.weeks, state.isInitialLoading) {
        if (requestedDate != null && (viewModel.openDate(requestedDate) || !state.isInitialLoading)) {
            onDateHandled()
        }
    }

    ScheduleScreenContent(
        state = ScheduleScreenState(
            subject = state.selection?.subject(source),
            entries = state.schedule?.entries(),
            selectedWeekId = state.selectedWeekId,
            isInitialLoading = state.isInitialLoading,
            isRefreshing = state.isRefreshing,
            loadingWeekId = state.loadingWeekId,
            isOffline = state.isOffline,
            error = state.error,
            lastUpdated = state.lastUpdated,
            groupNotFound = state.groupNotFound,
            focusDate = state.focusDate,
            staleWeeks = state.schedule?.staleWeeks.orEmpty(),
            roomCoverage = state.schedule?.roomCoverage.orEmpty(),
        ),
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onOpenTeacher = onOpenTeacher,
        onOpenNow = onOpenNow,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onPreviousWeek = viewModel::previousWeek,
        onNextWeek = viewModel::nextWeek,
        onSelectWeek = viewModel::selectWeek,
        onToday = viewModel::goToCurrentWeek,
        onDateFocused = viewModel::clearDateFocus,
    )
}

internal data class ScheduleScreenState(
    val subject: ScheduleSubject?,
    val entries: ScheduleEntries? = null,
    val selectedWeekId: Int? = null,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val loadingWeekId: Int? = null,
    val isOffline: Boolean = false,
    val error: AppError? = null,
    val lastUpdated: Instant? = null,
    val groupNotFound: Boolean = false,
    val focusDate: LocalDate? = null,
    val staleWeeks: Set<Int> = emptySet(),
    val roomCoverage: Map<Int, RoomWeekCoverage> = emptyMap(),
)

@Composable
internal fun ScheduleScreenContent(
    state: ScheduleScreenState,
    onOpenSettings: () -> Unit,
    onOpenTeacher: (String) -> Unit,
    onOpenNow: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectWeek: (Int) -> Unit,
    onToday: () -> Unit,
    onDateFocused: (LocalDate) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    var showWeekPicker by rememberSaveable { mutableStateOf(false) }
    var detailsLesson by remember { mutableStateOf<Lesson?>(null) }

    val subject = state.subject
    val schedule = state.entries
    val weeks = remember(schedule?.weeks) {
        schedule?.weeks?.sortedBy { it.startDate }.orEmpty()
    }
    val weeksWithLessons = remember(weeks, schedule?.lessons) {
        schedule?.lessons.orEmpty()
            .mapNotNull { ScheduleTime.findWeek(weeks, it.date)?.id }
            .toSet()
    }
    val currentWeekId = ScheduleTime.currentWeek(weeks, ScheduleTime.today())?.id

    // При смене группы листы со старыми данными закрываем.
    LaunchedEffect(subject?.key) {
        detailsLesson = null
        showWeekPicker = false
    }

    Scaffold(
        topBar = {
            ScheduleTopBar(
                subject = subject,
                onOpenSettings = onOpenSettings,
                onOpenNow = onOpenNow,
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                subject == null -> {
                    if (state.isInitialLoading) {
                        ScheduleLoadingState()
                    } else {
                        NoSelectionState(onOpenSettings = onOpenSettings)
                    }
                }

                schedule == null -> {
                    if (state.isInitialLoading) {
                        ScheduleLoadingState()
                    } else {
                        ScheduleErrorState(
                            error = state.error,
                            groupNotFound = state.groupNotFound,
                            onRetry = onRetry,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }

                else -> ScheduleBody(
                    state = state,
                    schedule = schedule,
                    subject = subject,
                    weeks = weeks,
                    weeksWithLessons = weeksWithLessons,
                    currentWeekId = currentWeekId,
                    onRefresh = onRefresh,
                    onPreviousWeek = onPreviousWeek,
                    onNextWeek = onNextWeek,
                    onToday = onToday,
                    onChooseWeek = { showWeekPicker = true },
                    onOpenLesson = { detailsLesson = it },
                    onDateFocused = onDateFocused,
                )
            }
        }
    }

    val selectedWeekId = state.selectedWeekId
    if (showWeekPicker && schedule != null && selectedWeekId != null) {
        WeekPickerSheet(
            weeks = weeks,
            selectedWeekId = selectedWeekId,
            currentWeekId = currentWeekId,
            weeksWithLessons = weeksWithLessons,
            onSelectWeek = { onSelectWeek(it.id) },
            onDismiss = { showWeekPicker = false },
        )
    }

    val lesson = detailsLesson
    if (lesson != null && subject != null && schedule != null) {
        LessonDetailsSheet(
            lesson = lesson,
            sourceUrl = subject.sourceUrl(lesson),
            showTeachers = true,
            onTeacherClick = { teacher ->
                detailsLesson = null
                onOpenTeacher(teacher)
            },
            onDismiss = { detailsLesson = null },
        )
    }
}

@Composable
private fun ScheduleTopBar(
    subject: ScheduleSubject?,
    onOpenSettings: () -> Unit,
    onOpenNow: () -> Unit,
    onBack: (() -> Unit)?,
) {
    ScheduleHeader(
        title = subject?.title ?: stringResource(R.string.app_name),
        subtitle = if (subject?.fromRooms == true) {
            stringResource(R.string.schedule_source_group_from_rooms)
        } else subject?.subtitle,
        onBack = onBack,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (subject != null) {
                FilledTonalButton(onClick = onOpenNow, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.schedule_now))
                }
            }
            FilledTonalIconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleBody(
    state: ScheduleScreenState,
    schedule: ScheduleEntries,
    subject: ScheduleSubject,
    weeks: List<Week>,
    weeksWithLessons: Set<Int>,
    currentWeekId: Int?,
    onRefresh: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
    onChooseWeek: () -> Unit,
    onOpenLesson: (Lesson) -> Unit,
    onDateFocused: (LocalDate) -> Unit,
) {
    val fromRooms = subject.fromRooms
    val selectedWeek = weeks.firstOrNull { it.id == state.selectedWeekId }
    val selectedIndex = weeks.indexOfFirst { it.id == state.selectedWeekId }
    val canGoPrevious = selectedIndex > 0
    val canGoNext = selectedIndex >= 0 && selectedIndex < weeks.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .weekSwipe(
                canGoPrevious = canGoPrevious,
                canGoNext = canGoNext,
                onPrevious = onPreviousWeek,
                onNext = onNextWeek,
            ),
    ) {
        if (selectedWeek != null) {
            WeekPanel(
                week = selectedWeek,
                weeks = weeks,
                weeksWithLessons = weeksWithLessons,
                currentWeekId = currentWeekId,
                canGoPrevious = canGoPrevious,
                canGoNext = canGoNext,
                onPrevious = onPreviousWeek,
                onNext = onNextWeek,
                onToday = onToday,
                onChooseWeek = onChooseWeek,
            )
        }

        if ((state.error != null || state.isOffline || selectedWeek?.id in state.staleWeeks) &&
            (!fromRooms || selectedWeek?.id in schedule.publications)
        ) {
            OfflineBanner(
                message = stringResource(
                    if (state.isOffline || state.error is AppError.Network) {
                        R.string.schedule_offline
                    } else {
                        R.string.schedule_stale
                    },
                ),
                updatedAt = state.lastUpdated.takeUnless { fromRooms },
            )
        } else if (!fromRooms && state.lastUpdated != null) {
            UpdatedAtLabel(updatedAt = state.lastUpdated)
        }

        if (fromRooms && selectedWeek != null) {
            val coverage = state.roomCoverage[selectedWeek.id]
            val loading = state.loadingWeekId == selectedWeek.id || state.isRefreshing || coverage?.loading == true
            if (loading || coverage != null && !coverage.complete) {
                RoomWeekProgress(coverage, loading)
            }
        }

        ScheduleRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val weekRange = selectedWeek?.let { it.startDate..it.startDate.plusDays(6) }
            val hasSelectedWeekLessons = weekRange != null &&
                schedule.lessons.any { it.date in weekRange }

            // Не подменяем список на спиннер: PullToRefreshBox должен получать
            // прокрутку от содержимого. Индикатор показываем поверх списка.
            Box(Modifier.fillMaxSize()) {
                WeekTransition(
                    week = selectedWeek,
                    modifier = Modifier.fillMaxSize(),
                ) { week ->
                    val publication = week?.id?.let(schedule.publications::get)
                    val roomWeekPending = fromRooms && publication == null
                    val hasLessons = week != null &&
                        schedule.lessons.any { it.date in week.startDate..week.startDate.plusDays(6) }
                    when {
                        roomWeekPending && !hasLessons && state.error != null -> ScheduleErrorState(
                            error = state.error,
                            groupNotFound = false,
                            onRetry = onRefresh,
                            onOpenSettings = null,
                        )
                        roomWeekPending && !hasLessons -> Unit
                        else -> ScheduleWeekList(
                            lessons = schedule.lessons,
                            week = week,
                            contentKey = subject.key,
                            currentWeekId = currentWeekId,
                            canGoPrevious = canGoPrevious,
                            canGoNext = canGoNext,
                            showTeachers = true,
                            showGroups = false,
                            onPreviousWeek = onPreviousWeek,
                            onNextWeek = onNextWeek,
                            onOpenLesson = onOpenLesson,
                            holidays = schedule.holidays,
                            publication = publication,
                            focusDate = state.focusDate.takeIf { week?.id == state.selectedWeekId },
                            onDateFocused = onDateFocused,
                            fromRooms = fromRooms,
                            partial = roomWeekPending,
                        )
                    }
                }
                if (!fromRooms && selectedWeek != null &&
                    state.loadingWeekId == selectedWeek.id &&
                    !hasSelectedWeekLessons
                ) {
                    LoadingBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RoomWeekProgress(coverage: RoomWeekCoverage?, loading: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when {
                coverage == null || coverage.total == 0 -> stringResource(R.string.room_checks_starting)
                loading -> stringResource(R.string.room_checks_progress, coverage.checked, coverage.total)
                else -> stringResource(R.string.room_checks_incomplete, coverage.checked, coverage.total)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) {
            if (coverage != null && coverage.total > 0) {
                LinearProgressIndicator(
                    progress = { coverage.checked.toFloat() / coverage.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LoadingBar(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun UpdatedAtLabel(updatedAt: Instant) {
    Text(
        text = stringResource(
            R.string.schedule_updated_at,
            ScheduleTime.formatUpdatedAt(updatedAt),
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 8.dp),
    )
}
