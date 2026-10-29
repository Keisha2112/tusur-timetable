package com.keisha.tusur.timetable.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/** Один экземпляр DataStore на процесс, файл "settings". */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

internal object SettingsKeys {

    val FACULTY_CODE = stringPreferencesKey("faculty_code")
    val FACULTY_NAME = stringPreferencesKey("faculty_name")
    val GROUP_SLUG = stringPreferencesKey("group_slug")
    val GROUP_NAME = stringPreferencesKey("group_name")

    /** "system" | "light" | "dark". Отсутствие ключа равнозначно "system". */
    val THEME_MODE = stringPreferencesKey("theme_mode")

    val SCHEMA_VERSION = intPreferencesKey("schema_version")

    /** Время последней проверки обновлений, миллисекунды Unix. */
    val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")

    /** Версия обновления, которую пользователь попросил больше не предлагать. */
    val UPDATE_SKIPPED_VERSION = stringPreferencesKey("update_skipped_version")

    const val CURRENT_SCHEMA_VERSION = 1
}
