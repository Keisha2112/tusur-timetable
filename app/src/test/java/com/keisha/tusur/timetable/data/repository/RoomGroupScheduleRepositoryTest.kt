package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.RoomCacheDao
import com.keisha.tusur.timetable.data.local.RoomCatalogCacheEntity
import com.keisha.tusur.timetable.data.local.RoomNotificationBaselineEntity
import com.keisha.tusur.timetable.data.local.RoomWeekCacheEntity
import com.keisha.tusur.timetable.data.local.RoomWeekStateEntity
import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec.json
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import com.keisha.tusur.timetable.domain.model.WeekPublication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RoomGroupScheduleRepositoryTest {
    private val server = MockWebServer()
    private val dao = FakeRoomCacheDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val week = Week(851, 13, LocalDate.of(2026, 11, 23), WeekKind.THEORETICAL, true)
    private val clock = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC)
    private val group = Group("fb", "713-1", "713-1")
    private val other = Group("fb", "714-2", "714-2")
    private val rooms = listOf(
        Auditorium("gk", "205", "205"), Auditorium("mk", "208", "208"),
        Auditorium("rk", "210", "210"), Auditorium("ulk", "211", "211"), Auditorium("f", "212", "212"),
    )
    @Volatile private var respond: (Auditorium, RecordedRequest) -> MockResponse = { room, _ -> page(room) }
    private lateinit var repository: RoomGroupScheduleRepository

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = checkNotNull(request.requestUrl)
                val room = rooms.single { it.slug == url.pathSegments.last() }
                return respond(room, request)
            }
        }
        server.start()
        val client = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(request.newBuilder().url(url).build())
        }.build()
        repository = RoomGroupScheduleRepository(dao, TusurClient(client), WeekScheduleParser(), scope, clock = clock)
        runBlocking {
            dao.saveCatalog(RoomCatalogCacheEntity("auditoriums", json.encodeToString(rooms), clock.millis()))
            dao.saveCatalog(RoomCatalogCacheEntity("weeks", json.encodeToString(listOf(week)), clock.millis()))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun `all buildings combine shared groups and confirmed repeats while keeping both subgroups`() = runBlocking {
        respond = { room, _ ->
            val groups = groupLink(group) + groupLink(other)
            val content = when (room) {
                rooms[0] -> training("42", "Общая лекция", groups)
                rooms[1] -> training("42", "Общая лекция", groupLink(other) + groupLink(group))
                rooms[2] -> training("43", "Лабораторная", groupLink(group), "1 подгруппа")
                rooms[3] -> training("43", "Лабораторная", groupLink(group), "2 подгруппа")
                else -> training("44", "По номеру", "<span class='group' title='гр. 713-1'></span>") +
                    training("45", "Не эта группа", groupLink(group.copy(facultyCode = "fit")))
            }
            page(room, content)
        }
        val result = repository.load(group)
        assertNull(result.error)
        val lessons = checkNotNull(result.schedule).lessons
        assertEquals(4, lessons.size)
        val lecture = lessons.single { it.discipline == "Общая лекция" }
        assertEquals(rooms.take(2).map(Auditorium::label).sorted(), lecture.rooms)
        assertEquals("https://timetable.tusur.ru/buildings/gk/auditoriums/205?week_id=851", lecture.sourceUrl)
        assertEquals(setOf("1 подгруппа", "2 подгруппа"), lessons.mapNotNull { it.note }.toSet())
        val anotherGroup = repository.load(other)
        assertEquals(listOf(lecture), anotherGroup.schedule?.lessons)
        assertEquals(rooms.size, server.requestCount)
    }

    @Test
    fun `first incomplete pass exposes found lessons and resumes remaining rooms before confirming empty`() = runBlocking {
        respond = { room, _ ->
            when (room) {
                rooms.last() -> MockResponse().setResponseCode(503)
                rooms.first() -> page(room, training("51", "Найденная пара", groupLink(group)))
                else -> page(room)
            }
        }
        val partial = repository.load(group)
        assertTrue(partial.error is AppError.Server)
        val schedule = checkNotNull(partial.schedule)
        assertEquals("Найденная пара", schedule.lessons.single().discipline)
        assertTrue(schedule.publications.isEmpty())
        assertEquals(4, schedule.roomCoverage.getValue(week.id).checked)
        assertFalse(schedule.roomCoverage.getValue(week.id).complete)
        assertTrue(checkNotNull(repository.cached(other)).publications.isEmpty())

        respond = { room, _ -> page(room) }
        val complete = checkNotNull(repository.load(other).schedule)
        assertTrue(complete.lessons.isEmpty())
        assertEquals(WeekPublication.PUBLISHED, complete.publications[week.id])
        assertTrue(complete.roomCoverage.getValue(week.id).complete)
        assertEquals(rooms.size + 1, server.requestCount)
    }

    @Test
    fun `manual sweep uses etags and retains last full snapshot after broken room page`() = runBlocking {
        respond = { room, _ ->
            page(room, if (room == rooms.first()) training("52", "Исходная пара", groupLink(group)) else "")
        }
        val original = checkNotNull(repository.load(group).schedule)
        val conditional = AtomicInteger()
        respond = { _, request ->
            if (request.getHeader("If-None-Match") == "\"test\"") conditional.incrementAndGet()
            MockResponse().setResponseCode(304)
        }
        assertEquals(original.lessons, repository.loadWeek(group, week, true).schedule?.lessons)
        assertEquals(rooms.size, conditional.get())

        respond = { room, _ ->
            if (room == rooms.last()) MockResponse().setResponseCode(503)
            else page(room)
        }
        val failed = repository.refresh(group, ScheduleRefreshReason.MANUAL, week.id)
        assertTrue(failed.error is AppError.Server)
        assertEquals(original.lessons, failed.schedule?.lessons)
        assertEquals(setOf(week.id), failed.schedule?.staleWeeks)
        assertEquals(original.publications, failed.schedule?.publications)
    }

    @Test
    fun `simultaneous group viewers and manual refresh share a scan with three requests in flight`() = runBlocking {
        val firstBatch = CountDownLatch(3)
        val release = CountDownLatch(1)
        respond = { room, _ ->
            firstBatch.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            page(room)
        }
        val visible = async { repository.load(group) }
        val manual = async { repository.refresh(other, ScheduleRefreshReason.MANUAL, week.id) }
        val now = async { repository.load(other) }
        try {
            assertTrue(withContext(Dispatchers.IO) { firstBatch.await(5, TimeUnit.SECONDS) })
            assertEquals(3, server.requestCount)
            val loading = withTimeout(5_000) {
                repository.observe(group).first { it?.roomCoverage?.get(week.id)?.loading == true }
            }
            assertTrue(loading?.publications.orEmpty().isEmpty())
        } finally {
            release.countDown()
        }
        assertNull(visible.await().error)
        assertNull(manual.await().error)
        assertNull(now.await().error)
        assertEquals(rooms.size, server.requestCount)
    }

    private fun page(room: Auditorium, content: String = ""): MockResponse {
        val rows = listOf("пн", "вт", "ср", "чт", "пт", "сб").mapIndexed { index, day ->
            "<tr><td>$day, ${23 + index} нояб.</td><td>08:50 10:25</td>" +
                "<td>${content.takeIf { index == 0 }.orEmpty()}</td></tr>"
        }.joinToString("")
        return MockResponse().setHeader("ETag", "\"test\"").setBody(
            """
            <ul class="weeks"><li class="current current-week">
               <a href="/buildings/${room.buildingCode}/auditoriums/${room.slug}?week_id=851">13 нечётная с 23 ноября 2026</a>
            </li></ul>
            <table class="screen-reader-element">$rows</table>
            """.trimIndent(),
        )
    }

    private fun groupLink(group: Group) =
        "<a href='/faculties/${group.facultyCode}/groups/${group.slug}'>${group.name}</a>"

    private fun training(id: String, subject: String, groups: String, note: String? = null) =
        """<div class="lesson-cell"><div class="training">
           <span class="discipline" data-lesson-id="$id">$subject</span>
           <span class="kind">Лекция</span>$groups${note?.let { "<span class='note' title='$it'></span>" }.orEmpty()}
           </div></div>"""

    private class FakeRoomCacheDao : RoomCacheDao() {
        private val catalog = MutableStateFlow<Map<String, RoomCatalogCacheEntity>>(emptyMap())
        private val states = MutableStateFlow<Map<Pair<Int, String>, RoomWeekStateEntity>>(emptyMap())
        private val cachedPages = ConcurrentHashMap<Triple<Int, String, String>, RoomWeekCacheEntity>()
        private val baselines = ConcurrentHashMap<String, RoomNotificationBaselineEntity>()

        override suspend fun catalog(key: String) = catalog.value[key]
        override fun observeCatalog(key: String) = catalog.map { it[key] }
        override suspend fun saveCatalog(entity: RoomCatalogCacheEntity) = catalog.update { it + (entity.key to entity) }
        override suspend fun pages(weekId: Int, startDate: String) =
            cachedPages.values.filter { it.weekId == weekId && it.startDate == startDate }
        override suspend fun savePage(entity: RoomWeekCacheEntity) {
            cachedPages[Triple(entity.weekId, entity.startDate, entity.roomKey)] = entity
        }
        override suspend fun weeks() = states.value.values.toList()
        override fun observeWeeks() = states.map { it.values.toList() }
        override suspend fun week(weekId: Int, startDate: String) = states.value[weekId to startDate]
        override suspend fun saveWeek(entity: RoomWeekStateEntity) =
            states.update { it + ((entity.weekId to entity.startDate) to entity) }
        override suspend fun baseline(generation: String) = baselines[generation]
        override suspend fun saveBaseline(entity: RoomNotificationBaselineEntity) {
            baselines[entity.generation] = entity
        }
        override suspend fun pruneBaselines(oldest: Long) {
            baselines.values.removeAll { it.updatedAt < oldest }
        }
        override suspend fun clearCatalog() { catalog.value = emptyMap() }
        override suspend fun clearPages() = cachedPages.clear()
        override suspend fun clearWeeks() { states.value = emptyMap() }
        override suspend fun clearBaselines() = baselines.clear()
    }
}
