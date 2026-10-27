package com.keisha.tusur.timetable.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/** Один экземпляр DataStore на процесс, файл "settings". */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

internal object SettingsKeys {

    val FACULTY_CODE = stringPreferencesKey("faculty_code")
    val FACULTY_NAME = stringPreferencesKey("faculty_name")
    val GROUP_SLUG = stringPreferencesKey("group_slug")
    val GROUP_NAME = stringPreferencesKey("group_name")
    val SCHEMA_VERSION = intPreferencesKey("schema_version")

    const val CURRENT_SCHEMA_VERSION = 1
}
