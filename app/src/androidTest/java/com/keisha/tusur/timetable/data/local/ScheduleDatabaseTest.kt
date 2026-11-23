package com.keisha.tusur.timetable.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.data.repository.RoomGroupScheduleRepository
import com.keisha.tusur.timetable.data.repository.ScheduleMonitoring
import com.keisha.tusur.timetable.data.repository.ScheduleRefreshReason
import com.keisha.tusur.timetable.data.repository.ScheduleRepositoryImpl
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.change.ChangeKind
import com.keisha.tusur.timetable.domain.change.ScheduleChange
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ScheduleDatabaseTest {

    @get:Rule
    val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun updatesFromAllOldSchemasKeepCacheAndNotificationQueue() {
        for (start in 1..4) {
            val name = "schedule-migration-$start-" + UUID.randomUUID()
            migration.createDatabase(name, start).use { db ->
                db.execSQL("INSERT INTO schedule_cache VALUES ('714-2', 'legacy', NULL, NULL, 42, 42)")
                if (start >= 2) {
                    db.execSQL("INSERT INTO teacher_schedule_cache VALUES ('teacher', 'teacher-cache', 42, 42)")
                }
                if (start >= 3) {
                    db.execSQL("INSERT INTO schedule_notifications VALUES ('notice', 'generation', '[]', 42)")
                }
                if (start == 4) {
                    db.execSQL("INSERT INTO room_schedule_cache VALUES ('mk', '205', 'old-room')")
                    db.execSQL("INSERT INTO room_catalog_cache VALUES ('buildings', 'catalog', 42)")
                }
            }
            migration.runMigrationsAndValidate(
                name, 5, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            ).use { db ->
                db.query("SELECT payload FROM schedule_cache WHERE groupSlug = '714-2'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("legacy", it.getString(0))
                }
                if (start >= 2) {
                    db.query("SELECT payload FROM teacher_schedule_cache WHERE teacherKey = 'teacher'").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("teacher-cache", it.getString(0))
                    }
                }
                db.query("SELECT COUNT(*) FROM schedule_notifications").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(if (start >= 3) 1 else 0, it.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'room_schedule_cache'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM room_catalog_cache").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(if (start == 4) 1 else 0, it.getInt(0))
                }
            }
        }
    }

    @Test
    fun partialRoomWeekDoesNotReplaceTheBaselineOrCreateCancellations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val group = Group("fb", "714-2", "714-2")
            val generation = "from-rooms"
            val monday = LocalDate.of(2026, 11, 23)
            val week = Week(851, 13, monday, WeekKind.THEORETICAL, true)
            val next = week.copy(id = 852, number = 14, startDate = monday.plusWeeks(1), isCurrent = false)
            val first = Auditorium("mk", "205", "205")
            val second = Auditorium("ulk", "406", "406")
            val failing = AtomicBoolean()
            val occupied = AtomicReference(second.slug)
            val time = AtomicReference(Instant.parse("2026-11-23T00:00:00Z"))
            val clock = object : Clock() {
                override fun getZone() = ZoneOffset.UTC
                override fun withZone(zone: ZoneId) = Clock.fixed(instant(), zone)
                override fun instant(): Instant = time.get()
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val room = if (request.url.pathSegments.last() == first.slug) first else second
                val requested = if (request.url.queryParameter("week_id") == "852") next else week
                val error = room == second && requested == week && failing.get()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (error) 503 else 200).message(if (error) "Unavailable" else "OK")
                    .body(roomHtml(room, requested, requested == week && occupied.get() == room.slug).toResponseBody())
                    .build()
            }.build()
            val dao = db.roomCacheDao()
            dao.saveCatalog(RoomCatalogCacheEntity("auditoriums", ScheduleCacheCodec.json.encodeToString(listOf(first, second)), clock.millis()))
            dao.saveCatalog(RoomCatalogCacheEntity("weeks", ScheduleCacheCodec.json.encodeToString(listOf(week, next)), clock.millis()))
            val repository = RoomGroupScheduleRepository(dao, TusurClient(client), WeekScheduleParser(), scope, db, clock) {
                ScheduleMonitoring(UserSelection(group.facultyCode, "", group.slug, group.name), generation,
                    source = ScheduleSource.GROUP_FROM_ROOMS)
            }
            val initial = repository.load(group)
            assertNull(initial.error)
            assertEquals(listOf(second.label), initial.schedule?.lessons?.single()?.rooms)
            val baseline = dao.baseline(generation)
            assertNotNull(baseline)
            val queue = db.scheduleNotificationDao()
            assertTrue(queue.pending(generation).isEmpty())

            failing.set(true)
            occupied.set(first.slug)
            time.set(time.get().plusSeconds(7_200))
            val incomplete = repository.refresh(group, ScheduleRefreshReason.BACKGROUND, monitoringGeneration = generation)
            assertNotNull(incomplete.error)
            assertEquals(listOf(second.label), incomplete.schedule?.lessons?.single()?.rooms)
            assertEquals(1, incomplete.schedule?.roomCoverage?.get(week.id)?.checked)
            assertTrue(dao.baseline(generation)!!.payload.contains("\"851\""))
            assertTrue(queue.pending(generation).isEmpty())

            failing.set(false)
            time.set(time.get().plusSeconds(3_600))
            val complete = repository.refresh(group, ScheduleRefreshReason.BACKGROUND, monitoringGeneration = generation)
            assertNull(complete.error)
            assertEquals(listOf(first.label), complete.schedule?.lessons?.single()?.rooms)
            val change = ScheduleCacheCodec.json.decodeFromString<List<ScheduleChange>>(queue.pending(generation).single().payload).single()
            assertEquals(setOf(ChangeKind.ROOM), change.kinds)
            assertEquals(listOf(second.label), change.before?.rooms)
            assertEquals(listOf(first.label), change.after?.rooms)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    private fun roomHtml(room: Auditorium, week: Week, occupied: Boolean): String {
        val rows = listOf("пн", "вт", "ср", "чт", "пт", "сб").mapIndexed { index, day ->
            val date = ScheduleTime.formatDayTitle(week.startDate.plusDays(index.toLong())).substringAfter(", ")
            val lesson = if (occupied && index == 2) """
                <div class="lesson-cell"><div class="training">
                    <span class="discipline" data-lesson-id="61">Численные методы</span>
                    <span class="kind">Практика</span><a href="/faculties/fb/groups/714-2">714-2</a>
                </div></div>
            """ else ""
            "<tr><td>$day, $date</td><td>08:50 10:25</td><td>$lesson</td></tr>"
        }.joinToString("")
        return """
            <ul class="weeks"><li class="current current-week">
              <a href="/buildings/${room.buildingCode}/auditoriums/${room.slug}?week_id=${week.id}">
                ${week.number} нечётная с ${ScheduleTime.formatWeekStart(week.startDate)}
              </a>
            </li></ul>
            <table class="screen-reader-element">$rows</table>
        """.trimIndent()
    }

    @Test
    fun concurrentManualAndBackgroundChecksEnqueueOneChange() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            var room = "улк 406"
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val content = if (request.url.encodedPath.endsWith(".ics")) "" else weekHtml(room)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(content.toResponseBody()).build()
            }.build()
            val clock = Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"), ZoneOffset.UTC)
            fun repository() = ScheduleRepositoryImpl(
                db.scheduleCacheDao(), TusurClient(client), WeekListParser(), IcsParser(),
                database = db, clock = clock,
            )
            val group = Group("fb", "714-2", "714-2")
            val generation = "714-2-generation"
            val initial = repository().refresh(group, ScheduleRefreshReason.MANUAL, monitoringGeneration = generation)
            assertNotNull(initial.schedule)
            assertTrue(db.scheduleNotificationDao().pending(generation).isEmpty())
            assertEquals(listOf("улк 406"), initial.schedule?.lessons?.single()?.rooms)

            room = "улк 312"
            val repo = repository()
            awaitAll(
                async { repo.refresh(group, ScheduleRefreshReason.BACKGROUND, monitoringGeneration = generation) },
                async { repo.refresh(group, ScheduleRefreshReason.MANUAL, monitoringGeneration = generation) },
            )
            val dao = db.scheduleNotificationDao()
            val notice = dao.pending(generation).single()
            val change = ScheduleCacheCodec.json.decodeFromString<List<ScheduleChange>>(notice.payload).single()
            assertEquals(setOf(ChangeKind.ROOM), change.kinds)
            assertEquals(listOf("улк 312"), change.after?.rooms)
            assertEquals(1, dao.claim(notice.id))
            assertEquals(0, dao.claim(notice.id))
            repository().refresh(group, ScheduleRefreshReason.BACKGROUND, monitoringGeneration = generation)
            assertTrue(dao.pending(generation).isEmpty())
        } finally {
            db.close()
        }
    }

    private fun weekHtml(room: String): String {
        val days = listOf("пн", "вт", "ср", "чт", "пт", "сб")
        val rows = days.mapIndexed { index, day ->
            val content = if (index == 0) """
                <div class="lesson-cell"><div class="training">
                  <span class="discipline" data-lesson-id="42">Численные методы</span>
                  <span class="kind">Практика</span><span class="auditoriums">$room</span>
                </div></div>
            """.trimIndent() else ""
            "<tr><td>$day, ${23 + index} нояб.</td><td>08:50 10:25</td><td>$content</td></tr>"
        }.joinToString("")
        return """
            <ul class="weeks"><li class="current current-week">
              <a href="/faculties/fb/groups/714-2?week_id=851">13 нечётная с 23 ноября 2026</a>
            </li></ul>
            <table class="screen-reader-element">$rows</table>
        """.trimIndent()
    }
}
