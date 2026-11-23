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

@Entity(tableName = "room_week_cache", primaryKeys = ["weekId", "startDate", "roomKey"])
data class RoomWeekCacheEntity(
    val weekId: Int,
    val startDate: String,
    val roomKey: String,
    val payload: String,
)

@Entity(tableName = "room_week_state", primaryKeys = ["weekId", "startDate"])
data class RoomWeekStateEntity(
    val weekId: Int,
    val startDate: String,
    val checked: Int,
    val total: Int,
    val snapshot: ByteArray?,
    val partial: ByteArray?,
)

@Entity(tableName = "room_notification_baselines")
data class RoomNotificationBaselineEntity(
    @PrimaryKey val generation: String,
    val payload: String,
    val updatedAt: Long,
)

@Dao
abstract class RoomCacheDao {
    @Query("SELECT * FROM room_catalog_cache WHERE `key` = :key")
    abstract suspend fun catalog(key: String): RoomCatalogCacheEntity?

    @Query("SELECT * FROM room_catalog_cache WHERE `key` = :key")
    abstract fun observeCatalog(key: String): Flow<RoomCatalogCacheEntity?>

    @Upsert
    abstract suspend fun saveCatalog(entity: RoomCatalogCacheEntity)

    @Query("SELECT * FROM room_week_cache WHERE weekId = :weekId AND startDate = :startDate")
    abstract suspend fun pages(weekId: Int, startDate: String): List<RoomWeekCacheEntity>

    @Upsert
    abstract suspend fun savePage(entity: RoomWeekCacheEntity)

    @Query("SELECT * FROM room_week_state")
    abstract suspend fun weeks(): List<RoomWeekStateEntity>

    @Query("SELECT * FROM room_week_state")
    abstract fun observeWeeks(): Flow<List<RoomWeekStateEntity>>

    @Query("SELECT * FROM room_week_state WHERE weekId = :weekId AND startDate = :startDate")
    abstract suspend fun week(weekId: Int, startDate: String): RoomWeekStateEntity?

    @Upsert
    abstract suspend fun saveWeek(entity: RoomWeekStateEntity)

    @Query("SELECT * FROM room_notification_baselines WHERE generation = :generation")
    abstract suspend fun baseline(generation: String): RoomNotificationBaselineEntity?

    @Upsert
    abstract suspend fun saveBaseline(entity: RoomNotificationBaselineEntity)

    @Query("DELETE FROM room_notification_baselines WHERE updatedAt < :oldest")
    abstract suspend fun pruneBaselines(oldest: Long)

    @Query("DELETE FROM room_catalog_cache")
    protected abstract suspend fun clearCatalog()

    @Query("DELETE FROM room_week_cache")
    protected abstract suspend fun clearPages()

    @Query("DELETE FROM room_week_state")
    protected abstract suspend fun clearWeeks()

    @Query("DELETE FROM room_notification_baselines")
    protected abstract suspend fun clearBaselines()

    @Transaction
    open suspend fun clear() {
        clearCatalog()
        clearPages()
        clearWeeks()
        clearBaselines()
    }
}
