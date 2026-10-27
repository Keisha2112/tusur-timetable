package com.keisha.tusur.timetable.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.keisha.tusur.timetable.domain.model.Faculty
import com.keisha.tusur.timetable.domain.model.Group

@Entity(tableName = "faculties")
data class FacultyEntity(
    @PrimaryKey val code: String,
    val name: String,
    val updatedAt: Long,
)

@Entity(tableName = "groups", primaryKeys = ["facultyCode", "slug"])
data class GroupEntity(
    val facultyCode: String,
    val slug: String,
    val name: String,
    val course: Int?,
    val updatedAt: Long,
)

/**
 * Расписание группы целиком лежит в JSON: схема базы не зависит от изменений модели.
 * [updatedAt] — время последнего успешного обновления, [checkedAt] — последней проверки
 * (в том числе ответа 304), по нему считается TTL.
 */
@Entity(tableName = "schedule_cache")
data class ScheduleCacheEntity(
    @PrimaryKey val groupSlug: String,
    val payload: String,
    val icsEtag: String?,
    val weeksEtag: String?,
    val updatedAt: Long,
    val checkedAt: Long,
)

fun FacultyEntity.toDomain(): Faculty = Faculty(code = code, name = name)

fun Faculty.toEntity(updatedAt: Long): FacultyEntity =
    FacultyEntity(code = code, name = name, updatedAt = updatedAt)

fun GroupEntity.toDomain(): Group =
    Group(facultyCode = facultyCode, slug = slug, name = name, course = course)

fun Group.toEntity(updatedAt: Long): GroupEntity =
    GroupEntity(
        facultyCode = facultyCode,
        slug = slug,
        name = name,
        course = course,
        updatedAt = updatedAt,
    )
