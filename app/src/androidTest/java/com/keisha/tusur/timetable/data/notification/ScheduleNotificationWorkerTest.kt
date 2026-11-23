package com.keisha.tusur.timetable.data.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.keisha.tusur.timetable.App
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.change.ChangeKind
import com.keisha.tusur.timetable.domain.change.ScheduleChange
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduleNotificationWorkerTest {
    @Test
    fun disabledPreferenceLeavesNoPeriodicCheck() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as App).container
        container.settingsRepository.setScheduleNotifications(false)
        container.scheduleNotifications.reconcile()
        val worker = TestListenableWorkerBuilder<ScheduleNotificationWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val jobs = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ScheduleNotificationScheduler.WORK_NAME).get(10, TimeUnit.SECONDS)
        assertTrue(jobs.all { it.state.isFinished })
    }

    @Test
    fun manifestAndChannelAllowPostingAnActualScheduleNotification() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val group = Group("fb", "714-2", "714-2")
        val before = Lesson(
            id = "1",
            date = LocalDate.of(2026, 11, 23),
            start = LocalTime.of(10, 40),
            end = LocalTime.of(12, 15),
            discipline = "Численные методы",
            type = LessonType.PRACTICE,
            rawType = "Практика",
            rooms = listOf("406"),
        )
        val change = ScheduleChange(setOf(ChangeKind.ROOM), before, before.copy(rooms = listOf("312")), before.date)
        val id = UUID.randomUUID().toString()
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        try {
            ScheduleNotifier(context).show(id, group, listOf(change), ScheduleSource.GROUP)
            val notification = withTimeout(5_000) {
                var posted = manager.activeNotifications.firstOrNull { it.tag == id }
                while (posted == null) {
                    delay(40)
                    posted = manager.activeNotifications.firstOrNull { it.tag == id }
                }
                posted.notification
            }
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("406 → 312"))
            assertTrue(notification.contentIntent != null)
        } finally {
            manager.cancel(id, 0)
        }
    }

    @Test
    fun enabledPreferenceSchedulesOneWorkRequestAndDisablingCancelsIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (context.applicationContext as App).container
        val settings = container.settingsRepository
        val previous = settings.selection.first()
        val previousSource = settings.scheduleSource.first()
        val manager = WorkManager.getInstance(context)
        fun activeWork() = manager.getWorkInfosForUniqueWork(ScheduleNotificationScheduler.WORK_NAME)
            .get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }

        try {
            settings.saveSelection(UserSelection("fb", "Факультет безопасности", "714-2", "714-2"))
            settings.setScheduleSource(ScheduleSource.GROUP_FROM_ROOMS)
            settings.setScheduleNotifications(true)
            container.scheduleNotifications.reconcile()
            container.scheduleNotifications.reconcile()
            val work = activeWork().single()
            assertEquals(TimeUnit.HOURS.toMillis(6), work.periodicityInfo?.repeatIntervalMillis)
            assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        } finally {
            settings.setScheduleNotifications(false)
            container.scheduleNotifications.reconcile()
            assertTrue(activeWork().isEmpty())
            settings.setScheduleSource(previousSource)
            if (previous == null) settings.clearSelection() else settings.saveSelection(previous)
        }
    }
}
