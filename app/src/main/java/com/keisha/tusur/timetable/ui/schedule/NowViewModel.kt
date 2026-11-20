package com.keisha.tusur.timetable.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.data.repository.ScheduleRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.time.NowSnapshot
import com.keisha.tusur.timetable.domain.time.NowStateCalculator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.time.Clock
import java.time.Duration

internal data class NowUiState(val schedule: GroupSchedule?, val snapshot: NowSnapshot)

@OptIn(ExperimentalCoroutinesApi::class)
internal class NowViewModel(
    scheduleRepository: ScheduleRepository,
    settingsRepository: SettingsRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val timeChanges = MutableStateFlow(0L)
    private val calculator = NowStateCalculator()

    val state = settingsRepository.selection.catch { emit(null) }
        .flatMapLatest { selection ->
            if (selection == null) flowOf(null)
            else scheduleRepository.observe(selection.toGroup()).catch { emit(null) }
        }.combine(timeChanges) { schedule, _ -> schedule }
        .flatMapLatest { schedule ->
            flow {
                while (currentCoroutineContext().isActive) {
                    val now = clock.instant()
                    val snapshot = calculator.calculate(schedule, now)
                    emit(NowUiState(schedule, snapshot))
                    delay(Duration.between(clock.instant(), snapshot.nextUpdate).toMillis().coerceAtLeast(1))
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
            null,
        )

    fun onTimeChanged() = timeChanges.update { it + 1 }
}
