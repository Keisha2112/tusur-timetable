package com.keisha.tusur.timetable.data.update

import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.update.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

/**
 * Последний релиз приложения из GitHub Releases.
 *
 * Клиент только достаёт и разбирает ответ: сравнение с установленной версией
 * делает репозиторий, здесь всегда возвращается то, что опубликовано.
 */
class GitHubReleaseClient(
    private val client: OkHttpClient,
    private val apiUrl: String = RELEASE_API_URL,
) {

    suspend fun latestRelease(): AppUpdate = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", TusurClient.USER_AGENT)
            .header("Accept", ACCEPT_HEADER)
            .header("X-GitHub-Api-Version", API_VERSION)
            .build()

        val payload = try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> response.body?.string() ?: throw AppError.Parse()
                    response.code == HTTP_NOT_FOUND -> throw AppError.NotFound()
                    response.code >= HTTP_SERVER_ERROR -> throw AppError.Server(response.code)
                    // 403 — исчерпан лимит GitHub API; 429 — слишком много запросов.
                    else -> throw AppError.Server(response.code)
                }
            }
        } catch (e: IOException) {
            throw AppError.Network(e)
        }

        parseRelease(payload)
    }

    private fun parseRelease(payload: String): AppUpdate {
        val release = try {
            json.decodeFromString(GitHubReleaseDto.serializer(), payload)
        } catch (e: Exception) {
            throw AppError.Parse(e)
        }

        val tagName = release.tagName?.trim().orEmpty()
        val versionName = tagName.removePrefix("v").removePrefix("V")
        if (tagName.isEmpty() || versionName.isEmpty()) throw AppError.Parse()

        // Имя ассета — часть контракта релиза. Сначала ищем точное имя,
        // чтобы не взять debug-сборку или чужой APK при нескольких файлах.
        val candidates = release.assets.orEmpty().filter {
            it.isReleaseApk() && it.name?.contains("debug", ignoreCase = true) != true
        }
        val expectedName = "$APK_PREFIX$versionName$APK_SUFFIX"
        val asset = candidates.firstOrNull { it.name.equals(expectedName, ignoreCase = true) }
            ?: candidates.singleOrNull()
            ?: throw AppError.Parse()

        val downloadUrl = asset.browserDownloadUrl?.takeIf { it.isNotBlank() }
            ?: throw AppError.Parse()

        return AppUpdate(
            versionName = versionName,
            tagName = tagName,
            notes = release.body?.takeIf { it.isNotBlank() },
            publishedAt = release.publishedAt?.let { raw ->
                runCatching { Instant.parse(raw) }.getOrNull()
            },
            downloadUrl = downloadUrl,
            sizeBytes = asset.size ?: 0L,
            sha256 = parseDigest(asset.digest),
        )
    }

    /** `sha256:ABC...` -> `abc...`; пустой digest или чужой алгоритм — null. */
    private fun parseDigest(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val algorithm = if (':' in value) value.substringBefore(':') else SHA256
        val hash = if (':' in value) value.substringAfter(':') else value

        if (!algorithm.equals(SHA256, ignoreCase = true)) return null

        return hash.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    private fun GitHubAssetDto.isApk(): Boolean =
        name?.endsWith(APK_SUFFIX, ignoreCase = true) == true

    private fun GitHubAssetDto.isReleaseApk(): Boolean =
        name?.startsWith(APK_PREFIX, ignoreCase = true) == true && isApk()

    companion object {
        const val RELEASE_API_URL =
            "https://api.github.com/repos/Keisha2112/tusur-timetable/releases/latest"

        private const val ACCEPT_HEADER = "application/vnd.github+json"
        private const val API_VERSION = "2022-11-28"
        private const val APK_PREFIX = "tusur-timetable-"
        private const val APK_SUFFIX = ".apk"
        private const val SHA256 = "sha256"
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_SERVER_ERROR = 500

        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<GitHubAssetDto>? = null,
)

@Serializable
internal data class GitHubAssetDto(
    @SerialName("name") val name: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    @SerialName("digest") val digest: String? = null,
)
