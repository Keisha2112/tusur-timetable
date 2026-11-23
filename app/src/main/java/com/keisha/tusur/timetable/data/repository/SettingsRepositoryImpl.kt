package com.keisha.tusur.timetable.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.keisha.tusur.timetable.data.local.SettingsKeys
import com.keisha.tusur.timetable.data.local.settingsDataStore
import com.keisha.tusur.timetable.domain.model.AnimationMode
import com.keisha.tusur.timetable.domain.model.Appearance
import com.keisha.tusur.timetable.domain.model.ColorPalette
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Настройки приложения в DataStore Preferences, файл "settings":
 * выбор расписания и оформление.
 *
 * DataStore должен быть один на процесс, поэтому репозиторий принимает Context,
 * а экземпляр берёт из делегата [settingsDataStore].
 */
class SettingsRepositoryImpl internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

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

    override val scheduleSource: Flow<ScheduleSource> = preferences
        .map(::readSource)
        .distinctUntilChanged()

    override suspend fun setScheduleSource(source: ScheduleSource) {
        dataStore.edit { preferences ->
            if (readSource(preferences) != source) {
                preferences[SettingsKeys.SCHEDULE_SOURCE] = source.preferenceValue()
                if (preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] != null) {
                    preferences[SettingsKeys.SCHEDULE_MONITORING_GENERATION] = UUID.randomUUID().toString()
                }
            }
        }
    }

    override val appearance: Flow<Appearance> = preferences
        .map {
            Appearance(
                themeMode = readEnum(it[SettingsKeys.THEME_MODE], ThemeMode.SYSTEM),
                palette = readEnum(it[SettingsKeys.COLOR_PALETTE], ColorPalette.ORIGINAL),
                animationMode = readEnum(it[SettingsKeys.ANIMATION_MODE], AnimationMode.NORMAL),
            )
        }
        .distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[SettingsKeys.THEME_MODE] = mode.preferenceValue() }
    }

    override suspend fun setPalette(palette: ColorPalette) {
        dataStore.edit { it[SettingsKeys.COLOR_PALETTE] = palette.preferenceValue() }
    }

    override suspend fun setAnimationMode(mode: AnimationMode) {
        dataStore.edit { it[SettingsKeys.ANIMATION_MODE] = mode.preferenceValue() }
    }

    override val monitoring: Flow<ScheduleMonitoring> = preferences.map {
        ScheduleMonitoring(
            selection = readSelection(it),
            generation = it[SettingsKeys.SCHEDULE_MONITORING_GENERATION],
            blocked = it[SettingsKeys.SCHEDULE_NOTIFICATIONS_BLOCKED] == true,
            source = readSource(it),
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

    private fun readSource(preferences: Preferences): ScheduleSource =
        readEnum(preferences[SettingsKeys.SCHEDULE_SOURCE], ScheduleSource.GROUP)
}

private fun Enum<*>.preferenceValue(): String = name.lowercase(Locale.ROOT)

private inline fun <reified T : Enum<T>> readEnum(raw: String?, default: T): T =
    enumValues<T>().firstOrNull { it.preferenceValue() == raw } ?: default
