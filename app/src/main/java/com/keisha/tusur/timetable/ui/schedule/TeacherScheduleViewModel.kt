package com.keisha.tusur.timetable.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.data.repository.TeacherScheduleLoadResult
import com.keisha.tusur.timetable.data.repository.TeacherScheduleRepository
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.Teacher
import com.keisha.tusur.timetable.domain.model.TeacherSchedule
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Состояние экрана преподавателя. Неделя хранится по id, как и на главном экране:
 * после обновления тот же id указывает на свежий объект [Week].
 */
data class TeacherScheduleUiState(
    val teacherName: String,
    val teacher: Teacher? = null,
    val weeks: List<Week> = emptyList(),
    val lessons: List<Lesson> = emptyList(),
    val selectedWeekId: Int? = null,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val error: AppError? = null,
    val lastUpdated: Instant? = null,
)

class TeacherScheduleViewModel(
    private val teacherName: String,
    private val repository: TeacherScheduleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TeacherScheduleUiState(teacherName = teacherName))
    val state: StateFlow<TeacherScheduleUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    fun retry() = refresh()

    fun selectWeek(weekId: Int) {
        if (_state.value.weeks.none { it.id == weekId }) return
        _state.update { it.copy(selectedWeekId = weekId) }
    }

    fun previousWeek() = shiftWeek(-1)

    fun nextWeek() = shiftWeek(1)

    fun goToCurrentWeek() {
        val current = ScheduleTime.currentWeek(_state.value.weeks, ScheduleTime.today()) ?: return
        _state.update { it.copy(selectedWeekId = current.id) }
    }

    private fun shiftWeek(delta: Int) {
        val current = _state.value
        val weeks = current.weeks.sortedBy { it.startDate }
        val index = weeks.indexOfFirst { it.id == current.selectedWeekId }
        if (index < 0) return
        val target = weeks.getOrNull(index + delta) ?: return
        _state.update { it.copy(selectedWeekId = target.id) }
    }

    private fun load(forceRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val hasContent = _state.value.weeks.isNotEmpty()
            _state.update {
                it.copy(
                    isInitialLoading = !hasContent,
                    isRefreshing = forceRefresh && hasContent,
                    error = null,
                )
            }

            if (!forceRefresh) {
                val cached = cachedSchedule()
                if (cached != null) {
                    applySchedule(
                        schedule = cached,
                        updatedAt = cached.fetchedAt,
                        isOffline = false,
                        error = null,
                    )
                }
            }

            applyLoadResult(loadSchedule(forceRefresh))
        }
    }

    private fun applyLoadResult(result: TeacherScheduleLoadResult) {
        val schedule = result.schedule
        if (schedule == null) {
            _state.update { current ->
                if (current.weeks.isNotEmpty()) {
                    // Кэш уже на экране: не затираем его, только показываем баннер.
                    current.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isOffline = result.isOffline,
                        error = result.error ?: AppError.Unknown(),
                    )
                } else {
                    current.copy(
                        teacher = null,
                        weeks = emptyList(),
                        lessons = emptyList(),
                        selectedWeekId = null,
                        isInitialLoading = false,
                        isRefreshing = false,
                        isOffline = result.isOffline,
                        error = result.error ?: AppError.Unknown(),
                        lastUpdated = result.updatedAt,
                    )
                }
            }
            return
        }

        applySchedule(
            schedule = schedule,
            updatedAt = result.updatedAt ?: schedule.fetchedAt,
            isOffline = result.isOffline,
            error = result.error,
        )
    }

    private fun applySchedule(
        schedule: TeacherSchedule,
        updatedAt: Instant,
        isOffline: Boolean,
        error: AppError?,
    ) {
        val weeks = schedule.weeks
        val weekId = _state.value.selectedWeekId
            ?.takeIf { id -> weeks.any { it.id == id } }
            ?: ScheduleTime.currentWeek(weeks, ScheduleTime.today())?.id
            ?: weeks.minByOrNull { it.startDate }?.id

        _state.update {
            it.copy(
                teacher = schedule.teacher,
                weeks = weeks,
                lessons = schedule.lessons,
                selectedWeekId = weekId,
                isInitialLoading = false,
                isRefreshing = false,
                isOffline = isOffline,
                error = error,
                lastUpdated = updatedAt,
            )
        }
    }

    private suspend fun cachedSchedule(): TeacherSchedule? =
        try {
            repository.cached(teacherName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun loadSchedule(forceRefresh: Boolean): TeacherScheduleLoadResult =
        try {
            repository.load(teacherName, forceRefresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            TeacherScheduleLoadResult(
                schedule = null,
                updatedAt = null,
                isFromCache = false,
                isOffline = e is AppError.Network,
                error = e,
            )
        } catch (e: Exception) {
            TeacherScheduleLoadResult(
                schedule = null,
                updatedAt = null,
                isFromCache = false,
                isOffline = false,
                error = AppError.Unknown(e),
            )
        }
}
