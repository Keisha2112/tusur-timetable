package com.keisha.tusur.timetable.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.data.repository.CatalogRepository
import com.keisha.tusur.timetable.data.repository.RoomScheduleRepository
import com.keisha.tusur.timetable.data.repository.ScheduleMonitoring
import com.keisha.tusur.timetable.data.repository.ScheduleRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.data.repository.TeacherScheduleRepository
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AnimationMode
import com.keisha.tusur.timetable.domain.model.Appearance
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.ColorPalette
import com.keisha.tusur.timetable.domain.model.SchedulePreference
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class SettingsUiState(
    val selection: UserSelection? = null,
    val lastUpdated: Instant? = null,
    val isLoading: Boolean = true,
    val isClearingCache: Boolean = false,
    val appearance: Appearance = Appearance(),
    val schedulePreference: SchedulePreference = SchedulePreference(),
    val notificationsEnabled: Boolean = false,
)

sealed interface SettingsEvent {
    data object CacheCleared : SettingsEvent
    data class CacheClearFailed(val error: AppError) : SettingsEvent
    data object NotificationSettingFailed : SettingsEvent
    data object PreferenceSaveFailed : SettingsEvent
    data object ScheduleSourceChanged : SettingsEvent
}

internal class SettingsViewModel(
    private val catalogRepository: CatalogRepository,
    private val scheduleRepository: ScheduleRepository,
    private val teacherScheduleRepository: TeacherScheduleRepository,
    private val roomRepository: RoomScheduleRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private data class LocalState(
        val lastUpdated: Instant? = null,
        val isClearingCache: Boolean = false,
    )

    private val local = MutableStateFlow(LocalState())
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.monitoring.catch { emit(ScheduleMonitoring(null, null)) },
        settingsRepository.appearance.catch { emit(Appearance()) },
        settingsRepository.schedulePreference.catch { emit(SchedulePreference()) },
        local,
    ) { monitoring, appearance, preference, localState ->
        SettingsUiState(
            selection = monitoring.selection,
            lastUpdated = localState.lastUpdated,
            isLoading = false,
            isClearingCache = localState.isClearingCache,
            appearance = appearance,
            schedulePreference = preference,
            notificationsEnabled = monitoring.generation != null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    init {
        // Время кэша относится к конкретной группе: после смены группы его нужно перечитать.
        viewModelScope.launch {
            combine(settingsRepository.selection, settingsRepository.schedulePreference, ::Pair)
                .collectLatest { (selection, preference) ->
                val updatedAt = readLastUpdated(selection, preference)
                local.update { it.copy(lastUpdated = updatedAt) }
            }
        }
    }

    /** Перечитывает время последнего обновления: экран открывается повторно после расписания. */
    fun refreshLastUpdated() {
        viewModelScope.launch {
            val updatedAt = readLastUpdated(settingsRepository.selection.first(), settingsRepository.schedulePreference.first())
            local.update { it.copy(lastUpdated = updatedAt) }
        }
    }

    fun setThemeMode(mode: ThemeMode) = savePreference { settingsRepository.setThemeMode(mode) }

    fun setPalette(palette: ColorPalette) = savePreference { settingsRepository.setPalette(palette) }

    fun setAnimationMode(mode: AnimationMode) = savePreference { settingsRepository.setAnimationMode(mode) }

    fun setScheduleSource(source: ScheduleSource) = savePreference {
        settingsRepository.setScheduleSource(source)
        _events.send(SettingsEvent.ScheduleSourceChanged)
    }

    fun selectAuditorium(auditorium: Auditorium) = savePreference {
        settingsRepository.selectAuditorium(auditorium)
        _events.send(SettingsEvent.ScheduleSourceChanged)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setScheduleNotifications(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.send(SettingsEvent.NotificationSettingFailed)
            }
        }
    }

    fun clearCache() {
        if (local.value.isClearingCache) return
        viewModelScope.launch {
            local.update { it.copy(isClearingCache = true) }
            try {
                catalogRepository.clearCache()
                scheduleRepository.clearCache()
                teacherScheduleRepository.clearCache()
                roomRepository.clearCache()
                // Кэша больше нет — «последнее обновление» тоже сбрасывается.
                local.update { it.copy(lastUpdated = null) }
                _events.send(SettingsEvent.CacheCleared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError) {
                _events.send(SettingsEvent.CacheClearFailed(e))
            } catch (e: Exception) {
                _events.send(SettingsEvent.CacheClearFailed(AppError.Unknown(e)))
            } finally {
                local.update { it.copy(isClearingCache = false) }
            }
        }
    }

    private suspend fun readLastUpdated(selection: UserSelection?, preference: SchedulePreference): Instant? {
        return try {
            if (preference.source == ScheduleSource.ROOM) preference.auditorium?.let { roomRepository.lastUpdated(it) }
            else selection?.let { scheduleRepository.lastUpdated(it.groupSlug) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun savePreference(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.send(SettingsEvent.PreferenceSaveFailed)
            }
        }
    }
}
