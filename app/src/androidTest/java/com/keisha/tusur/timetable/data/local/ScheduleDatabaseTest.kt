package com.keisha.tusur.timetable.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.data.repository.ScheduleRefreshReason
import com.keisha.tusur.timetable.data.repository.ScheduleRepositoryImpl
import com.keisha.tusur.timetable.domain.change.ChangeKind
import com.keisha.tusur.timetable.domain.change.ScheduleChange
import com.keisha.tusur.timetable.domain.model.Group
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduleDatabaseTest {

    @get:Rule
    val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun updatesFromAllOldSchemasKeepCacheAndNotificationQueue() {
        for (start in 1..3) {
            val name = "schedule-migration-$start-" + UUID.randomUUID()
            migration.createDatabase(name, start).use { db ->
                db.execSQL("INSERT INTO schedule_cache VALUES ('714-2', 'legacy', NULL, NULL, 42, 42)")
                if (start >= 2) {
                    db.execSQL("INSERT INTO teacher_schedule_cache VALUES ('teacher', 'teacher-cache', 42, 42)")
                }
                if (start >= 3) {
                    db.execSQL("INSERT INTO schedule_notifications VALUES ('notice', 'generation', '[]', 42)")
                }
            }
            migration.runMigrationsAndValidate(
                name, 4, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
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
                db.query("SELECT COUNT(*) FROM room_schedule_cache").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM room_catalog_cache").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        }
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
