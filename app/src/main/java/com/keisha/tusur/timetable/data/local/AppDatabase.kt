package com.keisha.tusur.timetable.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FacultyEntity::class,
        GroupEntity::class,
        ScheduleCacheEntity::class,
        TeacherScheduleCacheEntity::class,
        ScheduleNotificationEntity::class,
        RoomCatalogCacheEntity::class,
        RoomScheduleCacheEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun catalogDao(): CatalogDao

    abstract fun scheduleCacheDao(): ScheduleCacheDao

    abstract fun teacherScheduleCacheDao(): TeacherScheduleCacheDao

    abstract fun scheduleNotificationDao(): ScheduleNotificationDao

    abstract fun roomCacheDao(): RoomCacheDao

    companion object {

        /**
         * v2 — кэш расписания преподавателей. Существующие таблицы не меняются,
         * поэтому миграция сводится к созданию новой таблицы.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `teacher_schedule_cache` (
                        `teacherKey` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `checkedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`teacherKey`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS schedule_notifications (
                        id TEXT NOT NULL PRIMARY KEY,
                        generation TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS room_catalog_cache (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        payload TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS room_schedule_cache (
                        buildingCode TEXT NOT NULL,
                        roomSlug TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        PRIMARY KEY(buildingCode, roomSlug)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tusur-timetable.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
