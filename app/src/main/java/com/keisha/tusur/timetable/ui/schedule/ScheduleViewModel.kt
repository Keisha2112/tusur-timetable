package com.keisha.tusur.timetable.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.data.repository.ScheduleLoadResult
import com.keisha.tusur.timetable.data.repository.ScheduleRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Состояние главного экрана. Неделя хранится по id: после фонового обновления
 * тот же id указывает на свежий объект [Week], а исчезнувшая неделя заменяется текущей.
 */
data class ScheduleUiState(
    val selection: UserSelection? = null,
    val schedule: GroupSchedule? = null,
    val selectedWeekId: Int? = null,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val error: AppError? = null,
    val lastUpdated: Instant? = null,
    val groupNotFound: Boolean = false,
)

class ScheduleViewModel(
    private val scheduleRepository: ScheduleRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    private var appliedSelection: UserSelection? = null
    private var selectionResolved = false
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // Сбой DataStore не должен оставлять экран в вечном загрузочном состоянии.
            settingsRepository.selection
                .catch { emit(null) }
                .collect(::onSelectionChanged)
        }
    }

    fun refresh() {
        val selection = _state.value.selection ?: return
        load(selection, forceRefresh = true)
    }

    fun retry() = refresh()

    fun selectWeek(weekId: Int) {
        val exists = _state.value.schedule?.weeks?.any { it.id == weekId } == true
        if (!exists) return
        _state.update { it.copy(selectedWeekId = weekId) }
    }

    fun previousWeek() = shiftWeek(-1)

    fun nextWeek() = shiftWeek(1)

    fun goToCurrentWeek() {
        val weeks = _state.value.schedule?.weeks ?: return
        val current = ScheduleTime.currentWeek(weeks, ScheduleTime.today()) ?: return
        _state.update { it.copy(selectedWeekId = current.id) }
    }

    private fun onSelectionChanged(selection: UserSelection?) {
        // DataStore может повторно отдать тот же выбор — перезагружать в этом случае не нужно.
        if (selectionResolved && selection == appliedSelection) return
        selectionResolved = true
        appliedSelection = selection

        if (selection == null) {
            loadJob?.cancel()
            _state.value = ScheduleUiState(selection = null, isInitialLoading = false)
            return
        }

        val previous = _state.value.selection
        if (previous != null && previous.groupSlug != selection.groupSlug) {
            // Другая группа: старые данные на экране не показываем, ждём кэш новой.
            _state.update {
                it.copy(
                    selection = selection,
                    schedule = null,
                    selectedWeekId = null,
                    error = null,
                    lastUpdated = null,
                    isOffline = false,
                    isRefreshing = false,
                    groupNotFound = false,
                    isInitialLoading = true,
                )
            }
        }
        load(selection, forceRefresh = false)
    }

    private fun load(selection: UserSelection, forceRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val hasContent = _state.value.schedule != null
            _state.update {
                it.copy(
                    selection = selection,
                    isInitialLoading = !hasContent,
                    isRefreshing = forceRefresh && hasContent,
                    error = null,
                    groupNotFound = false,
                )
            }

            val slug = selection.groupSlug
            if (!forceRefresh) {
                // Кэш и время последнего обновления читаем параллельно, экран показываем сразу.
                val (cached, cachedUpdatedAt) = coroutineScope {
                    val scheduleDeferred = async { cachedSchedule(slug) }
                    val updatedDeferred = async { lastUpdatedAt(slug) }
                    scheduleDeferred.await() to updatedDeferred.await()
                }
                if (cached != null) {
                    applySchedule(
                        schedule = cached,
                        updatedAt = cachedUpdatedAt ?: cached.fetchedAt,
                        isOffline = false,
                        error = null,
                    )
                }
            }

            val group = Group(
                facultyCode = selection.facultyCode,
                slug = slug,
                name = selection.groupName,
            )
            applyLoadResult(loadSchedule(group, forceRefresh))
        }
    }

    private fun applyLoadResult(result: ScheduleLoadResult) {
        val schedule = result.schedule
        val error = result.error ?: AppError.Unknown()
        if (schedule == null) {
            _state.update { current ->
                if (current.schedule != null) {
                    // Кэш уже на экране: не затираем его, только показываем баннер.
                    current.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isOffline = result.isOffline,
                        error = error,
                        lastUpdated = result.updatedAt ?: current.lastUpdated,
                        groupNotFound = result.error is AppError.NotFound,
                    )
                } else {
                    current.copy(
                        schedule = null,
                        selectedWeekId = null,
                        isInitialLoading = false,
                        isRefreshing = false,
                        isOffline = result.isOffline,
                        error = error,
                        lastUpdated = result.updatedAt,
                        groupNotFound = result.error is AppError.NotFound,
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
        schedule: GroupSchedule,
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
                schedule = schedule,
                selectedWeekId = weekId,
                isInitialLoading = false,
                isRefreshing = false,
                isOffline = isOffline,
                error = error,
                lastUpdated = updatedAt,
                groupNotFound = error is AppError.NotFound,
            )
        }
    }

    private fun shiftWeek(delta: Int) {
        val current = _state.value
        val weeks = current.schedule?.weeks?.sortedBy { it.startDate } ?: return
        val index = weeks.indexOfFirst { it.id == current.selectedWeekId }
        if (index < 0) return
        val target = weeks.getOrNull(index + delta) ?: return
        _state.update { it.copy(selectedWeekId = target.id) }
    }

    private suspend fun cachedSchedule(slug: String): GroupSchedule? =
        try {
            scheduleRepository.cached(slug)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun lastUpdatedAt(slug: String): Instant? =
        try {
            scheduleRepository.lastUpdated(slug)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun loadSchedule(group: Group, forceRefresh: Boolean): ScheduleLoadResult =
        try {
            scheduleRepository.load(group, forceRefresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            // Репозиторий ошибки не выбрасывает, но если это случится — сохраняем тип.
            ScheduleLoadResult(
                schedule = null,
                updatedAt = null,
                isFromCache = false,
                isOffline = e is AppError.Network,
                error = e,
            )
        } catch (e: Exception) {
            ScheduleLoadResult(
                schedule = null,
                updatedAt = null,
                isFromCache = false,
                isOffline = false,
                error = AppError.Unknown(e),
            )
        }
}
