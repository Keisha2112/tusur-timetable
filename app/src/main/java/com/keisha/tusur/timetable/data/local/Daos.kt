package com.keisha.tusur.timetable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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
