package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.components.NoSelectionState
import com.keisha.tusur.timetable.ui.components.OfflineBanner
import com.keisha.tusur.timetable.ui.components.ScheduleErrorState
import com.keisha.tusur.timetable.ui.components.ScheduleLoadingState
import com.keisha.tusur.timetable.ui.components.WeekPanel
import java.time.Instant

@Composable
fun ScheduleScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenTeacher: (String) -> Unit,
) {
    val viewModel: ScheduleViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ScheduleViewModel(
                    scheduleRepository = container.scheduleRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    ScheduleScreenContent(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenTeacher = onOpenTeacher,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onPreviousWeek = viewModel::previousWeek,
        onNextWeek = viewModel::nextWeek,
        onSelectWeek = viewModel::selectWeek,
        onToday = viewModel::goToCurrentWeek,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleScreenContent(
    state: ScheduleUiState,
    onOpenSettings: () -> Unit,
    onOpenTeacher: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectWeek: (Int) -> Unit,
    onToday: () -> Unit,
) {
    var showWeekPicker by rememberSaveable { mutableStateOf(false) }
    var detailsLesson by remember { mutableStateOf<Lesson?>(null) }

    val schedule = state.schedule
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
    LaunchedEffect(schedule?.group?.slug) {
        detailsLesson = null
        showWeekPicker = false
    }

    Scaffold(
        topBar = {
            ScheduleTopBar(
                selection = state.selection,
                onOpenSettings = onOpenSettings,
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
                state.selection == null -> {
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
                    weeks = weeks,
                    weeksWithLessons = weeksWithLessons,
                    currentWeekId = currentWeekId,
                    onRefresh = onRefresh,
                    onPreviousWeek = onPreviousWeek,
                    onNextWeek = onNextWeek,
                    onToday = onToday,
                    onChooseWeek = { showWeekPicker = true },
                    onOpenLesson = { detailsLesson = it },
                    onOpenTeacher = onOpenTeacher,
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
    if (lesson != null && schedule != null) {
        LessonDetailsSheet(
            lesson = lesson,
            sourceUrl = Urls.group(schedule.group.facultyCode, schedule.group.slug),
            showTeachers = true,
            showGroups = false,
            onTeacherClick = { teacher ->
                detailsLesson = null
                onOpenTeacher(teacher)
            },
            onDismiss = { detailsLesson = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTopBar(
    selection: UserSelection?,
    onOpenSettings: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = selection?.groupName ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selection != null) {
                    Text(
                        text = stringResource(
                            R.string.schedule_faculty_and_group,
                            selection.facultyName,
                            selection.groupName,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleBody(
    state: ScheduleUiState,
    schedule: GroupSchedule,
    weeks: List<Week>,
    weeksWithLessons: Set<Int>,
    currentWeekId: Int?,
    onRefresh: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
    onChooseWeek: () -> Unit,
    onOpenLesson: (Lesson) -> Unit,
    onOpenTeacher: (String) -> Unit,
) {
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

        if (state.error != null || state.isOffline) {
            OfflineBanner(
                message = stringResource(
                    if (state.isOffline || state.error is AppError.Network) {
                        R.string.schedule_offline
                    } else {
                        R.string.schedule_stale
                    },
                ),
                updatedAt = state.lastUpdated,
            )
        } else if (state.lastUpdated != null) {
            UpdatedAtLabel(updatedAt = state.lastUpdated)
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            ScheduleWeekList(
                lessons = schedule.lessons,
                week = selectedWeek,
                contentKey = schedule.group.slug,
                currentWeekId = currentWeekId,
                canGoPrevious = canGoPrevious,
                canGoNext = canGoNext,
                showTeachers = true,
                showGroups = false,
                onPreviousWeek = onPreviousWeek,
                onNextWeek = onNextWeek,
                onOpenLesson = onOpenLesson,
            )
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
            .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
    )
}
