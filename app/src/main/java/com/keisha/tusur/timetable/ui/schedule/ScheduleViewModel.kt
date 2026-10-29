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
    /** Какая неделя сейчас догружается со страницы сайта. */
    val loadingWeekId: Int? = null,
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
    private var weekLoadJob: Job? = null
    private var weekLoadJobWeekId: Int? = null

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

    /**
     * Вызывается при возврате на экран. TTL решает репозиторий, поэтому частые
     * возвраты из настроек не приводят к лишним запросам.
     */
    fun onScreenShown() {
        val selection = _state.value.selection ?: return
        if (loadJob?.isActive == true || weekLoadJob?.isActive == true) return
        load(selection, forceRefresh = false)
    }

    /**
     * Догружает выбранную неделю из HTML. Это основной путь для будущих недель:
     * `.ics` сайта обрезан текущим учебным периодом.
     */
    fun ensureWeekLoaded(weekId: Int, forceRefresh: Boolean = false) {
        val current = _state.value
        val schedule = current.schedule ?: return
        val week = schedule.weeks.firstOrNull { it.id == weekId } ?: return
        // Во время ручной загрузки всех недель отдельный запрос не нужен:
        // выбранная неделя уже входит в общий список.
        if (!forceRefresh && current.isRefreshing) return
        if (weekLoadJob?.isActive == true) {
            if (weekLoadJobWeekId == weekId) return
            // Пользователь уже пролистнул дальше — старый запрос больше не нужен.
            weekLoadJob?.cancel()
        }

        val range = week.startDate..week.startDate.plusDays(6)
        val hasLessons = schedule.lessons.any { it.date in range }
        val isPast = week.startDate.plusDays(6).isBefore(ScheduleTime.today())
        if (!forceRefresh) {
            // Если занятия уже есть в ICS, HTML не должен их подменять.
            // Для недель, загруженных из HTML, отметка в checkedWeeks разрешает
            // обновление: иначе будущая неделя останется в старом кэше.
            if (hasLessons && !schedule.checkedWeeks.containsKey(weekId)) return
            // Прошлые недели уже полностью приходят в ICS; HTML нужен только
            // для пустых архивных недель или будущих/текущих.
            if (isPast && hasLessons) return

            val checkedAt = schedule.checkedWeeks[weekId]
            if (checkedAt != null) {
                val age = System.currentTimeMillis() - checkedAt
                if (age in 0 until WEEK_REFRESH_MS) return
            }
        }

        val selection = current.selection ?: return
        val group = Group(
            facultyCode = selection.facultyCode,
            slug = selection.groupSlug,
            name = selection.groupName,
        )

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
            weekLoadJob?.cancel()
            _state.value = ScheduleUiState(selection = null, isInitialLoading = false)
            return
        }

        val previous = _state.value.selection
        if (previous != null && previous.groupSlug != selection.groupSlug) {
            weekLoadJob?.cancel()
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
            if (forceRefresh) {
                // Пользователь сам попросил обновление: подтягиваем не только
                // выбранную неделю, а все недели, которых ещё нет в кэше.
                refreshAllWeeks()
            } else {
                // Ответ мог быть 304, и объект расписания не изменился. Явно
                // проверяем выбранную неделю, иначе пустая будущая неделя
                // останется пустой до ручного обновления.
                _state.value.selectedWeekId?.let { ensureWeekLoaded(it) }
            }
        }
    }

    private suspend fun refreshAllWeeks() {
        val state = _state.value
        val selection = state.selection ?: return
        val schedule = state.schedule ?: return
        if (schedule.weeks.isEmpty()) return

        val weeksToLoad = schedule.weeks.filter { week ->
            val range = week.startDate..week.startDate.plusDays(6)
            val hasLessons = schedule.lessons.any { it.date in range }
            // Недели, уже загруженные из HTML, тоже перечитываем: расписание
            // могло измениться, а ICS их не обновляет.
            !hasLessons || schedule.checkedWeeks.containsKey(week.id)
        }
        if (weeksToLoad.isEmpty()) return

        _state.update { it.copy(isRefreshing = true) }
        val group = Group(
            facultyCode = selection.facultyCode,
            slug = selection.groupSlug,
            name = selection.groupName,
        )
        try {
            applyLoadResult(
                loadWeeksSchedule(group, weeksToLoad, forceRefresh = true),
            )
        } finally {
            _state.update { it.copy(isRefreshing = false) }
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
                loadingWeekId = null,
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

    private suspend fun loadWeeksSchedule(
        group: Group,
        weeks: List<Week>,
        forceRefresh: Boolean,
    ): ScheduleLoadResult =
        try {
            scheduleRepository.loadWeeks(group, weeks, forceRefresh)
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
    }
}
