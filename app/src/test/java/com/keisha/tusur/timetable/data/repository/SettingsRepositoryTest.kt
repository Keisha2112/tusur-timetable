package com.keisha.tusur.timetable.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.keisha.tusur.timetable.data.local.SettingsKeys
import com.keisha.tusur.timetable.domain.model.AnimationMode
import com.keisha.tusur.timetable.domain.model.Appearance
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.ColorPalette
import com.keisha.tusur.timetable.domain.model.SchedulePreference
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `upgrading from 1_4_0 retains group and remembers sources and appearance across restart`() = runBlocking {
        val group = UserSelection("fb", "Факультет безопасности", "714-2", "714-2")
        val auditorium = Auditorium("mk", "205-a2a74d79-c6e0-492e-b996-d5fa56fcc305", "205")
        val file = files.root.resolve("settings.preferences_pb")
        val original = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + original)) { file }
            store.edit {
                it[SettingsKeys.FACULTY_CODE] = group.facultyCode
                it[SettingsKeys.FACULTY_NAME] = group.facultyName
                it[SettingsKeys.GROUP_SLUG] = group.groupSlug
                it[SettingsKeys.GROUP_NAME] = group.groupName
                it[SettingsKeys.SCHEMA_VERSION] = 1
                it[SettingsKeys.THEME_MODE] = "dark"
                it[SettingsKeys.SCHEDULE_MONITORING_GENERATION] = "legacy-notification"
            }
            val settings = SettingsRepositoryImpl(store)
            assertEquals(group, settings.selection.first())
            assertEquals(Appearance(themeMode = ThemeMode.DARK), settings.appearance.first())
            assertEquals(SchedulePreference(), settings.schedulePreference.first())
            settings.setScheduleSource(ScheduleSource.ROOM)
            assertEquals(SchedulePreference(), settings.schedulePreference.first())
            settings.selectAuditorium(auditorium)
            assertEquals(SchedulePreference(ScheduleSource.ROOM, auditorium), settings.schedulePreference.first())
            settings.setScheduleSource(ScheduleSource.GROUP)
            settings.setPalette(ColorPalette.FOREST)
            settings.setAnimationMode(AnimationMode.FAST)
        } finally {
            original.cancelAndJoin()
        }

        val restarted = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + restarted)) { file }
            val settings = SettingsRepositoryImpl(store)
            assertEquals(group, settings.selection.first())
            assertEquals("legacy-notification", settings.monitoring.first().generation)
            assertEquals(SchedulePreference(ScheduleSource.GROUP, auditorium), settings.schedulePreference.first())
            assertEquals(Appearance(ThemeMode.DARK, ColorPalette.FOREST, AnimationMode.FAST), settings.appearance.first())
            settings.setScheduleSource(ScheduleSource.ROOM)
            assertEquals(SchedulePreference(ScheduleSource.ROOM, auditorium), settings.schedulePreference.first())
        } finally {
            restarted.cancelAndJoin()
        }
    }
}
