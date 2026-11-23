package com.keisha.tusur.timetable.data.local

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

import java.util.UUID

/** Один экземпляр DataStore на процесс, файл "settings". */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(RoomSourceMigration) },
)

internal object RoomSourceMigration : DataMigration<Preferences> {
    private val legacyKeys = listOf("room_building", "room_slug", "room_number").map(::stringPreferencesKey)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[SettingsKeys.SCHEDULE_SOURCE] == "room" || legacyKeys.any { it in currentData }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (this[SettingsKeys.SCHEDULE_SOURCE] == "room") {
                this[SettingsKeys.SCHEDULE_SOURCE] = "group_from_rooms"
                if (this[SettingsKeys.SCHEDULE_MONITORING_GENERATION] != null) {
                    this[SettingsKeys.SCHEDULE_MONITORING_GENERATION] = UUID.randomUUID().toString()
                }
            }
            legacyKeys.forEach { remove(it) }
        }

    override suspend fun cleanUp() = Unit
}

internal object SettingsKeys {

    val FACULTY_CODE = stringPreferencesKey("faculty_code")
    val FACULTY_NAME = stringPreferencesKey("faculty_name")
    val GROUP_SLUG = stringPreferencesKey("group_slug")
    val GROUP_NAME = stringPreferencesKey("group_name")

    /** "system" | "light" | "dark". Отсутствие ключа равнозначно "system". */
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val COLOR_PALETTE = stringPreferencesKey("color_palette")
    val ANIMATION_MODE = stringPreferencesKey("animation_mode")
    val SCHEDULE_SOURCE = stringPreferencesKey("schedule_source")

    val SCHEMA_VERSION = intPreferencesKey("schema_version")

    /** Время последней проверки обновлений, миллисекунды Unix. */
    val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")

    /** Версия обновления, которую пользователь попросил больше не предлагать. */
    val UPDATE_SKIPPED_VERSION = stringPreferencesKey("update_skipped_version")

    val SCHEDULE_MONITORING_GENERATION = stringPreferencesKey("schedule_monitoring_generation")
    val SCHEDULE_NOTIFICATIONS_BLOCKED = booleanPreferencesKey("schedule_notifications_blocked")

    const val CURRENT_SCHEMA_VERSION = 1
}
