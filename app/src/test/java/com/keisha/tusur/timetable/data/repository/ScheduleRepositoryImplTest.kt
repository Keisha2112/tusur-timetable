package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.ScheduleCacheDao
import com.keisha.tusur.timetable.data.local.ScheduleCacheEntity
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.parser.FixtureLoader
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Group
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Поведение репозитория на реальных фикстурах: кэш, ETag/304, офлайн и ошибки.
 * Запросы уходят на MockWebServer, адреса сайта подменяются перехватчиком.
 */
class ScheduleRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeScheduleCacheDao

    private val group = Group(
        facultyCode = "fb",
        slug = "714-2",
        name = "714-2",
        course = 3,
    )

    private val ics: String by lazy { FixtureLoader.text("714-2.ics") }
    private val html: String by lazy { FixtureLoader.text("group_714-2.html") }

    @Before
    fun setUp() {
        server = MockWebServer()
        dao = FakeScheduleCacheDao()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun repository(dispatcher: Dispatcher): ScheduleRepositoryImpl {
        server.dispatcher = dispatcher
        server.start()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(request.newBuilder().url(url).build())
            }
            .build()

        return ScheduleRepositoryImpl(
            dao = dao,
            client = TusurClient(client),
            weekListParser = WeekListParser(),
            icsParser = IcsParser(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `cold load parses ics and weeks and stores cache`() = runBlocking {
        val repository = repository(fixtureDispatcher())

        val result = repository.load(group)

        val schedule = result.schedule
        assertNotNull(schedule)
        assertEquals(172, schedule!!.lessons.size)
        assertEquals(23, schedule.weeks.size)
        assertEquals("714-2", schedule.group.slug)
        assertTrue(!result.isFromCache)
        assertNull(result.error)

        val stored = dao.get(group.slug)
        assertNotNull(stored)
        assertEquals("\"ics-1\"", stored!!.icsEtag)
        assertEquals("\"html-1\"", stored.weeksEtag)
    }

    @Test
    fun `second load within ttl does not hit network`() = runBlocking {
        val repository = repository(fixtureDispatcher())
        repository.load(group)
        val requestsAfterFirstLoad = server.requestCount

        val result = repository.load(group)

        assertEquals(requestsAfterFirstLoad, server.requestCount)
        assertTrue(result.isFromCache)
        assertEquals(172, result.schedule?.lessons?.size)
    }

    @Test
    fun `forced refresh sends etag and handles 304 without losing data`() = runBlocking {
        val repository = repository(fixtureDispatcher())
        repository.load(group)

        val requestsBeforeRefresh = server.requestCount
        val result = repository.load(group, forceRefresh = true)

        assertTrue(server.requestCount > requestsBeforeRefresh)
        assertEquals(172, result.schedule?.lessons?.size)
        assertNull(result.error)
        assertNotNull(dao.get(group.slug)?.payload)

        var conditionalRequests = 0
        while (true) {
            val request = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            if (request.getHeader("If-None-Match") != null) conditionalRequests++
        }
        assertTrue("ожидались условные запросы с ETag", conditionalRequests >= 1)
    }

    @Test
    fun `network failure returns cache and does not overwrite it`() = runBlocking {
        val repository = repository(fixtureDispatcher())
        repository.load(group)
        val cachedPayload = dao.get(group.slug)?.payload

        server.shutdown()
        val result = repository.load(group, forceRefresh = true)

        assertEquals(172, result.schedule?.lessons?.size)
        assertTrue(result.isFromCache)
        assertTrue(result.isOffline)
        assertTrue(result.error is AppError.Network)
        assertEquals(cachedPayload, dao.get(group.slug)?.payload)
    }

    @Test
    fun `404 without cache reports not found and keeps cache empty`() = runBlocking {
        val repository = repository(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404)
        })

        val result = repository.load(group)

        assertNull(result.schedule)
        assertTrue(result.error is AppError.NotFound)
        assertNull(dao.get(group.slug))
    }

    @Test
    fun `empty ics is stored as empty schedule and is not an error`() = runBlocking {
        val repository = repository(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().endsWith(".ics")) {
                    MockResponse().setResponseCode(200).setBody("")
                } else {
                    MockResponse().setResponseCode(200).setBody(html)
                }
        })

        val result = repository.load(group)

        assertNotNull(result.schedule)
        assertTrue(result.schedule!!.lessons.isEmpty())
        assertNull(result.error)
        assertNotNull(dao.get(group.slug))
    }

    private fun fixtureDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val etag = if (path.endsWith(".ics")) "\"ics-1\"" else "\"html-1\""

            // Второй запрос с тем же ETag получает 304, как на реальном сайте.
            if (request.getHeader("If-None-Match") == etag) {
                return MockResponse().setResponseCode(304).addHeader("ETag", etag)
            }

            return when {
                path.endsWith(".ics") -> MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", etag)
                    .setBody(ics)

                path.contains("/groups/") -> MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", etag)
                    .setBody(html)

                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private class FakeScheduleCacheDao : ScheduleCacheDao {

        private var entity: ScheduleCacheEntity? = null

        override suspend fun get(groupSlug: String): ScheduleCacheEntity? = entity

        override suspend fun updatedAt(groupSlug: String): Long? = entity?.updatedAt

        override suspend fun upsert(entity: ScheduleCacheEntity) {
            this.entity = entity
        }

        override suspend fun delete(groupSlug: String) {
            entity = null
        }

        override suspend fun clear() {
            entity = null
        }

        override suspend fun deleteAll() {
            entity = null
        }
    }
}
