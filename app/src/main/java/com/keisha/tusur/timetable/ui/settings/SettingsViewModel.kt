package com.keisha.tusur.timetable.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.data.repository.CatalogRepository
import com.keisha.tusur.timetable.data.repository.ScheduleRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AppError
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
)

sealed interface SettingsEvent {
    data object CacheCleared : SettingsEvent
    data class CacheClearFailed(val error: AppError) : SettingsEvent
}

class SettingsViewModel(
    private val catalogRepository: CatalogRepository,
    private val scheduleRepository: ScheduleRepository,
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
        settingsRepository.selection.catch { emit(null) },
        local,
    ) { selection, localState ->
        SettingsUiState(
            selection = selection,
            lastUpdated = localState.lastUpdated,
            isLoading = false,
            isClearingCache = localState.isClearingCache,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    init {
        // Время кэша относится к конкретной группе: после смены группы его нужно перечитать.
        viewModelScope.launch {
            settingsRepository.selection.collectLatest { selection ->
                val updatedAt = readLastUpdated(selection?.groupSlug)
                local.update { it.copy(lastUpdated = updatedAt) }
            }
        }
    }

    /** Перечитывает время последнего обновления: экран открывается повторно после расписания. */
    fun refreshLastUpdated() {
        viewModelScope.launch {
            val selection = settingsRepository.selection.first()
            val updatedAt = readLastUpdated(selection?.groupSlug)
            local.update { it.copy(lastUpdated = updatedAt) }
        }
    }

    fun clearCache() {
        if (local.value.isClearingCache) return
        viewModelScope.launch {
            local.update { it.copy(isClearingCache = true) }
            try {
                catalogRepository.clearCache()
                scheduleRepository.clearCache()
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

    private suspend fun readLastUpdated(groupSlug: String?): Instant? {
        if (groupSlug == null) return null
        return try {
            scheduleRepository.lastUpdated(groupSlug)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
