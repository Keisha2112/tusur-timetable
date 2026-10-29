package com.keisha.tusur.timetable.data.update

import android.content.Context
import android.util.Log
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.update.AppUpdate
import com.keisha.tusur.timetable.domain.update.DownloadProgress
import com.keisha.tusur.timetable.domain.update.DownloadedApk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Скачивание APK обновления с GitHub releases.
 *
 * - Качаем только по `https`: ссылка из ответа API не должна уводить на `http`.
 * - Файл сначала пишется в `<имя>.part` в кэше, проверяется и только потом
 *   публикуется под окончательным именем — недокачанный APK не попадёт в установку.
 * - SHA-256 считается по ходу записи, отдельного прохода по файлу нет.
 * - Прогресс отдаётся не чаще, чем меняется целый процент или раз в 100 мс:
 *   иначе на быстрой сети UI захлёбывается обновлениями. Колбэк вызывается
 *   на [ioDispatcher].
 */
class ApkDownloader(
    private val client: OkHttpClient,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun download(
        update: AppUpdate,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadedApk = withContext(ioDispatcher) {
        val url = update.downloadUrl.toHttpUrlOrNull() ?: throw AppError.Parse()
        if (!url.isHttps) throw AppError.Parse()

        val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val target = File(directory, fileName(update.versionName))
        val part = File(directory, target.name + PART_SUFFIX)
        part.delete()

        try {
            val sha256 = downloadTo(part, url, update, onProgress)
            verify(part, update, sha256)
            publish(part, target)
            DownloadedApk(target, update)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: AppError) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            throw AppError.Network(e)
        } catch (e: Exception) {
            part.delete()
            throw AppError.Unknown(e)
        }
    }

    /** Качает тело ответа в [part] и возвращает SHA-256 содержимого в hex. */
    private suspend fun downloadTo(
        part: File,
        url: HttpUrl,
        update: AppUpdate,
        onProgress: (DownloadProgress) -> Unit,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", TusurClient.USER_AGENT)
            .header("Accept", "application/vnd.android.package-archive")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            Log.w(TAG, "APK download failed: ${e.message}", e)
            throw AppError.Network(e)
        }

        response.use { answer ->
            // Редирект GitHub уводит на CDN. Проверяем и схему, и хост:
            // ссылка из API не должна уводить загрузку на произвольный сервер.
            if (!answer.request.url.isHttps || answer.request.url.host !in ALLOWED_HOSTS) {
                throw AppError.Parse()
            }

            when {
                answer.code == HTTP_NOT_FOUND -> throw AppError.NotFound()
                answer.code >= 500 -> throw AppError.Server(answer.code)
                !answer.isSuccessful -> throw AppError.Unknown()
            }

            val body = answer.body ?: throw AppError.Unknown()
            val declaredBytes = body.contentLength()
            val totalBytes = when {
                declaredBytes > 0 -> declaredBytes
                update.sizeBytes > 0 -> update.sizeBytes
                else -> null
            }

            val digest = MessageDigest.getInstance(SHA_256)
            val coroutine = currentCoroutineContext()
            var bytesRead = 0L
            var lastPercent = -1
            var lastNotifiedNs = 0L

            body.byteStream().use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutine.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break

                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesRead += read

                        val percent = totalBytes?.let { (bytesRead * 100 / it).toInt() } ?: -1
                        val now = System.nanoTime()
                        if (percent != lastPercent || now - lastNotifiedNs >= PROGRESS_INTERVAL_NS) {
                            lastPercent = percent
                            lastNotifiedNs = now
                            onProgress(DownloadProgress(bytesRead, totalBytes))
                        }
                    }
                    output.flush()
                }
            }

            onProgress(DownloadProgress(bytesRead, totalBytes))
            return digest.digest().toHex()
        }
    }

    private fun verify(file: File, update: AppUpdate, actualSha256: String) {
        val expectedSha256 = update.sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (expectedSha256 != null && expectedSha256 != actualSha256) {
            throw AppError.InvalidUpdate()
        }
        if (update.sizeBytes > 0 && file.length() != update.sizeBytes) {
            throw AppError.InvalidUpdate()
        }
    }

    /** Публикует готовый файл: переименование в пределах кэша атомарно. */
    private fun publish(part: File, target: File) {
        target.delete()
        if (part.renameTo(target)) return

        part.copyTo(target, overwrite = true)
        part.delete()
    }

    private fun fileName(versionName: String): String {
        val safe = versionName.replace(UNSAFE_CHARS, "_").ifBlank { DEFAULT_NAME }
        return safe + APK_SUFFIX
    }

    private fun ByteArray.toHex(): String = buildString(this@toHex.size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    private companion object {
        const val TAG = "ApkDownloader"
        const val CACHE_DIRECTORY = "updates"
        const val PART_SUFFIX = ".part"
        const val APK_SUFFIX = ".apk"
        const val DEFAULT_NAME = "update"

        const val BUFFER_SIZE = 8 * 1024
        const val PROGRESS_INTERVAL_NS = 100_000_000L

        const val HTTP_NOT_FOUND = 404
        const val SHA_256 = "SHA-256"

        const val HEX_DIGITS = "0123456789abcdef"
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

        val ALLOWED_HOSTS = setOf(
            "github.com",
            "api.github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )
    }
}
