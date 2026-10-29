package com.keisha.tusur.timetable.data.update

import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.domain.model.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class GitHubReleaseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GitHubReleaseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubReleaseClient(
            client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
            apiUrl = server.url("/releases/latest").toString(),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `release is parsed with matching apk and digest`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(RELEASE_JSON),
        )

        val update = client.latestRelease()

        assertEquals("1.2.0", update.versionName)
        assertEquals("v1.2.0", update.tagName)
        assertEquals("Что нового", update.notes)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), update.publishedAt)
        assertEquals(
            "https://github.com/Keisha2112/tusur-timetable/releases/download/v1.2.0/tusur-timetable-1.2.0.apk",
            update.downloadUrl,
        )
        assertEquals(12_345_678L, update.sizeBytes)
        assertEquals("abcdef0123456789", update.sha256)
    }

    @Test
    fun `request carries github headers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(RELEASE_JSON))

        client.latestRelease()

        val request = server.takeRequest()
        assertEquals(TusurClient.USER_AGENT, request.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("/releases/latest", request.path)
    }

    @Test
    fun `release without apk maps to AppError Parse`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "tag_name": "v1.2.0",
                      "assets": [
                        {"name": "tusur-timetable-1.2.0.aab", "browser_download_url": "https://example.com/a.aab"},
                        {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        assertThrows(AppError.Parse::class.java) {
            runBlocking { client.latestRelease() }
        }
    }

    @Test
    fun `server error maps to AppError Server with code`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = assertThrows(AppError.Server::class.java) {
            runBlocking { client.latestRelease() }
        }
        assertEquals(500, error.code)
    }

    @Test
    fun `broken json maps to AppError Parse`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"tag_name\": \"v1.2.0\","),
        )

        assertThrows(AppError.Parse::class.java) {
            runBlocking { client.latestRelease() }
        }
    }

    @Test
    fun `release without notes and digest has nulls`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "tag_name": "1.3.0",
                      "body": "   ",
                      "published_at": "not-a-date",
                      "assets": [
                        {"name": "tusur-timetable-1.3.0.apk", "browser_download_url": "https://example.com/tusur-timetable-1.3.0.apk"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val update = client.latestRelease()

        assertEquals("1.3.0", update.versionName)
        assertNull(update.notes)
        assertNull(update.publishedAt)
        assertNull(update.sha256)
        assertEquals(0L, update.sizeBytes)
        assertEquals("https://example.com/tusur-timetable-1.3.0.apk", update.downloadUrl)
    }

    private companion object {
        const val RELEASE_JSON = """
            {
              "tag_name": "v1.2.0",
              "name": "ТУСУР Расписание 1.2.0",
              "body": "Что нового",
              "published_at": "2026-10-01T10:00:00Z",
              "assets": [
                {
                  "name": "debug.apk",
                  "size": 999,
                  "browser_download_url": "https://example.com/debug.apk"
                },
                {
                  "name": "tusur-timetable-1.2.0.apk",
                  "size": 12345678,
                  "browser_download_url": "https://github.com/Keisha2112/tusur-timetable/releases/download/v1.2.0/tusur-timetable-1.2.0.apk",
                  "digest": "sha256:ABCDEF0123456789"
                },
                {
                  "name": "tusur-timetable-1.2.0.aab",
                  "size": 777,
                  "browser_download_url": "https://example.com/bundle.aab"
                }
              ]
            }
        """
    }
}
