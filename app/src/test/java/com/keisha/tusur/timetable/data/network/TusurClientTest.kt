package com.keisha.tusur.timetable.data.network

import com.keisha.tusur.timetable.domain.model.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TusurClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TusurClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TusurClient(
            OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `successful response returns body etag and user agent`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody("payload"),
        )

        val result = client.get(server.url("/faculties").toString())

        assertFalse(result.notModified)
        assertEquals(200, result.statusCode)
        assertEquals("payload", result.body)
        assertEquals("\"v1\"", result.etag)

        val request = server.takeRequest()
        assertEquals(TusurClient.USER_AGENT, request.getHeader("User-Agent"))
        assertEquals("ru", request.getHeader("Accept-Language"))
        assertNull(request.getHeader("If-None-Match"))
    }

    @Test
    fun `body is decoded as utf-8`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<p>Расписание ТУСУР</p>"),
        )

        val result = client.get(server.url("/faculties/fb").toString())

        assertEquals("<p>Расписание ТУСУР</p>", result.body)
    }

    @Test
    fun `default client uses 30 second timeouts`() {
        val defaults = TusurClient.defaultClient()

        assertEquals(30_000, defaults.connectTimeoutMillis)
        assertEquals(30_000, defaults.readTimeoutMillis)
        assertEquals(30_000, defaults.callTimeoutMillis)
    }

    @Test
    fun `not modified response sends if-none-match and has no body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody("first"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(304)
                .setHeader("ETag", "\"v1\""),
        )

        client.get(server.url("/faculties").toString())
        val result = client.get(server.url("/faculties").toString(), etag = "\"v1\"")

        assertTrue(result.notModified)
        assertNull(result.body)
        assertEquals(304, result.statusCode)
        assertEquals("\"v1\"", result.etag)

        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("\"v1\"", second.getHeader("If-None-Match"))
        assertEquals("ru", second.getHeader("Accept-Language"))
    }

    @Test
    fun `not modified without etag header keeps previous etag`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304))

        val result = client.get(server.url("/faculties").toString(), etag = "\"cached\"")

        assertTrue(result.notModified)
        assertEquals("\"cached\"", result.etag)
    }

    @Test
    fun `not found maps to AppError NotFound`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertThrows(AppError.NotFound::class.java) {
            runBlocking { client.get(server.url("/faculties/fb/groups/none").toString()) }
        }
    }

    @Test
    fun `server error maps to AppError Server with code`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = assertThrows(AppError.Server::class.java) {
            runBlocking { client.get(server.url("/faculties").toString()) }
        }
        assertEquals(500, error.code)
    }

    @Test
    fun `connection failure maps to AppError Network`() {
        val url = server.url("/faculties").toString()
        server.shutdown()

        assertThrows(AppError.Network::class.java) {
            runBlocking { client.get(url) }
        }
    }

    @Test
    fun `read timeout maps to AppError Network`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = TusurClient(
            OkHttpClient.Builder()
                .readTimeout(500, TimeUnit.MILLISECONDS)
                .callTimeout(2, TimeUnit.SECONDS)
                .build(),
        )

        assertThrows(AppError.Network::class.java) {
            runBlocking { impatient.get(server.url("/faculties").toString()) }
        }
    }
}
