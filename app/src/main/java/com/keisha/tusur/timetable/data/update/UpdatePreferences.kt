package com.keisha.tusur.timetable.data.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.keisha.tusur.timetable.data.local.SettingsKeys
import com.keisha.tusur.timetable.data.local.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

/**
 * Настройки проверки обновлений в общем DataStore приложения:
 * время последней проверки и пропущенная пользователем версия.
 */
class UpdatePreferences(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val lastCheckAt: Flow<Instant?> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { preferences ->
            preferences[SettingsKeys.UPDATE_LAST_CHECK_AT]?.let(Instant::ofEpochMilli)
        }
        .distinctUntilChanged()

    val skippedVersion: Flow<String?> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { preferences ->
            // Пустая строка равнозначна отсутствию ключа.
            preferences[SettingsKeys.UPDATE_SKIPPED_VERSION]?.takeIf { it.isNotBlank() }
        }
        .distinctUntilChanged()

    suspend fun setLastCheckAt(at: Instant) {
        dataStore.edit { preferences ->
            preferences[SettingsKeys.UPDATE_LAST_CHECK_AT] = at.toEpochMilli()
        }
    }

    suspend fun setSkippedVersion(version: String?) {
        dataStore.edit { preferences ->
            if (version.isNullOrBlank()) {
                preferences.remove(SettingsKeys.UPDATE_SKIPPED_VERSION)
            } else {
                preferences[SettingsKeys.UPDATE_SKIPPED_VERSION] = version
            }
        }
    }
}
