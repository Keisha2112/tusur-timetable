package com.keisha.tusur.timetable.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.keisha.tusur.timetable.data.local.SettingsKeys
import com.keisha.tusur.timetable.data.local.settingsDataStore
import com.keisha.tusur.timetable.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

/**
 * Настройки приложения в DataStore Preferences, файл "settings":
 * выбор факультета и группы, режим оформления.
 *
 * DataStore должен быть один на процесс, поэтому репозиторий принимает Context,
 * а экземпляр берёт из делегата [settingsDataStore].
 */
class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val dataStore = context.applicationContext.settingsDataStore

    private val preferences = dataStore.data
        .catch { e ->
            // Нечитаемый файл настроек равнозначен первому запуску, а не падению приложения.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }

    override val selection: Flow<UserSelection?> = preferences
        .map(::readSelection)
        .distinctUntilChanged()

    override suspend fun saveSelection(selection: UserSelection) {
        dataStore.edit { preferences ->
            if (preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] != null &&
                (preferences[SettingsKeys.FACULTY_CODE] != selection.facultyCode ||
                    preferences[SettingsKeys.GROUP_SLUG] != selection.groupSlug)
            ) {
                preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] = UUID.randomUUID().toString()
            }
            preferences[SettingsKeys.FACULTY_CODE] = selection.facultyCode
            preferences[SettingsKeys.FACULTY_NAME] = selection.facultyName
            preferences[SettingsKeys.GROUP_SLUG] = selection.groupSlug
            preferences[SettingsKeys.GROUP_NAME] = selection.groupName
            preferences[SettingsKeys.SCHEMA_VERSION] = SettingsKeys.CURRENT_SCHEMA_VERSION
        }
    }

    override suspend fun clearSelection() {
        dataStore.edit { preferences ->
            preferences.remove(SettingsKeys.FACULTY_CODE)
            preferences.remove(SettingsKeys.FACULTY_NAME)
            preferences.remove(SettingsKeys.GROUP_SLUG)
            preferences.remove(SettingsKeys.GROUP_NAME)
            if (preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] != null) {
                preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] = UUID.randomUUID().toString()
            }
        }
    }

    override val themeMode: Flow<ThemeMode> = preferences
        .map { preferences ->
            // Ключа может не быть: настройки старых версий читаем как системную тему.
            themeModeFrom(preferences[SettingsKeys.THEME_MODE])
        }
        .distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[SettingsKeys.THEME_MODE] = mode.toPreferenceValue()
        }
    }

    override val monitoring: Flow<ScheduleMonitoring> = preferences.map {
        ScheduleMonitoring(
            selection = readSelection(it),
            generation = it[SettingsKeys.SCHEDULE_MONITORING_GENERATION],
            blocked = it[SettingsKeys.SCHEDULE_NOTIFICATIONS_BLOCKED] == true,
        )
    }.distinctUntilChanged()

    override suspend fun setScheduleNotifications(enabled: Boolean) {
        dataStore.edit { preferences ->
            if (enabled) {
                if (preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] == null) {
                    preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] = UUID.randomUUID().toString()
                }
            } else {
                preferences.remove(SettingsKeys.SCHEDULE_MONITORING_GENERATION)
                preferences.remove(SettingsKeys.SCHEDULE_NOTIFICATIONS_BLOCKED)
            }
        }
    }

    override suspend fun setScheduleNotificationsBlocked(blocked: Boolean) {
        dataStore.edit { preferences ->
            val enabled = preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] != null
            val wasBlocked = preferences[SettingsKeys.SCHEDULE_NOTIFICATIONS_BLOCKED] == true
            if (enabled && wasBlocked != blocked) {
                preferences[SettingsKeys.SCHEDULE_NOTIFICATIONS_BLOCKED] = blocked
                preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] = UUID.randomUUID().toString()
            }
        }
    }

    private fun readSelection(preferences: Preferences): UserSelection? {
        val schemaVersion = preferences[SettingsKeys.SCHEMA_VERSION] ?: SettingsKeys.CURRENT_SCHEMA_VERSION
        val facultyCode = preferences[SettingsKeys.FACULTY_CODE]
        val groupSlug = preferences[SettingsKeys.GROUP_SLUG]
        if (schemaVersion != SettingsKeys.CURRENT_SCHEMA_VERSION ||
            facultyCode.isNullOrBlank() || groupSlug.isNullOrBlank()
        ) return null
        return UserSelection(
            facultyCode = facultyCode,
            facultyName = preferences[SettingsKeys.FACULTY_NAME].orEmpty(),
            groupSlug = groupSlug,
            groupName = preferences[SettingsKeys.GROUP_NAME].orEmpty(),
        )
    }
}

private fun themeModeFrom(raw: String?): ThemeMode = when (raw) {
    "light" -> ThemeMode.LIGHT
    "dark" -> ThemeMode.DARK
    else -> ThemeMode.SYSTEM
}

private fun ThemeMode.toPreferenceValue(): String = when (this) {
    ThemeMode.SYSTEM -> "system"
    ThemeMode.LIGHT -> "light"
    ThemeMode.DARK -> "dark"
}
