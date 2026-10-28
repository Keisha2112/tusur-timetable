package com.keisha.tusur.timetable.data.network

import android.util.Log
import com.keisha.tusur.timetable.domain.model.AppError
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ответ сайта: тело есть только для 2xx, при 304 приходит [notModified]. */
data class HttpResult(
    val body: String?,
    val etag: String?,
    val notModified: Boolean,
    val statusCode: Int,
    /**
     * URL после всех редиректов. Нужен поиску: единственное совпадение сайт
     * перенаправляет сразу на страницу преподавателя.
     */
    val finalUrl: String? = null,
)

/**
 * Тонкая обёртка над OkHttp: строки, ETag и ошибки слоя данных.
 * Асинхронный вызов OkHttp переведён в suspend; отмена корутины отменяет запрос.
 */
class TusurClient(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun get(url: String, etag: String? = null): HttpResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ru")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Запрос не удался: $url", e)
                    if (continuation.isActive) {
                        continuation.resumeWithException(AppError.Network(e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { it.toHttpResult(previousEtag = etag) }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: AppError) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(AppError.Network(e))
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(AppError.Unknown(e))
                    }
                }
            })
        }
    }

    private fun Response.toHttpResult(previousEtag: String?): HttpResult = when {
        code == HTTP_NOT_MODIFIED -> HttpResult(
            body = null,
            // Данные не менялись — актуален тот ETag, который мы отправили.
            etag = previousEtag,
            notModified = true,
            statusCode = code,
            finalUrl = request.url.toString(),
        )

        isSuccessful -> HttpResult(
            // Сайт отдаёт UTF-8; декодируем явно, не полагаясь на charset в заголовке.
            body = body?.bytes()?.toString(Charsets.UTF_8),
            etag = header("ETag"),
            notModified = false,
            statusCode = code,
            finalUrl = request.url.toString(),
        )

        code == HTTP_NOT_FOUND -> throw AppError.NotFound()
        code >= 500 -> throw AppError.Server(code)
        else -> throw AppError.Unknown()
    }

    companion object {
        const val USER_AGENT = "TusurTimetable/1.0 (Android; +https://timetable.tusur.ru)"

        private const val TAG = "TusurClient"
        private const val HTTP_NOT_MODIFIED = 304
        private const val HTTP_NOT_FOUND = 404

        fun defaultClient(): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)

            // Android 8–13: в системном хранилище нет корня GlobalSign R46.
            LegacyTls.trustManager()?.let { trustManager ->
                builder.sslSocketFactory(LegacyTls.sslContext(trustManager).socketFactory, trustManager)
            }

            return builder.build()
        }
    }
}
