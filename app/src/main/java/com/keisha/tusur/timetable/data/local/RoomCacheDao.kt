package com.keisha.tusur.timetable.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "room_catalog_cache")
data class RoomCatalogCacheEntity(
    @PrimaryKey val key: String,
    val payload: String,
    val updatedAt: Long,
)

@Entity(tableName = "room_schedule_cache", primaryKeys = ["buildingCode", "roomSlug"])
data class RoomScheduleCacheEntity(
    val buildingCode: String,
    val roomSlug: String,
    val payload: String,
)

@Dao
abstract class RoomCacheDao {
    @Query("SELECT * FROM room_catalog_cache WHERE `key` = :key")
    abstract suspend fun catalog(key: String): RoomCatalogCacheEntity?

    @Upsert
    abstract suspend fun saveCatalog(entity: RoomCatalogCacheEntity)

    @Query("SELECT * FROM room_schedule_cache WHERE buildingCode = :building AND roomSlug = :slug")
    abstract suspend fun schedule(building: String, slug: String): RoomScheduleCacheEntity?

    @Query("SELECT * FROM room_schedule_cache WHERE buildingCode = :building AND roomSlug = :slug")
    abstract fun observeSchedule(building: String, slug: String): Flow<RoomScheduleCacheEntity?>

    @Upsert
    abstract suspend fun saveSchedule(entity: RoomScheduleCacheEntity)

    @Query("DELETE FROM room_catalog_cache")
    protected abstract suspend fun clearCatalog()

    @Query("DELETE FROM room_schedule_cache")
    protected abstract suspend fun clearSchedules()

    @Transaction
    open suspend fun clear() {
        clearCatalog()
        clearSchedules()
    }
}
