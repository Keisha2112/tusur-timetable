package com.keisha.tusur.timetable.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.data.repository.ScheduleLoadResult
import com.keisha.tusur.timetable.data.repository.ScheduleRepository
import com.keisha.tusur.timetable.data.repository.ScheduleRefreshReason
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

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
    /** Какая неделя сейчас догружается со страницы сайта. */
    val loadingWeekId: Int? = null,
    val isOffline: Boolean = false,
    val error: AppError? = null,
    val lastUpdated: Instant? = null,
    val groupNotFound: Boolean = false,
    val focusDate: LocalDate? = null,
)

class ScheduleViewModel(
    private val scheduleRepository: ScheduleRepository,
    private val settingsRepository: SettingsRepository,
    private val refreshManually: suspend (Group, Int?) -> ScheduleLoadResult = { group, weekId ->
        scheduleRepository.refresh(group, ScheduleRefreshReason.MANUAL, weekId)
    },
    private val source: ScheduleSource = ScheduleSource.GROUP,
    selectionOverride: UserSelection? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    private var appliedSelection: UserSelection? = null
    private var selectionResolved = false
    private var screenActive = false
    private var loadJob: Job? = null
    private var weekLoadJob: Job? = null
    private var weekLoadJobWeekId: Int? = null
    private var cacheJob: Job? = null

    init {
        viewModelScope.launch {
            // Сбой DataStore не должен оставлять экран в вечном загрузочном состоянии.
            (selectionOverride?.let { flowOf(it) } ?: settingsRepository.selection)
                .catch { emit(null) }
                .collect(::onSelectionChanged)
        }
    }

    fun refresh() {
        val selection = _state.value.selection ?: return
        load(selection, forceRefresh = true)
    }

    fun retry() = refresh()

    fun onScreenHidden() {
        screenActive = false
        if (source == ScheduleSource.GROUP_FROM_ROOMS) {
            loadJob?.cancel()
            weekLoadJob?.cancel()
            _state.update { it.copy(isRefreshing = false, loadingWeekId = null) }
        }
    }

    /**
     * Вызывается при возврате на экран. TTL решает репозиторий, поэтому частые
     * возвраты из настроек не приводят к лишним запросам.
     */
    fun onScreenShown() {
        screenActive = true
        val selection = _state.value.selection ?: return
        if (loadJob?.isActive == true || weekLoadJob?.isActive == true) return
        val selectedWeekId = _state.value.selectedWeekId
        if (source == ScheduleSource.GROUP_FROM_ROOMS && selectedWeekId != null) {
            ensureWeekLoaded(selectedWeekId)
        } else load(selection, forceRefresh = false)
    }

    /**
     * Догружает выбранную неделю из HTML. Это основной путь для будущих недель:
     * `.ics` сайта обрезан текущим учебным периодом.
     */
    fun ensureWeekLoaded(weekId: Int, forceRefresh: Boolean = false) {
        if (!screenActive) return
        val current = _state.value
        val schedule = current.schedule ?: return
        val week = schedule.weeks.firstOrNull { it.id == weekId } ?: return
        // Во время ручной загрузки всех недель отдельный запрос не нужен:
        // выбранная неделя уже входит в общий список.
        if (!forceRefresh && current.isRefreshing) return
        if (source == ScheduleSource.GROUP_FROM_ROOMS && loadJob?.isActive == true &&
            ScheduleTime.currentWeek(schedule.weeks)?.id == weekId
        ) return
        if (weekLoadJob?.isActive == true) {
            if (weekLoadJobWeekId == weekId) return
            // Пользователь уже пролистнул дальше — старый запрос больше не нужен.
            weekLoadJob?.cancel()
        }

        if (!forceRefresh) {
            // В HTML есть примечания и праздники даже у недель с занятиями в ICS.
            val checkedAt = schedule.checkedWeeks[weekId]
            if (checkedAt != null) {
                val age = System.currentTimeMillis() - checkedAt
                val ttl = if (source == ScheduleSource.GROUP_FROM_ROOMS) ROOM_WEEK_REFRESH_MS else WEEK_REFRESH_MS
                if (age in 0 until ttl) return
            }
        }

        val selection = current.selection ?: return
        val group = selection.toGroup()

        weekLoadJobWeekId = weekId
        weekLoadJob = viewModelScope.launch {
            _state.update { it.copy(loadingWeekId = weekId) }
            try {
                val result = loadWeekSchedule(group, week, forceRefresh)
                _state.update { it.copy(loadingWeekId = null) }
                applyLoadResult(result)
            } finally {
                if (weekLoadJobWeekId == weekId) {
                    weekLoadJobWeekId = null
                }
            }
        }
    }

    fun selectWeek(weekId: Int) {
        val current = _state.value
        if (current.schedule?.weeks?.none { it.id == weekId } != false) return
        _state.update { it.copy(selectedWeekId = weekId, focusDate = null) }
        if (source == ScheduleSource.GROUP_FROM_ROOMS && current.selectedWeekId != weekId &&
            loadJob?.isActive == true && !current.isRefreshing
        ) loadJob?.cancel()
    }

    fun openDate(date: LocalDate): Boolean {
        val weeks = _state.value.schedule?.weeks ?: return false
        val week = ScheduleTime.findWeek(weeks, date) ?: return false
        selectWeek(week.id)
        _state.update { it.copy(focusDate = date) }
        return true
    }

    fun clearDateFocus(date: LocalDate) {
        _state.update { if (it.focusDate == date) it.copy(focusDate = null) else it }
    }

    fun previousWeek() = shiftWeek(-1)

    fun nextWeek() = shiftWeek(1)

    fun goToCurrentWeek() {
        val weeks = _state.value.schedule?.weeks ?: return
        val current = ScheduleTime.currentWeek(weeks, ScheduleTime.today()) ?: return
        selectWeek(current.id)
    }

    private fun onSelectionChanged(selection: UserSelection?) {
        // DataStore может повторно отдать тот же выбор — перезагружать в этом случае не нужно.
        if (selectionResolved && selection == appliedSelection) return
        selectionResolved = true
        appliedSelection = selection
        cacheJob?.cancel()

        if (selection == null) {
            loadJob?.cancel()
            weekLoadJob?.cancel()
            _state.value = ScheduleUiState(selection = null, isInitialLoading = false)
            return
        }

        val previous = _state.value.selection
        if (previous != null && (previous.groupSlug != selection.groupSlug ||
                previous.facultyCode != selection.facultyCode)
        ) {
            loadJob?.cancel()
            weekLoadJob?.cancel()
            // Другая группа: старые данные на экране не показываем, ждём кэш новой.
            _state.value = ScheduleUiState(selection = selection)
        } else {
            _state.update { it.copy(selection = selection) }
        }
        val group = selection.toGroup()
        cacheJob = viewModelScope.launch {
            scheduleRepository.observe(group).catch { }.collect { cached ->
                _state.update { current ->
                    if (current.selection != selection || cached == null) return@update current
                    current.copy(
                        schedule = cached,
                        lastUpdated = cached.fetchedAt.takeUnless { it == Instant.EPOCH },
                        selectedWeekId = resolveWeek(cached, current.selectedWeekId),
                        isInitialLoading = false,
                    )
                }
            }
        }
        if (screenActive) load(selection, forceRefresh = false)
    }

    private fun load(selection: UserSelection, forceRefresh: Boolean) {
        loadJob?.cancel()
        weekLoadJob?.cancel()
        weekLoadJob = null
        loadJob = viewModelScope.launch {
            val hasContent = _state.value.schedule != null
            _state.update {
                it.copy(
                    selection = selection,
                    isInitialLoading = !hasContent,
                    isRefreshing = forceRefresh && hasContent,
                    loadingWeekId = null,
                    error = null,
                    groupNotFound = false,
                )
            }

            val group = selection.toGroup()
            if (!forceRefresh) {
                cachedSchedule(group)?.let { cached ->
                    applySchedule(
                        schedule = cached,
                        updatedAt = cached.fetchedAt.takeUnless { it == Instant.EPOCH },
                        isOffline = false,
                        error = null,
                    )
                }
            }

            applyLoadResult(loadSchedule(group, forceRefresh))
            if (!forceRefresh && source == ScheduleSource.GROUP) {
                // Ответ мог быть 304, и объект расписания не изменился. Явно
                // проверяем выбранную неделю, иначе пустая будущая неделя
                // останется пустой до ручного обновления.
                _state.value.selectedWeekId?.let { ensureWeekLoaded(it) }
            }
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
            updatedAt = result.updatedAt ?: schedule.fetchedAt.takeUnless { it == Instant.EPOCH },
            isOffline = result.isOffline,
            error = result.error,
        )
    }

    private fun applySchedule(
        schedule: GroupSchedule,
        updatedAt: Instant?,
        isOffline: Boolean,
        error: AppError?,
    ) {
        _state.update {
            it.copy(
                schedule = schedule,
                selectedWeekId = resolveWeek(schedule, it.selectedWeekId),
                isInitialLoading = false,
                isRefreshing = false,
                loadingWeekId = null,
                isOffline = isOffline,
                error = error,
                lastUpdated = updatedAt,
                groupNotFound = error is AppError.NotFound,
            )
        }
    }

    private fun resolveWeek(schedule: GroupSchedule, selectedId: Int?): Int? {
        val weeks = schedule.weeks
        return selectedId?.takeIf { id -> weeks.any { it.id == id } }
            ?: ScheduleTime.currentWeek(weeks, ScheduleTime.today())?.id
            ?: weeks.minByOrNull { it.startDate }?.id
    }

    private fun shiftWeek(delta: Int) {
        val current = _state.value
        val weeks = current.schedule?.weeks?.sortedBy { it.startDate } ?: return
        val index = weeks.indexOfFirst { it.id == current.selectedWeekId }
        if (index < 0) return
        val target = weeks.getOrNull(index + delta) ?: return
        selectWeek(target.id)
    }

    private suspend fun cachedSchedule(group: Group): GroupSchedule? =
        try {
            scheduleRepository.cached(group)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun loadSchedule(group: Group, forceRefresh: Boolean): ScheduleLoadResult =
        try {
            if (forceRefresh) refreshManually(group, _state.value.selectedWeekId)
            else scheduleRepository.load(group)
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

    private suspend fun loadWeekSchedule(
        group: Group,
        week: Week,
        forceRefresh: Boolean,
    ): ScheduleLoadResult =
        try {
            scheduleRepository.loadWeek(group, week, forceRefresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
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

    private companion object {
        const val WEEK_REFRESH_MS = 15L * 60 * 1000
        const val ROOM_WEEK_REFRESH_MS = 6L * 60 * 60 * 1000
    }
}
