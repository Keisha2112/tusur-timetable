package com.keisha.tusur.timetable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Кэш каталога. Замена списка идёт транзакцией: либо старая версия целиком,
 * либо новая, без промежуточного пустого состояния.
 */
@Dao
abstract class CatalogDao {

    @Query("SELECT * FROM faculties ORDER BY name")
    abstract suspend fun faculties(): List<FacultyEntity>

    @Query("SELECT * FROM faculties WHERE code = :code")
    abstract suspend fun faculty(code: String): FacultyEntity?

    @Query("SELECT * FROM `groups` WHERE facultyCode = :facultyCode ORDER BY (course IS NULL), course, name")
    abstract suspend fun groups(facultyCode: String): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFaculties(items: List<FacultyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertGroups(items: List<GroupEntity>)

    @Query("DELETE FROM faculties")
    abstract suspend fun clearFaculties()

    @Query("DELETE FROM `groups` WHERE facultyCode = :facultyCode")
    abstract suspend fun clearGroups(facultyCode: String)

    @Query("DELETE FROM `groups`")
    abstract suspend fun clearAllGroups()

    @Transaction
    open suspend fun replaceFaculties(items: List<FacultyEntity>) {
        clearFaculties()
        upsertFaculties(items)
    }

    @Transaction
    open suspend fun replaceGroups(facultyCode: String, items: List<GroupEntity>) {
        clearGroups(facultyCode)
        upsertGroups(items)
    }

    @Transaction
    open suspend fun clearCatalog() {
        clearFaculties()
        clearAllGroups()
    }
}

@Dao
interface ScheduleCacheDao {

    @Query("SELECT * FROM schedule_cache WHERE groupSlug = :groupSlug")
    suspend fun get(groupSlug: String): ScheduleCacheEntity?

    @Query("SELECT * FROM schedule_cache WHERE groupSlug = :groupSlug")
    fun observe(groupSlug: String): Flow<ScheduleCacheEntity?>

    @Query("SELECT updatedAt FROM schedule_cache WHERE groupSlug = :groupSlug")
    suspend fun updatedAt(groupSlug: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduleCacheEntity)

    @Query("DELETE FROM schedule_cache WHERE groupSlug = :groupSlug")
    suspend fun delete(groupSlug: String)

    @Query("DELETE FROM schedule_cache")
    suspend fun clear()

    @Query("DELETE FROM schedule_cache")
    suspend fun deleteAll()
}

@Dao
interface ScheduleNotificationDao {
    @Insert
    suspend fun insert(entity: ScheduleNotificationEntity)

    @Query("SELECT * FROM schedule_notifications WHERE generation = :generation ORDER BY createdAt, id")
    suspend fun pending(generation: String): List<ScheduleNotificationEntity>

    @Query("DELETE FROM schedule_notifications WHERE id = :id")
    suspend fun claim(id: String): Int

    @Query("DELETE FROM schedule_notifications WHERE generation != :generation OR createdAt < :oldest")
    suspend fun discardOther(generation: String, oldest: Long)

    @Query("DELETE FROM schedule_notifications")
    suspend fun clear()
}

@Dao
interface TeacherScheduleCacheDao {

    @Query("SELECT * FROM teacher_schedule_cache WHERE teacherKey = :teacherKey")
    suspend fun get(teacherKey: String): TeacherScheduleCacheEntity?

    @Query("SELECT updatedAt FROM teacher_schedule_cache WHERE teacherKey = :teacherKey")
    suspend fun updatedAt(teacherKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TeacherScheduleCacheEntity)

    @Query("DELETE FROM teacher_schedule_cache WHERE teacherKey = :teacherKey")
    suspend fun delete(teacherKey: String)

    @Query("DELETE FROM teacher_schedule_cache")
    suspend fun deleteAll()
}
