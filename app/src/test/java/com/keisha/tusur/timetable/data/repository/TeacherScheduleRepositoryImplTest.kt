package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.TeacherScheduleCacheDao
import com.keisha.tusur.timetable.data.local.TeacherScheduleCacheEntity
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.TeacherParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.domain.model.AppError
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
 * Расписание преподавателя на MockWebServer: поиск по фамилии, страница недель,
 * ICS с группами, кэш и офлайн-поведение.
 */
class TeacherScheduleRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeTeacherCacheDao

    @Before
    fun setUp() {
        server = MockWebServer()
        dao = FakeTeacherCacheDao()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun repository(dispatcher: Dispatcher): TeacherScheduleRepositoryImpl {
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

        return TeacherScheduleRepositoryImpl(
            dao = dao,
            client = TusurClient(client),
            weekListParser = WeekListParser(),
            icsParser = IcsParser(),
            teacherParser = TeacherParser(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `поиск по фамилии открывает преподавателя и загружает занятия`() = runBlocking {
        val repository = repository(uniqueTeacherDispatcher())

        val result = repository.load("Жидова Л.А.")

        val schedule = result.schedule
        assertNotNull(schedule)
        assertEquals("35144", schedule!!.teacher.id)
        assertEquals("Жидова Л.А.", schedule.teacher.shortName)
        assertEquals("Жидова Любовь Александровна", schedule.teacher.fullName)
        assertEquals(2, schedule.weeks.size)
        assertEquals(2, schedule.lessons.size)
        assertEquals(listOf("716-1", "716-2"), schedule.lessons.first().groups)
        assertTrue(schedule.lessons.all { it.teachers.isEmpty() })
        assertNull(result.error)
        assertNotNull(dao.get("жидова л.а."))
    }

    @Test
    fun `повторная загрузка в пределах ttl не ходит в сеть`() = runBlocking {
        val repository = repository(uniqueTeacherDispatcher())
        repository.load("Жидова Л.А.")
        val requestsAfterFirstLoad = server.requestCount

        val result = repository.load("Жидова Л.А.")

        assertEquals(requestsAfterFirstLoad, server.requestCount)
        assertTrue(result.isFromCache)
        assertEquals("35144", result.schedule?.teacher?.id)
    }

    @Test
    fun `принудительное обновление перезапрашивает ics`() = runBlocking {
        val repository = repository(uniqueTeacherDispatcher())
        repository.load("Жидова Л.А.")
        val requestsBeforeRefresh = server.requestCount

        val result = repository.load("Жидова Л.А.", forceRefresh = true)

        assertTrue(server.requestCount > requestsBeforeRefresh)
        assertEquals(2, result.schedule?.lessons?.size)
        assertNull(result.error)
    }

    @Test
    fun `офлайн возвращает кэш и не затирает его`() = runBlocking {
        val repository = repository(uniqueTeacherDispatcher())
        repository.load("Жидова Л.А.")
        val cachedPayload = dao.get("жидова л.а.")?.payload

        server.shutdown()
        val result = repository.load("Жидова Л.А.", forceRefresh = true)

        assertEquals(2, result.schedule?.lessons?.size)
        assertTrue(result.isFromCache)
        assertTrue(result.isOffline)
        assertTrue(result.error is AppError.Network)
        assertEquals(cachedPayload, dao.get("жидова л.а.")?.payload)
    }

    @Test
    fun `несколько однофамильцев выбираются по инициалам`() = runBlocking {
        val repository = repository(ambiguousTeacherDispatcher())

        val result = repository.load("Петров Г.К.")

        val teacher = result.schedule?.teacher
        assertNotNull(teacher)
        assertEquals("35757", teacher!!.id)
        assertEquals("Петров Г.К.", teacher.shortName)
        assertEquals("Петров Глеб Константинович", teacher.fullName)
    }

    @Test
    fun `поиск без результатов даёт not found`() = runBlocking {
        val repository = repository(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("<html><body>Ничего не найдено</body></html>")
        })

        val result = repository.load("Несуществующий П.П.")

        assertNull(result.schedule)
        assertTrue(result.error is AppError.NotFound)
        assertNull(dao.get("несуществующий п.п."))
    }

    private fun uniqueTeacherDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("/searches/") -> MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/teachers/35144")

                path == "/teachers/35144.ics" -> MockResponse()
                    .setResponseCode(200)
                    .setBody(TEACHER_ICS)

                path == "/teachers/35144" -> MockResponse()
                    .setResponseCode(200)
                    .setBody(teacherPage(id = "35144", shortName = "Жидова Л.А."))

                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun ambiguousTeacherDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("/searches/") -> MockResponse()
                    .setResponseCode(200)
                    .setBody(SEARCH_RESULTS)

                path == "/teachers/35757.ics" -> MockResponse()
                    .setResponseCode(200)
                    .setBody(TEACHER_ICS)

                path == "/teachers/35757" -> MockResponse()
                    .setResponseCode(200)
                    .setBody(teacherPage(id = "35757", shortName = "Петров Г.К."))

                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun teacherPage(id: String, shortName: String): String = """
        <html><body>
          <div class="breadcrumbs"><a href="/faculties">Расписание</a> &rarr; <span>$shortName</span></div>
          <h1>${if (id == "35144") "Жидова Любовь Александровна" else "Петров Глеб Константинович"}, доцент</h1>
          <ul class="weeks">
            <li class="tile day_color_theoretical current-week">
              <a href="/teachers/$id/weeks/847"><div>9 нечётная</div><div>с 26 октября 2026</div></a>
            </li>
            <li class="tile day_color_theoretical">
              <a href="/teachers/$id/weeks/848"><div>10 чётная</div><div>с 2 ноября 2026</div></a>
            </li>
          </ul>
        </body></html>
    """.trimIndent()

    private class FakeTeacherCacheDao : TeacherScheduleCacheDao {

        private val entities = mutableMapOf<String, TeacherScheduleCacheEntity>()

        override suspend fun get(teacherKey: String): TeacherScheduleCacheEntity? = entities[teacherKey]

        override suspend fun updatedAt(teacherKey: String): Long? = entities[teacherKey]?.updatedAt

        override suspend fun upsert(entity: TeacherScheduleCacheEntity) {
            entities[entity.teacherKey] = entity
        }

        override suspend fun delete(teacherKey: String) {
            entities.remove(teacherKey)
        }

        override suspend fun deleteAll() {
            entities.clear()
        }
    }

    private companion object {
        val SEARCH_RESULTS = """
            <html><body>
              <h1>Поиск: «Петров»</h1>
              <ul class="list-inline">
                <li><h2><a href="/teachers/35757">Петров Глеб Константинович</a></h2></li>
                <li><h2><a href="/teachers/35399">Петров Юрий Аркадьевич</a></h2></li>
              </ul>
            </body></html>
        """.trimIndent()

        val TEACHER_ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20261026T085000
            DTEND;TZID=Asia/Novosibirsk:20261026T102500
            SUMMARY:История России
            DESCRIPTION:Лекция\, 716-1\, 716-2
            LOCATION:улк 808
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20261027T104000
            DTEND;TZID=Asia/Novosibirsk:20261027T121500
            SUMMARY:Информатика
            DESCRIPTION:Практика\, 716-1
            LOCATION:улк 311
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }
}
