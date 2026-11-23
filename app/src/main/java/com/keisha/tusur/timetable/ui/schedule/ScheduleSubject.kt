package com.keisha.tusur.timetable.ui.schedule

import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Week

internal data class ScheduleSubject(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    private val groupUrl: String? = null,
    val auditorium: Auditorium? = null,
) {
    fun sourceUrl(week: Week? = null): String =
        auditorium?.let { Urls.auditorium(it, week?.id) } ?: groupUrl ?: Urls.BASE
}

internal fun UserSelection.subject() = ScheduleSubject(
    key = "$facultyCode/$groupSlug",
    title = groupName,
    subtitle = facultyName,
    groupUrl = Urls.group(facultyCode, groupSlug),
)

internal fun Auditorium.subject() = ScheduleSubject(
    key = Urls.auditorium(this),
    title = label,
    auditorium = this,
)
