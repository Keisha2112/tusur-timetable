package com.keisha.tusur.timetable.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FacultyEntity::class, GroupEntity::class, ScheduleCacheEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun catalogDao(): CatalogDao

    abstract fun scheduleCacheDao(): ScheduleCacheDao

    companion object {
        /**
         * Схема v1, поэтому миграций и fallbackToDestructiveMigration нет:
         * при несовпадении версии лучше упасть на разработке, чем молча стереть кэш.
         */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tusur-timetable.db",
            ).build()
    }
}
