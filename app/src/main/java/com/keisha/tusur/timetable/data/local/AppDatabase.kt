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
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun catalogDao(): CatalogDao

    abstract fun scheduleCacheDao(): ScheduleCacheDao

    abstract fun teacherScheduleCacheDao(): TeacherScheduleCacheDao

    abstract fun scheduleNotificationDao(): ScheduleNotificationDao

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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tusur-timetable.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
