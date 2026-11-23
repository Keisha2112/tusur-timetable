package com.keisha.tusur.timetable.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.data.repository.RoomScheduleRepository
import com.keisha.tusur.timetable.data.repository.ScheduleRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.ScheduleEntries
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.NowSnapshot
import com.keisha.tusur.timetable.domain.time.NowStateCalculator
import com.keisha.tusur.timetable.domain.time.ScheduleTime
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class NowSchedule(
    val subject: ScheduleSubject,
    val entries: ScheduleEntries?,
    val fetchedAt: Instant? = null,
    val updatedWeeks: Map<Int, Instant> = emptyMap(),
    val staleWeeks: Set<Int> = emptySet(),
)

internal data class NowUiState(val schedule: NowSchedule?, val snapshot: NowSnapshot) {
    fun nextRoomWeek(): Week? {
        val entries = schedule?.entries ?: return null
        val start = ScheduleTime.weekStart(snapshot.date)
        val nextWeek = entries.weeks.firstOrNull { it.startDate > start } ?: return null
        val nextLesson = snapshot.upcoming?.lessons?.firstOrNull()?.lesson
        return nextWeek.takeUnless {
            it.id in entries.publications || nextLesson != null && nextLesson.date < it.startDate
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class NowViewModel(
    scheduleRepository: ScheduleRepository,
    settingsRepository: SettingsRepository,
    private val roomRepository: RoomScheduleRepository,
    private val auditorium: Auditorium?,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val timeChanges = MutableStateFlow(0L)
    private val calculator = NowStateCalculator()

    private val schedules = if (auditorium != null) {
        val subject = auditorium.subject()
        roomRepository.observe(auditorium).catch { emit(null) }.map { schedule ->
            NowSchedule(subject, schedule?.entries, updatedWeeks = schedule?.updatedWeeks.orEmpty(),
                staleWeeks = schedule?.staleWeeks.orEmpty())
        }
    } else {
        settingsRepository.selection.catch { emit(null) }.flatMapLatest { selection ->
            if (selection == null) flowOf(null)
            else scheduleRepository.observe(selection.toGroup()).catch { emit(null) }.map { schedule ->
                NowSchedule(selection.subject(), schedule?.entries(), schedule?.fetchedAt,
                    staleWeeks = schedule?.staleWeeks.orEmpty())
            }
        }
    }

    val state = schedules.combine(timeChanges) { schedule, _ -> schedule }
        .flatMapLatest { schedule ->
            flow {
                while (currentCoroutineContext().isActive) {
                    val now = clock.instant()
                    val snapshot = calculator.calculateEntries(schedule?.entries, now)
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

    suspend fun loadRoom(week: Week? = null, forceRefresh: Boolean = false): AppError? =
        auditorium?.let { roomRepository.load(it, week, forceRefresh) }
}
