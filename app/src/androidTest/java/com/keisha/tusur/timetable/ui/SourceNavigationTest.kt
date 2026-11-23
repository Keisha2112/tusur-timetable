package com.keisha.tusur.timetable.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keisha.tusur.timetable.MainActivity
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.local.AppDatabase
import com.keisha.tusur.timetable.data.local.RoomCatalogCacheEntity
import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec
import com.keisha.tusur.timetable.data.local.ScheduleCacheEntity
import com.keisha.tusur.timetable.data.local.StoredSchedule
import com.keisha.tusur.timetable.data.local.StoredWeek
import com.keisha.tusur.timetable.data.local.settingsDataStore
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.notification.ScheduleNoticeDestination
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.data.repository.RoomScheduleRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepositoryImpl
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AnimationMode
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Building
import com.keisha.tusur.timetable.domain.model.ColorPalette
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.ThemeMode
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class SourceNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsRepositoryImpl(context)
    private val group = UserSelection("fb", "Факультет", "schedule-test", "Группа теста")
    private val room = Auditorium("mk", "205-test", "205")
    private val monday = ScheduleTime.weekStart(ScheduleTime.today())
    private val noticeDate = monday.plusDays(2)

    @Before
    fun prepareCache() = runBlocking {
        context.settingsDataStore.edit { it.clear() }
        settings.saveSelection(group)
        settings.setScheduleSource(ScheduleSource.GROUP)
        settings.setAnimationMode(AnimationMode.OFF)
        val database = AppDatabase.build(context)
        try {
            val now = System.currentTimeMillis()
            val week = Week(9001, 1, monday, WeekKind.THEORETICAL, true)
            val following = week.copy(id = 9002, number = 2, startDate = monday.plusWeeks(1), isCurrent = false)
            val lesson = Lesson(
                "notice", noticeDate, LocalTime.of(8, 50), LocalTime.of(10, 25), 1,
                "Занятие из уведомления", LessonType.LECTURE, "Лекция",
            )
            val storedWeek = StoredWeek(monday, now, publication = WeekPublication.PUBLISHED, lessons = listOf(lesson))
            val stored = StoredSchedule.empty(group.toGroup()).copy(
                weeks = listOf(week), icsLessons = listOf(lesson), htmlWeeks = mapOf(week.id to storedWeek), weekListCheckedAt = now,
            )
            database.scheduleCacheDao().upsert(
                ScheduleCacheEntity(group.groupSlug, ScheduleCacheCodec.encodeStored(stored), null, null, now, now),
            )
            val dao = database.roomCacheDao()
            dao.saveCatalog(RoomCatalogCacheEntity("buildings", ScheduleCacheCodec.json.encodeToString(listOf(Building("mk", "Малый корпус"))), now))
            dao.saveCatalog(RoomCatalogCacheEntity("rooms:mk", ScheduleCacheCodec.json.encodeToString(listOf(room)), now))
            val fakeClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val selected = if (request.url.queryParameter("week_id") == following.id.toString()) following else week
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(roomPage(selected).toResponseBody()).build()
            }.build()
            val repository = RoomScheduleRepository(dao, TusurClient(fakeClient), WeekScheduleParser())
            assertNull(repository.load(room, week))
            assertNull(repository.load(room, following))
        } finally {
            database.close()
        }
    }

    @After
    fun resetPreferences() = runBlocking {
        context.settingsDataStore.edit { it.clear() }
        Unit
    }

    @Test
    fun sourcePickerRemembersRoomAndNotificationReturnsToIt() {
        startup().use { scenario ->
            waitFor(group.groupName)
            pickSource(R.string.schedule_source_room)
            waitFor("Малый корпус")
            compose.onNodeWithText("Малый корпус").performClick()
            waitFor(room.label)
            compose.onNodeWithText(room.label).performClick()
            compose.onNodeWithText(context.getString(R.string.room_apply_auditorium)).performClick()
            waitForRoomSchedule()
            compose.onNode(hasScrollAction()).performTouchInput { swipeLeft() }
            compose.onNodeWithContentDescription(context.getString(R.string.cd_next_week)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(context.getString(R.string.cd_previous_week)).performClick()

            pickSource(R.string.schedule_source_group)
            waitFor(group.groupName)
            pickSource(R.string.schedule_source_room)
            waitForRoomSchedule()

            scenario.onActivity { activity ->
                activity.startActivity(
                    ScheduleNoticeDestination.intent(activity, group.toGroup(), noticeDate, "test-notice"),
                )
            }
            waitFor(group.groupName)
            waitFor("Занятие из уведомления")
            compose.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
            waitForRoomSchedule()
        }
        assertEquals(room, runBlocking { settings.schedulePreference.first().auditorium })
        assertEquals(ScheduleSource.ROOM, runBlocking { settings.schedulePreference.first().source })
    }

    @Test
    fun appearanceCanBeChangedAndPersistsAfterRecreatingActivity() {
        startup().use { scenario ->
            waitFor(group.groupName)
            openSettings()
            selectOption(R.string.settings_palette, R.string.settings_palette_marine)
            selectOption(R.string.settings_animations, R.string.settings_animations_fast)
            compose.onNodeWithText(context.getString(R.string.settings_theme_dark)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                runBlocking {
                    val value = settings.appearance.first()
                    value.palette == ColorPalette.MARINE && value.animationMode == AnimationMode.FAST &&
                        value.themeMode == ThemeMode.DARK
                }
            }
            scenario.recreate()
            waitFor(context.getString(R.string.settings_palette_marine))
            waitFor(context.getString(R.string.settings_animations_fast))
            selectOption(R.string.settings_animations, R.string.settings_animations_normal)
            selectOption(R.string.settings_animations, R.string.settings_animations_off)
            waitFor(context.getString(R.string.settings_animations_off))
        }
    }

    private fun startup(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(Intent(context, MainActivity::class.java))

    private fun pickSource(source: Int) {
        openSettings()
        val title = context.getString(R.string.schedule_source_title)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        selectOption(R.string.schedule_source_title, source)
    }

    private fun openSettings() {
        val title = context.getString(R.string.cd_settings)
        compose.onNodeWithContentDescription(title).performClick()
        waitFor(context.getString(R.string.settings_appearance_title))
    }

    private fun selectOption(title: Int, value: Int) {
        val label = context.getString(title)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.onNode(hasText(context.getString(value)) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForRoomSchedule() {
        val entries = context.getString(R.string.room_no_entries)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasText(entries)).fetchSemanticsNodes().isNotEmpty()
        }
        waitFor(room.label)
        compose.onNodeWithText("На сайте ТУСУРа могут быть указаны не все занятия", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Обновлено ", substring = true).assertDoesNotExist()
    }

    private fun roomPage(week: Week): String {
        val days = listOf("пн", "вт", "ср", "чт", "пт", "сб")
        val rows = days.mapIndexed { index, day ->
            val date = ScheduleTime.formatDayTitle(week.startDate.plusDays(index.toLong())).substringAfter(", ")
            "<tr><td>$day, $date</td><td>08:50 10:25</td><td></td></tr>"
        }.joinToString("")
        return """
            <ul class="weeks"><li class="current current-week">
              <a href="/buildings/${room.buildingCode}/auditoriums/${room.slug}?week_id=${week.id}">
                1 нечётная с ${ScheduleTime.formatWeekStart(week.startDate)}
              </a>
            </li></ul>
            <table class="screen-reader-element">$rows</table>
        """.trimIndent()
    }
}
