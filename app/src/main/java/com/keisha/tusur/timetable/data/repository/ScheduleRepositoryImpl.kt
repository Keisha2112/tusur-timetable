package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec
import com.keisha.tusur.timetable.data.local.ScheduleCacheDao
import com.keisha.tusur.timetable.data.local.ScheduleCacheEntity
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * Расписание группы: JSON-кэш в Room плюс обновление из `.ics` и HTML-страницы недель.
 *
 * - TTL считается по `checkedAt`: 15 минут, пока в кэше есть текущая неделя, иначе 6 часов.
 *   Занятия текущей недели могли поменять (перенос, замена), а прошлый семестр — уже нет.
 * - Страница недель запрашивается только если её нет в кэше или ей больше 7 дней:
 *   список недель меняется раз в семестр, а страница генерируется несколько секунд.
 * - Оба запроса уходят с `If-None-Match`. Ответ 304 не перепарсивается: из кэша берутся
 *   готовые занятия, обновляется только время проверки.
 * - [mutex] — single-flight: второй вызов ждёт первый и забирает результат из кэша,
 *   вместо того чтобы тянуть тот же `.ics` параллельно.
 */
class ScheduleRepositoryImpl(
    private val dao: ScheduleCacheDao,
    private val client: TusurClient,
    private val weekListParser: WeekListParser,
    private val icsParser: IcsParser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScheduleRepository {

    private val mutex = Mutex()

    override suspend fun cached(groupSlug: String): GroupSchedule? =
        withContext(ioDispatcher) { readCache(groupSlug)?.schedule }

    override suspend fun lastUpdated(groupSlug: String): Instant? =
        withContext(ioDispatcher) {
            dao.updatedAt(groupSlug)?.let { Instant.ofEpochMilli(it) }
        }

    override suspend fun load(group: Group, forceRefresh: Boolean): ScheduleLoadResult =
        withContext(ioDispatcher) {
            val startedAt = System.currentTimeMillis()
            val cached = readCache(group.slug)

            // Свежий кэш отдаём сразу, без сети: экран открывается мгновенно.
            if (!forceRefresh && cached != null && isFresh(cached)) {
                return@withContext fromCache(cached)
            }

            mutex.withLock {
                // Пока ждали блокировку, соседний вызов мог уже обновить кэш.
                val current = readCache(group.slug)
                if (current != null) {
                    val refreshedByAnotherCall = current.entity.checkedAt >= startedAt
                    if (refreshedByAnotherCall || (!forceRefresh && isFresh(current))) {
                        return@withLock fromCache(current)
                    }
                }
                loadFromNetwork(group, current)
            }
        }

    override suspend fun clearCache() {
        withContext(ioDispatcher) { dao.deleteAll() }
    }

    private suspend fun loadFromNetwork(
        group: Group,
        cached: CachedSchedule?,
    ): ScheduleLoadResult =
        try {
            val requestWeeks = cached == null || !weeksAreFresh(cached)

            // `.ics` и страница недель независимы, поэтому идут параллельно.
            val (icsResponse, weeksResponse) = coroutineScope {
                val ics = async {
                    client.get(
                        url = Urls.groupIcs(group.facultyCode, group.slug),
                        etag = cached?.entity?.icsEtag,
                    )
                }
                val weeks = if (requestWeeks) {
                    async {
                        client.get(
                            url = Urls.group(group.facultyCode, group.slug),
                            etag = cached?.entity?.weeksEtag,
                        )
                    }
                } else {
                    null
                }
                ics.await() to weeks?.await()
            }

            // 304 — содержимое не изменилось, повторно разбирать нечего.
            val lessons = if (icsResponse.notModified) {
                // 304 без кэша возможен только при гонке с очисткой — считаем это сбоем.
                cached?.schedule?.lessons ?: throw AppError.Parse()
            } else {
                parse { icsParser.parse(icsResponse.body.orEmpty()) }
            }

            val weeks = when {
                weeksResponse == null -> cached?.schedule?.weeks.orEmpty()
                weeksResponse.notModified -> cached?.schedule?.weeks ?: throw AppError.Parse()
                else -> {
                    val parsed = parse { weekListParser.parse(weeksResponse.body.orEmpty()) }
                    // Пустой список недель при наличии кэша — вероятный сбой публикации,
                    // старые недели полезнее пустого экрана.
                    if (parsed.isEmpty()) cached?.schedule?.weeks.orEmpty() else parsed
                }
            }

            val now = Instant.now()
            val dataChanged = !icsResponse.notModified || weeksResponse?.notModified == false
            // `updatedAt` — время последнего изменения данных, `checkedAt` — последней проверки.
            // После 304 данные те же, поэтому время обновления не двигаем.
            val updatedAt = if (dataChanged || cached == null) {
                now
            } else {
                Instant.ofEpochMilli(cached.entity.updatedAt)
            }

            val schedule = GroupSchedule(
                group = group,
                weeks = weeks,
                lessons = lessons,
                fetchedAt = updatedAt,
            )
            dao.upsert(
                ScheduleCacheEntity(
                    groupSlug = group.slug,
                    payload = ScheduleCacheCodec.encode(schedule),
                    // Сервер может не повторить ETag — прежний не теряем.
                    icsEtag = icsResponse.etag ?: cached?.entity?.icsEtag,
                    weeksEtag = weeksResponse?.etag ?: cached?.entity?.weeksEtag,
                    updatedAt = updatedAt.toEpochMilli(),
                    checkedAt = now.toEpochMilli(),
                ),
            )

            ScheduleLoadResult(
                schedule = schedule,
                updatedAt = updatedAt,
                isFromCache = false,
                isOffline = false,
                error = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.toAppError()
            // Кэш при ошибке не затираем: старые занятия лучше пустого экрана.
            if (cached != null) {
                fromCache(cached, isOffline = error is AppError.Network, error = error)
            } else {
                ScheduleLoadResult(
                    schedule = null,
                    updatedAt = null,
                    isFromCache = false,
                    isOffline = error is AppError.Network,
                    error = error,
                )
            }
        }

    private suspend fun readCache(groupSlug: String): CachedSchedule? {
        val entity = dao.get(groupSlug) ?: return null
        val schedule = ScheduleCacheCodec.decode(entity.payload)
        if (schedule == null) {
            // Битый JSON (например, после смены модели) — кэша нет, строку убираем,
            // чтобы не разбирать её при каждом запуске.
            dao.delete(groupSlug)
            return null
        }
        return CachedSchedule(entity = entity, schedule = schedule)
    }

    private fun isFresh(cached: CachedSchedule): Boolean {
        val age = System.currentTimeMillis() - cached.entity.checkedAt
        if (age < 0) return true // часы устройства перевели назад — не считаем кэш протухшим

        val ttl = if (ScheduleTime.currentWeek(cached.schedule.weeks) != null) {
            TTL_CURRENT_WEEK_MS
        } else {
            TTL_DEFAULT_MS
        }
        return age < ttl
    }

    /**
     * Страница недель живёт 7 дней. Отсчёт идёт от `updatedAt`, но если сегодняшняя дата
     * не попадает ни в одну известную неделю, список заведомо устарел (начался новый семестр)
     * и запрашивается заново, не дожидаясь конца TTL.
     */
    private fun weeksAreFresh(cached: CachedSchedule): Boolean {
        val weeks = cached.schedule.weeks
        if (weeks.isEmpty()) return false
        if (ScheduleTime.findWeek(weeks, ScheduleTime.today()) == null) return false

        val age = System.currentTimeMillis() - cached.entity.updatedAt
        return age >= 0 && age < WEEKS_TTL_MS
    }

    private fun fromCache(
        cached: CachedSchedule,
        isOffline: Boolean = false,
        error: AppError? = null,
    ): ScheduleLoadResult = ScheduleLoadResult(
        schedule = cached.schedule,
        updatedAt = Instant.ofEpochMilli(cached.entity.updatedAt),
        isFromCache = true,
        isOffline = isOffline,
        error = error,
    )

    private fun Throwable.toAppError(): AppError = when (this) {
        is AppError -> this
        is IOException -> AppError.Network(this)
        else -> AppError.Unknown(this)
    }

    /** Ошибки парсеров приводим к [AppError.Parse], остальные не трогаем. */
    private inline fun <T> parse(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            throw AppError.Parse(e)
        }

    private data class CachedSchedule(
        val entity: ScheduleCacheEntity,
        val schedule: GroupSchedule,
    )

    private companion object {
        const val TTL_CURRENT_WEEK_MS = 15L * 60 * 1000
        const val TTL_DEFAULT_MS = 6L * 60 * 60 * 1000
        const val WEEKS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
