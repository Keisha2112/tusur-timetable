package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.data.repository.RoomScheduleRepository
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.RoomSchedule
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Composable
internal fun RoomScheduleScreen(
    container: AppContainer,
    room: Auditorium,
    onOpenSettings: () -> Unit,
    onOpenTeacher: (String) -> Unit,
    onOpenNow: () -> Unit,
) {
    val viewModel: RoomScheduleViewModel = viewModel(
        key = room.subject().key,
        factory = viewModelFactory {
            initializer { RoomScheduleViewModel(room, container.roomScheduleRepository) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenShown() }
    DisposableEffect(viewModel) { onDispose(viewModel::onScreenHidden) }

    ScheduleScreenContent(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenTeacher = onOpenTeacher,
        onOpenNow = onOpenNow,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
        onPreviousWeek = { viewModel.shiftWeek(-1) },
        onNextWeek = { viewModel.shiftWeek(1) },
        onSelectWeek = viewModel::selectWeek,
        onToday = viewModel::goToCurrentWeek,
    )
}

private class RoomScheduleViewModel(
    private val room: Auditorium,
    private val repository: RoomScheduleRepository,
) : ViewModel() {
    private data class Request(
        val week: Week? = null,
        val loading: Boolean = true,
        val manual: Boolean = false,
        val error: AppError? = null,
    )

    private val subject = room.subject()
    private val request = MutableStateFlow(Request())
    private val cached = repository.observe(room).catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    private var loadJob: Job? = null

    val state = combine(cached, request) { schedule, request ->
        val week = request.week ?: defaultWeek(schedule)
        ScheduleScreenState(
            subject = subject,
            entries = schedule?.entries,
            selectedWeekId = week?.id,
            isInitialLoading = schedule == null && request.loading,
            isRefreshing = request.loading && request.manual && schedule != null,
            loadingWeekId = week?.id.takeIf { request.loading },
            isOffline = request.error is AppError.Network,
            error = request.error,
            staleWeeks = schedule?.staleWeeks.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ScheduleScreenState(subject))

    init {
        load(null)
    }

    fun onScreenShown() {
        if (loadJob?.isActive != true) load(request.value.week)
    }

    fun onScreenHidden() {
        loadJob?.cancel()
    }

    fun refresh() = load(request.value.week, force = true)

    fun selectWeek(id: Int) {
        val week = cached.value?.entries?.weeks?.firstOrNull { it.id == id } ?: return
        load(week)
    }

    fun shiftWeek(delta: Int) {
        val weeks = cached.value?.entries?.weeks.orEmpty()
        val index = weeks.indexOfFirst { it.id == state.value.selectedWeekId }
        if (index >= 0) weeks.getOrNull(index + delta)?.let { load(it) }
    }

    fun goToCurrentWeek() {
        val week = defaultWeek(cached.value) ?: return
        load(week)
    }

    private fun load(week: Week?, force: Boolean = false) {
        loadJob?.cancel()
        request.value = Request(week, manual = force)
        loadJob = viewModelScope.launch {
            val error = repository.load(room, week, force)
            request.value = Request(week, loading = false, error = error)
        }
    }

    private fun defaultWeek(schedule: RoomSchedule?): Week? {
        val weeks = schedule?.entries?.weeks.orEmpty()
        return ScheduleTime.currentWeek(weeks) ?: weeks.firstOrNull()
    }
}
