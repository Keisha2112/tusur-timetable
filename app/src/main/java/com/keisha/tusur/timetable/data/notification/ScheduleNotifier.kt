package com.keisha.tusur.timetable.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.keisha.tusur.timetable.MainActivity
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.change.ChangeKind
import com.keisha.tusur.timetable.domain.change.ScheduleChange
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import java.time.LocalDate

data class ScheduleNoticeDestination(
    val facultyCode: String,
    val groupSlug: String,
    val date: LocalDate,
    val source: ScheduleSource = ScheduleSource.GROUP,
    val groupName: String? = null,
) {
    companion object {
        private const val ACTION = "com.keisha.tusur.timetable.OPEN_SCHEDULE_CHANGE"
        private const val FACULTY_CODE = "facultyCode"
        private const val GROUP_SLUG = "groupSlug"
        private const val DATE = "date"
        private const val SOURCE = "source"
        private const val GROUP_NAME = "groupName"

        fun from(intent: Intent?): ScheduleNoticeDestination? {
            if (intent?.action != ACTION) return null
            val faculty = intent.getStringExtra(FACULTY_CODE) ?: return null
            val group = intent.getStringExtra(GROUP_SLUG) ?: return null
            val rawDate = intent.getStringExtra(DATE) ?: return null
            val date = runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: return null
            val source = ScheduleSource.entries.firstOrNull { it.name == intent.getStringExtra(SOURCE) }
                ?: ScheduleSource.GROUP
            return ScheduleNoticeDestination(faculty, group, date, source, intent.getStringExtra(GROUP_NAME))
        }

        internal fun intent(
            context: Context,
            group: Group,
            date: LocalDate,
            noticeId: String,
            source: ScheduleSource = ScheduleSource.GROUP,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION
                data = Uri.Builder().scheme("tusur-timetable").authority("schedule").appendPath(noticeId).build()
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(FACULTY_CODE, group.facultyCode)
                putExtra(GROUP_SLUG, group.slug)
                putExtra(DATE, date.toString())
                putExtra(SOURCE, source.name)
                putExtra(GROUP_NAME, group.name)
            }
    }
}

internal class ScheduleNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL)?.importance !=
                NotificationManager.IMPORTANCE_NONE)
    }

    fun settingsIntent(): Intent {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = manager.getNotificationChannel(CHANNEL)
            return if (manager.areNotificationsEnabled() && channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.schedule_notifications_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    fun show(id: String, group: Group, changes: List<ScheduleChange>, source: ScheduleSource) {
        if (changes.isEmpty() || !canPost()) return
        ensureChannel()
        val content = PendingIntent.getActivity(
            context,
            0,
            ScheduleNoticeDestination.intent(context, group, changes.first().date, id, source),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lines = changes.map { describe(it, source) }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_schedule)
            .setContentTitle(context.getString(R.string.schedule_notice_title, group.name))
            .setContentText(lines.first())
            .setSubText(context.getString(
                if (source == ScheduleSource.GROUP_FROM_ROOMS) R.string.schedule_source_group_from_rooms
                else R.string.schedule_source_group,
            ))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (lines.size == 1) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.first()))
        } else {
            val style = NotificationCompat.InboxStyle()
            lines.take(MAX_LINES).forEach(style::addLine)
            if (lines.size > MAX_LINES) {
                style.setSummaryText(context.getString(R.string.schedule_notice_more, lines.size - MAX_LINES))
            }
            builder.setStyle(style)
        }

        try {
            manager.notify(id, 0, builder.build())
        } catch (_: SecurityException) {
            // Разрешение могли отозвать одновременно с отправкой.
        }
    }

    private fun describe(change: ScheduleChange, source: ScheduleSource): String {
        val before = change.before
        val after = change.after
        val lesson = after ?: before
        val title = lesson?.discipline.orEmpty()
        val date = ScheduleTime.formatDayTitle(change.date)
        val kinds = change.kinds
        if (ChangeKind.SCHEDULE in kinds) return context.getString(R.string.schedule_notice_unspecified_change, date)
        if (ChangeKind.ADDED in kinds) return context.getString(R.string.schedule_notice_added, title, date, time(after))
        if (ChangeKind.REMOVED in kinds) return context.getString(
            if (source == ScheduleSource.GROUP_FROM_ROOMS) R.string.schedule_notice_room_record_removed
            else R.string.schedule_notice_removed, title, date,
        )
        if (ChangeKind.CANCELLED in kinds) return context.getString(R.string.schedule_notice_cancelled, title, date)
        if (ChangeKind.RESTORED in kinds) return context.getString(R.string.schedule_notice_restored, title, date)

        val details = buildList {
            if (ChangeKind.DATE in kinds || ChangeKind.TIME in kinds) {
                val from = if (ChangeKind.DATE in kinds) dateAndTime(before) else time(before)
                val to = if (ChangeKind.DATE in kinds) dateAndTime(after) else time(after)
                add(context.getString(R.string.schedule_notice_move, from, to))
            }
            if (ChangeKind.ROOM in kinds) add(context.getString(
                R.string.schedule_notice_room, display(before?.rooms), display(after?.rooms),
            ))
            if (ChangeKind.TEACHER in kinds) add(context.getString(
                R.string.schedule_notice_teacher, display(before?.teachers), display(after?.teachers),
            ))
            if (ChangeKind.TYPE in kinds) add(context.getString(
                R.string.schedule_notice_type, show(before?.rawType), show(after?.rawType),
            ))
            if (ChangeKind.NOTE in kinds) add(context.getString(
                R.string.schedule_notice_note, show(before?.note), show(after?.note),
            ))
        }
        return context.getString(R.string.schedule_notice_detail, title, date, details.joinToString(" · "))
    }

    private fun time(lesson: Lesson?): String = lesson?.let {
        context.getString(R.string.lesson_time, ScheduleTime.formatTime(it.start), ScheduleTime.formatTime(it.end))
    }.orEmpty()

    private fun dateAndTime(lesson: Lesson?): String =
        lesson?.let { ScheduleTime.formatDayTitle(it.date) + " " + time(it) }.orEmpty()

    private fun show(value: String?): String =
        value?.takeIf(String::isNotBlank) ?: context.getString(R.string.schedule_notice_missing_value)

    private fun display(values: List<String>?): String = show(values?.joinToString(", "))

    private companion object {
        const val CHANNEL = "schedule_changes"
        const val MAX_LINES = 5
    }
}
