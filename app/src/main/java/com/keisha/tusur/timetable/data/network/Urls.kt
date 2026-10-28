package com.keisha.tusur.timetable.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Адреса публичного сайта расписания. Собираем через HttpUrl,
 * чтобы коды факультетов и slug групп корректно экранировались.
 */
object Urls {

    const val BASE = "https://timetable.tusur.ru"

    private val base: HttpUrl = BASE.toHttpUrl()

    fun faculties(): String = base.newBuilder()
        .addPathSegment("faculties")
        .build()
        .toString()

    fun faculty(code: String): String = base.newBuilder()
        .addPathSegment("faculties")
        .addPathSegment(code)
        .build()
        .toString()

    fun group(facultyCode: String, slug: String): String = base.newBuilder()
        .addPathSegment("faculties")
        .addPathSegment(facultyCode)
        .addPathSegment("groups")
        .addPathSegment(slug)
        .build()
        .toString()

    fun groupIcs(facultyCode: String, slug: String): String = base.newBuilder()
        .addPathSegment("faculties")
        .addPathSegment(facultyCode)
        .addPathSegment("groups")
        .addPathSegment("$slug.ics")
        .build()
        .toString()

    fun teacher(id: String): String = base.newBuilder()
        .addPathSegment("teachers")
        .addPathSegment(id)
        .build()
        .toString()

    fun teacherIcs(id: String): String = base.newBuilder()
        .addPathSegment("teachers")
        .addPathSegment("$id.ics")
        .build()
        .toString()

    /** Общий поиск сайта: преподаватели, группы, аудитории. */
    fun search(query: String): String = base.newBuilder()
        .addPathSegment("searches")
        .addPathSegment("common_search")
        .addQueryParameter("search[common]", query)
        .build()
        .toString()
}
