package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.components.OfflineBanner
import com.keisha.tusur.timetable.ui.components.ScheduleErrorState
import com.keisha.tusur.timetable.ui.components.ScheduleLoadingState
import com.keisha.tusur.timetable.ui.components.WeekPanel

/**
 * Расписание преподавателя: тот же список недель и карточки занятий, что у группы.
 * Преподаватель открывается по короткому имени из карточки занятия.
 */
@Composable
fun TeacherScheduleScreen(
    container: AppContainer,
    teacherName: String,
    onBack: () -> Unit,
) {
    val viewModel: TeacherScheduleViewModel = viewModel(
        key = "teacher:$teacherName",
        factory = viewModelFactory {
            initializer {
                TeacherScheduleViewModel(
                    teacherName = teacherName,
                    repository = container.teacherScheduleRepository,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    TeacherScheduleContent(
        state = state,
        onBack = onBack,
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
private fun TeacherScheduleContent(
    state: TeacherScheduleUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectWeek: (Int) -> Unit,
    onToday: () -> Unit,
) {
    var showWeekPicker by rememberSaveable { mutableStateOf(false) }
    var detailsLesson by remember { mutableStateOf<Lesson?>(null) }

    val weeks = remember(state.weeks) { state.weeks.sortedBy { it.startDate } }
    val weeksWithLessons = remember(weeks, state.lessons) {
        state.lessons
            .mapNotNull { ScheduleTime.findWeek(weeks, it.date)?.id }
            .toSet()
    }
    val currentWeekId = ScheduleTime.currentWeek(weeks, ScheduleTime.today())?.id
    val scheduleAvailable = weeks.isNotEmpty()

    LaunchedEffect(state.teacherName) {
        detailsLesson = null
        showWeekPicker = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.teacher?.shortName ?: state.teacherName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.teacher_schedule_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
                !scheduleAvailable -> {
                    if (state.isInitialLoading) {
                        ScheduleLoadingState()
                    } else {
                        ScheduleErrorState(
                            error = state.error,
                            groupNotFound = false,
                            onRetry = onRetry,
                            onOpenSettings = null,
                            notFoundMessageRes = R.string.teacher_not_found,
                        )
                    }
                }

                else -> {
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
                                onChooseWeek = { showWeekPicker = true },
                            )
                        }

                        if (state.error != null || state.isOffline) {
                            OfflineBanner(
                                message = stringResource(
                                    if (state.isOffline) {
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
                                lessons = state.lessons,
                                week = selectedWeek,
                                contentKey = state.teacher?.id ?: state.teacherName,
                                currentWeekId = currentWeekId,
                                canGoPrevious = canGoPrevious,
                                canGoNext = canGoNext,
                                showTeachers = false,
                                showGroups = true,
                                onPreviousWeek = onPreviousWeek,
                                onNextWeek = onNextWeek,
                                onOpenLesson = { detailsLesson = it },
                            )
                        }
                    }

                    val selectedWeekId = state.selectedWeekId
                    if (showWeekPicker && selectedWeekId != null) {
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
                    val teacher = state.teacher
                    if (lesson != null && teacher != null) {
                        LessonDetailsSheet(
                            lesson = lesson,
                            sourceUrl = Urls.teacher(teacher.id),
                            showTeachers = false,
                            showGroups = true,
                            onTeacherClick = null,
                            onDismiss = { detailsLesson = null },
                        )
                    }
                }
            }
        }
    }
}
