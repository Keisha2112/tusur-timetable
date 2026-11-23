package com.keisha.tusur.timetable.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.keisha.tusur.timetable.data.local.RoomSourceMigration
import com.keisha.tusur.timetable.data.local.SettingsKeys
import com.keisha.tusur.timetable.domain.model.AnimationMode
import com.keisha.tusur.timetable.domain.model.Appearance
import com.keisha.tusur.timetable.domain.model.ColorPalette
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `upgrading from 1_5_0 retains group and appearance and migrates room source`() = runBlocking {
        val group = UserSelection("fb", "Факультет безопасности", "714-2", "714-2")
        val roomBuilding = stringPreferencesKey("room_building")
        val roomSlug = stringPreferencesKey("room_slug")
        val roomNumber = stringPreferencesKey("room_number")
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
                it[SettingsKeys.COLOR_PALETTE] = "forest"
                it[SettingsKeys.ANIMATION_MODE] = "fast"
                it[SettingsKeys.SCHEDULE_MONITORING_GENERATION] = "legacy-notification"
                it[SettingsKeys.SCHEDULE_SOURCE] = "room"
                it[roomBuilding] = "mk"
                it[roomSlug] = "205"
                it[roomNumber] = "205"
            }
        } finally {
            original.cancelAndJoin()
        }

        val restarted = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + restarted),
                migrations = listOf(RoomSourceMigration),
            ) { file }
            val settings = SettingsRepositoryImpl(store)
            assertEquals(group, settings.selection.first())
            val migrated = settings.monitoring.first()
            assertNotEquals("legacy-notification", migrated.generation)
            assertEquals(ScheduleSource.GROUP_FROM_ROOMS, migrated.source)
            assertEquals(ScheduleSource.GROUP_FROM_ROOMS, settings.scheduleSource.first())
            assertEquals(Appearance(ThemeMode.DARK, ColorPalette.FOREST, AnimationMode.FAST), settings.appearance.first())
            val preferences = store.data.first()
            assertNull(preferences[roomBuilding])
            assertNull(preferences[roomSlug])
            assertNull(preferences[roomNumber])
            settings.setScheduleSource(ScheduleSource.GROUP_FROM_ROOMS)
            assertEquals(migrated.generation, settings.monitoring.first().generation)
            settings.setScheduleSource(ScheduleSource.GROUP)
            assertEquals(ScheduleSource.GROUP, settings.scheduleSource.first())
            assertNotEquals(migrated.generation, settings.monitoring.first().generation)
            val oldGroupGeneration = settings.monitoring.first().generation
            settings.saveSelection(group.copy(groupName = "713-1", groupSlug = "713-1"))
            assertNotEquals(oldGroupGeneration, settings.monitoring.first().generation)
        } finally {
            restarted.cancelAndJoin()
        }
    }
}
