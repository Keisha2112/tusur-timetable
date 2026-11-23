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
        RoomWeekCacheEntity::class,
        RoomWeekStateEntity::class,
        RoomNotificationBaselineEntity::class,
    ],
    version = 5,
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

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE room_schedule_cache")
                db.execSQL(
                    """
                    CREATE TABLE room_week_cache (
                        weekId INTEGER NOT NULL,
                        startDate TEXT NOT NULL,
                        roomKey TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        PRIMARY KEY(weekId, startDate, roomKey)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE room_week_state (
                        weekId INTEGER NOT NULL,
                        startDate TEXT NOT NULL,
                        checked INTEGER NOT NULL,
                        total INTEGER NOT NULL,
                        snapshot BLOB,
                        partial BLOB,
                        PRIMARY KEY(weekId, startDate)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE room_notification_baselines (
                        generation TEXT NOT NULL PRIMARY KEY,
                        payload TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
