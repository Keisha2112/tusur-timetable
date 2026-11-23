package com.keisha.tusur.timetable.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keisha.tusur.timetable.MainActivity
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.local.AppDatabase
import com.keisha.tusur.timetable.data.local.FacultyEntity
import com.keisha.tusur.timetable.data.local.GroupEntity
import com.keisha.tusur.timetable.data.local.RoomCatalogCacheEntity
import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec
import com.keisha.tusur.timetable.data.local.ScheduleCacheEntity
import com.keisha.tusur.timetable.data.local.StoredSchedule
import com.keisha.tusur.timetable.data.local.StoredWeek
import com.keisha.tusur.timetable.data.local.settingsDataStore
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.notification.ScheduleNotifier
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.data.repository.RoomGroupScheduleRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepositoryImpl
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AnimationMode
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.change.ChangeKind
import com.keisha.tusur.timetable.domain.change.ScheduleChange
import com.keisha.tusur.timetable.domain.model.ColorPalette
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.ThemeMode
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val other = group.copy(groupSlug = "schedule-other", groupName = "Другая группа")
    private val room = Auditorium("mk", "205-test", "205")
    private val monday = ScheduleTime.weekStart(ScheduleTime.today())
    private val today = ScheduleTime.today()
    private val noticeDate = monday.plusWeeks(1).plusDays(2)

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
            val lesson = lesson("official", today, "Пара из расписания ТУСУРа")
            val storedWeek = StoredWeek(monday, now, publication = WeekPublication.PUBLISHED, lessons = listOf(lesson))
            val stored = StoredSchedule.empty(group.toGroup()).copy(
                weeks = listOf(week), icsLessons = listOf(lesson), htmlWeeks = mapOf(week.id to storedWeek), weekListCheckedAt = now,
            )
            database.scheduleCacheDao().upsert(
                ScheduleCacheEntity(group.groupSlug, ScheduleCacheCodec.encodeStored(stored), null, null, now, now),
            )
            database.catalogDao().replaceFaculties(listOf(FacultyEntity(group.facultyCode, group.facultyName, now)))
            database.catalogDao().replaceGroups(group.facultyCode, listOf(group, other).map {
                GroupEntity(it.facultyCode, it.groupSlug, it.groupName, 3, now)
            })
            val dao = database.roomCacheDao()
            dao.saveCatalog(RoomCatalogCacheEntity("auditoriums", ScheduleCacheCodec.json.encodeToString(listOf(room)), now))
            dao.saveCatalog(RoomCatalogCacheEntity("weeks", ScheduleCacheCodec.json.encodeToString(listOf(week, following)), now))
            val fakeClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val selected = if (request.url.queryParameter("week_id") == following.id.toString()) following else week
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(roomPage(selected).toResponseBody()).build()
            }.build()
            val repository = RoomGroupScheduleRepository(dao, TusurClient(fakeClient), WeekScheduleParser(), this)
            assertNull(repository.loadWeek(group.toGroup(), week).error)
            assertNull(repository.loadWeek(group.toGroup(), following).error)
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
    fun bothSourcesUseTheGroupScheduleAndSwitchGroupsFromRoomCache() {
        startup().use {
            waitFor("Пара из расписания ТУСУРа")
            pickSource(R.string.schedule_source_group_from_rooms)
            waitFor("Пара из данных аудиторий")
            waitFor(group.groupName)
            compose.onNodeWithText(context.getString(R.string.schedule_now)).performClick()
            waitFor(ScheduleTime.formatDayTitle(today))
            waitFor("Занятие из уведомления")
            back()
            waitFor("Пара из данных аудиторий")

            openSettings()
            compose.onNodeWithText(context.getString(R.string.settings_change_group)).performClick()
            waitFor(group.facultyName)
            compose.onNodeWithText(group.facultyName).performClick()
            waitFor(other.groupName)
            compose.onNodeWithText(other.groupName).performClick()
            waitFor("Пара другой группы")
            waitFor(other.groupName)
        }
        assertEquals(other, runBlocking { settings.selection.first() })
        assertEquals(ScheduleSource.GROUP_FROM_ROOMS, runBlocking { settings.scheduleSource.first() })
    }

    @Test
    fun notificationOpensItsDateAndSourceWithoutChangingSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val id = "rooms-deep-link"
        try {
            startup().use {
                waitFor("Пара из расписания ТУСУРа")
                val added = lesson("notice", noticeDate, "Занятие из уведомления")
                ScheduleNotifier(context).show(
                    id, group.toGroup(), listOf(ScheduleChange(setOf(ChangeKind.ADDED), after = added, date = noticeDate)),
                    ScheduleSource.GROUP_FROM_ROOMS,
                )
                runBlocking {
                    val contentIntent = withTimeout(5_000) {
                        var notice = manager.activeNotifications.firstOrNull { it.tag == id }
                        while (notice == null) {
                            delay(40)
                            notice = manager.activeNotifications.firstOrNull { it.tag == id }
                        }
                        notice.notification.contentIntent
                    }
                    contentIntent.send()
                }
                waitFor(ScheduleTime.formatDayTitle(noticeDate))
                waitFor("Занятие из уведомления")
                back()
                waitFor("Пара из расписания ТУСУРа")
            }
        } finally {
            manager.cancel(id, 0)
        }
        assertEquals(group, runBlocking { settings.selection.first() })
        assertEquals(ScheduleSource.GROUP, runBlocking { settings.scheduleSource.first() })
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

    private fun back() =
        compose.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()

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

    private fun lesson(id: String, date: LocalDate, name: String) =
        Lesson(id, date, LocalTime.of(8, 50), LocalTime.of(10, 25), 1, name, LessonType.LECTURE, "Лекция")

    private fun roomPage(week: Week): String {
        val days = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
        val rows = days.mapIndexed { index, day ->
            val dayDate = week.startDate.plusDays(index.toLong())
            val date = ScheduleTime.formatDayTitle(dayDate).substringAfter(", ")
            val entries = when (dayDate) {
                today -> roomLesson(group, "Пара из данных аудиторий", 61) + roomLesson(other, "Пара другой группы", 62)
                noticeDate -> roomLesson(group, "Занятие из уведомления", 63)
                else -> ""
            }
            "<tr><td>$day, $date</td><td>00:00 00:01</td><td>$entries</td></tr>"
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

    private fun roomLesson(group: UserSelection, subject: String, series: Int) = """
        <div class="lesson-cell"><div class="training">
            <span class="discipline" data-lesson-id="$series">$subject</span>
            <span class="kind">Лекция</span>
            <a href="/faculties/${group.facultyCode}/groups/${group.groupSlug}">${group.groupName}</a>
        </div></div>
    """.trimIndent()
}
