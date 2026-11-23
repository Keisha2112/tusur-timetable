package com.keisha.tusur.timetable.ui.schedule

import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.ScheduleSource

internal data class ScheduleSubject(
    val key: String,
    val title: String,
    val subtitle: String,
    private val groupUrl: String,
    val fromRooms: Boolean,
) {
    fun sourceUrl(lesson: Lesson): String = lesson.sourceUrl ?: groupUrl
}

internal fun UserSelection.subject(source: ScheduleSource) = ScheduleSubject(
    key = "$source:$facultyCode/$groupSlug",
    title = groupName,
    subtitle = facultyName,
    groupUrl = Urls.group(facultyCode, groupSlug),
    fromRooms = source == ScheduleSource.GROUP_FROM_ROOMS,
)
