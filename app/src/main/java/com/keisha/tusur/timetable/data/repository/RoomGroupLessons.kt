package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonNotes

internal fun Lesson.belongsTo(group: Group): Boolean {
    if (groupLinks.any { it.facultyCode == group.facultyCode && it.slug == group.slug }) return true
    val number = LessonNotes.normalize(group.name)
    return number.isNotEmpty() && groups.any { LessonNotes.normalize(it) == number } &&
        groupLinks.none { LessonNotes.normalize(it.name) == number }
}

/** Идентификатор серии сам по себе повторяется у разных недель и подгрупп. */
internal fun mergeRoomLessons(lessons: List<Lesson>): List<Lesson> =
    lessons.distinct().groupBy { lesson ->
        if (lesson.siteSeriesId == null) lesson
        else lesson.copy(
            rooms = emptyList(),
            teachers = lesson.teachers.sorted(),
            teacherIds = lesson.teacherIds.sorted(),
            groups = lesson.groups.sorted(),
            groupLinks = lesson.groupLinks.sortedWith(compareBy(Group::facultyCode, Group::slug, Group::name)),
            sourceUrl = null,
        )
    }.values.map { matches ->
        val first = matches.minBy { it.sourceUrl.orEmpty() }
        if (matches.size == 1) first
        else first.copy(rooms = matches.flatMap(Lesson::rooms).distinct().sorted())
    }.sortedWith(compareBy(Lesson::date, Lesson::start, Lesson::discipline, Lesson::sourceUrl))
