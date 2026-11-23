package com.keisha.tusur.timetable.data.notification

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec
import com.keisha.tusur.timetable.data.local.ScheduleNotificationDao
import com.keisha.tusur.timetable.data.repository.ScheduleLoadResult
import com.keisha.tusur.timetable.data.repository.ScheduleMonitoring
import com.keisha.tusur.timetable.data.repository.ScheduleRefreshReason
import com.keisha.tusur.timetable.data.repository.ScheduleRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.domain.change.ScheduleChange
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

internal class ScheduleNotificationScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val repositoryFor: (ScheduleSource) -> ScheduleRepository,
    private val queue: ScheduleNotificationDao,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val notifier = ScheduleNotifier(context)
    private val workManager by lazy { WorkManager.getInstance(context) }
    private val mutex = Mutex()
    private var workSource: ScheduleSource? = null
    private var workReconciled = false
    private val available = MutableStateFlow(notifier.canPost())
    val deliveryAvailable = available.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch { settings.monitoring.collect { reconcile() } }
    }

    fun settingsIntent(): Intent = notifier.settingsIntent()

    suspend fun reconcile() = safely {
        mutex.withLock {
            val monitoring = checkPermission()
            val source = monitoring.source.takeIf { monitoring.activeGeneration() != null }
            if (!workReconciled || source != workSource) {
                if (source != null) {
                    workManager.enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        PeriodicWorkRequestBuilder<ScheduleNotificationWorker>(
                            if (source == ScheduleSource.GROUP_FROM_ROOMS) 6 else 2,
                            TimeUnit.HOURS, 30, TimeUnit.MINUTES,
                        )
                            .setConstraints(
                                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                                    .setRequiresBatteryNotLow(source == ScheduleSource.GROUP).build(),
                            )
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                            .build(),
                    )
                } else {
                    workManager.cancelUniqueWork(WORK_NAME)
                    queue.clear()
                }
                workSource = source
                workReconciled = true
            }
            drain(monitoring)
        }
    }

    suspend fun refreshManually(group: Group, selectedWeekId: Int?, source: ScheduleSource): ScheduleLoadResult {
        val monitoring = checkPermission()
        val generation = monitoring.activeGeneration().takeIf {
            val selected = monitoring.selection
            monitoring.source == source && selected?.facultyCode == group.facultyCode && selected.groupSlug == group.slug
        }
        val result = repositoryFor(source).refresh(group, ScheduleRefreshReason.MANUAL, selectedWeekId, generation)
        reconcile()
        return result
    }

    suspend fun refreshInBackground(): ScheduleLoadResult? {
        val monitoring = checkPermission()
        val generation = monitoring.activeGeneration() ?: return null
        val group = monitoring.selection?.toGroup() ?: return null
        val result = repositoryFor(monitoring.source).refresh(
            group, ScheduleRefreshReason.BACKGROUND, monitoringGeneration = generation,
        )
        reconcile()
        return result
    }

    private suspend fun checkPermission(): ScheduleMonitoring {
        var monitoring = settings.monitoring.first()
        if (monitoring.generation != null) notifier.ensureChannel()
        val allowed = notifier.canPost()
        available.value = allowed
        if (monitoring.generation != null && monitoring.blocked == allowed) {
            settings.setScheduleNotificationsBlocked(!allowed)
            monitoring = settings.monitoring.first()
        }
        return monitoring
    }

    /** Погашение записи до notify исключает повтор при перезапуске, даже после аварийного выхода. */
    private suspend fun drain(monitoring: ScheduleMonitoring) {
        val generation = monitoring.activeGeneration() ?: return
        val group = monitoring.selection?.toGroup() ?: return
        val now = clock.instant()
        queue.discardOther(generation, now.minus(MAX_PENDING_AGE).toEpochMilli())
        for (item in queue.pending(generation)) {
            if (settings.monitoring.first().activeGeneration() != generation || !notifier.canPost()) return
            val changes = runCatching {
                ScheduleCacheCodec.json.decodeFromString<List<ScheduleChange>>(item.payload)
            }.getOrDefault(emptyList()).filter { it.isRelevantAt(clock.instant()) }
            if (queue.claim(item.id) == 1 &&
                settings.monitoring.first().activeGeneration() == generation
            ) notifier.show(item.id, group, changes, monitoring.source)
        }
    }

    private fun ScheduleMonitoring.activeGeneration(): String? =
        generation.takeIf { selection != null && !blocked && available.value }

    private suspend fun safely(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ScheduleNotifications", "Не удалось обновить состояние уведомлений", e)
        }
    }

    companion object {
        const val WORK_NAME = "tusur-schedule-change-check"
        private val MAX_PENDING_AGE = Duration.ofHours(24)
    }
}
