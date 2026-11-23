package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.RoomCacheDao
import com.keisha.tusur.timetable.data.local.RoomCatalogCacheEntity
import com.keisha.tusur.timetable.data.local.RoomScheduleCacheEntity
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.parser.FixtureLoader
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Building
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class RoomScheduleRepositoryTest {
    private val server = MockWebServer()
    private val dao = FakeRoomCacheDao()
    private val room = Auditorium("rk", "328", "328")
    private val week = Week(852, 14, LocalDate.of(2026, 11, 30), WeekKind.THEORETICAL, false)
    private val clock = Clock.fixed(Instant.parse("2026-11-30T02:00:00Z"), ZoneOffset.UTC)
    private lateinit var repository: RoomScheduleRepository

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val localUrl = request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(request.newBuilder().url(localUrl).build())
            }.build()
        repository = RoomScheduleRepository(dao, TusurClient(client), WeekScheduleParser(), clock = clock)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `explicit week uses etag and preserves verified cache on 304 server failure and timeout`() = runBlocking {
        server.enqueue(MockResponse().setBody(FixtureLoader.text("room_rk_328_next.html")).setHeader("ETag", "\"week-1\""))
        assertNull(repository.load(room, week))
        assertEquals("/buildings/rk/auditoriums/328?week_id=852", server.takeRequest().path)
        val saved = repository.observe(room).first()
        assertNotNull(saved)
        assertTrue(saved!!.entries.lessons.isNotEmpty())

        assertNull(repository.load(room, week))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(304))
        assertNull(repository.load(room, week, forceRefresh = true))
        assertEquals("\"week-1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(saved, repository.observe(room).first())

        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(repository.load(room, week, forceRefresh = true) is AppError.Server)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(repository.load(room, week, forceRefresh = true) is AppError.Network)
        assertEquals(saved, repository.observe(room).first())
        assertNull(repository.observe(Auditorium("ulk", room.slug, room.number)).first())
    }

    @Test
    fun `catalog cold fallback uses ordinary building and warm fallback uses cache`() = runBlocking {
        val building = Building("mk", "мк")
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(FixtureLoader.text("room_catalog_mk.html").replace("/before_breaking/", "/")))
        val cached = repository.rooms(building)
        assertEquals(4, cached.size)
        assertEquals("/before_breaking/buildings/mk", server.takeRequest().path)
        assertEquals("/buildings/mk", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(cached, repository.rooms(building, forceRefresh = true))
        assertEquals(3, server.requestCount)
    }

    private class FakeRoomCacheDao : RoomCacheDao() {
        private val catalogs = mutableMapOf<String, RoomCatalogCacheEntity>()
        private val schedules = MutableStateFlow<Map<Pair<String, String>, RoomScheduleCacheEntity>>(emptyMap())

        override suspend fun catalog(key: String) = catalogs[key]

        override suspend fun saveCatalog(entity: RoomCatalogCacheEntity) {
            catalogs[entity.key] = entity
        }

        override suspend fun schedule(building: String, slug: String) = schedules.value[building to slug]

        override fun observeSchedule(building: String, slug: String) = schedules.map { it[building to slug] }

        override suspend fun saveSchedule(entity: RoomScheduleCacheEntity) {
            schedules.update { it + ((entity.buildingCode to entity.roomSlug) to entity) }
        }

        override suspend fun clearCatalog() {
            catalogs.clear()
        }

        override suspend fun clearSchedules() {
            schedules.value = emptyMap()
        }
    }
}
