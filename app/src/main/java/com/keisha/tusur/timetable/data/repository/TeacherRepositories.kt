package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.TeacherSchedule
import java.time.Instant

/**
 * Результат загрузки расписания преподавателя. Как и у группы, ошибка не выбрасывается:
 * экран показывает кэш и баннер, если сеть недоступна.
 */
data class TeacherScheduleLoadResult(
    val schedule: TeacherSchedule?,
    val updatedAt: Instant?,
    val isFromCache: Boolean,
    val isOffline: Boolean,
    val error: AppError?,
)

interface TeacherScheduleRepository {

    /** Расписание из кэша без обращения к сети. Ключ — имя из карточки занятия. */
    suspend fun cached(teacherName: String): TeacherSchedule?

    suspend fun lastUpdated(teacherName: String): Instant?

    /**
     * Загружает расписание преподавателя: кэш → при необходимости сеть.
     * Имя из занятия («Жидова Л.А.») разрешается в id через поиск сайта.
     */
    suspend fun load(teacherName: String, forceRefresh: Boolean = false): TeacherScheduleLoadResult

    suspend fun clearCache()
}
