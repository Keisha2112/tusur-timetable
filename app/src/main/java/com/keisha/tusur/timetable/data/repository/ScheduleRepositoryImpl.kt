package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec
import com.keisha.tusur.timetable.data.local.ScheduleCacheDao
import com.keisha.tusur.timetable.data.local.ScheduleCacheEntity
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * Расписание группы: JSON-кэш в Room плюс обновление из `.ics` и HTML-страницы недель.
 *
 * - TTL считается по `checkedAt`: 15 минут, пока в кэше есть занятия текущей недели,
 *   минута для пустого расписания и 6 часов, когда текущей недели в списке уже нет.
 *   Пустое расписание нужно перепроверять часто: сайт мог опубликовать его только что.
 * - Страница недель запрашивается только если её нет в кэше или ей больше 7 дней:
 *   список недель меняется раз в семестр, а страница генерируется несколько секунд.
 *   При ручном обновлении страница недель запрашивается принудительно.
 * - `.ics` не содержит будущие недели. Недостающие недели догружаются из HTML-таблицы
 *   по `?week_id=` и сохраняются в кэше вместе с отметкой [GroupSchedule.checkedWeeks].
 * - Оба запроса уходят с `If-None-Match`. Ответ 304 не перепарсивается: из кэша берутся
 *   готовые занятия, обновляется только время проверки. Для пустого кэша ETag не
 *   отправляется, иначе 304 мог бы навсегда закрепить пустой ответ.
 * - [mutex] — single-flight: второй вызов ждёт первый и забирает результат из кэша,
 *   вместо того чтобы тянуть тот же `.ics` параллельно.
 */
class ScheduleRepositoryImpl(
    private val dao: ScheduleCacheDao,
    private val client: TusurClient,
    private val weekListParser: WeekListParser,
    private val icsParser: IcsParser,
    private val weekScheduleParser: WeekScheduleParser = WeekScheduleParser(),
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
                    // Строго больше: запись в ту же миллисекунду — это наш же прошлый
                    // вызов, а не параллельное обновление.
                    val refreshedByAnotherCall = current.entity.checkedAt > startedAt
                    if (refreshedByAnotherCall || (!forceRefresh && isFresh(current))) {
                        return@withLock fromCache(current)
                    }
                }
                loadFromNetwork(group, current, forceRefresh)
            }
        }

    override suspend fun loadWeek(
        group: Group,
        week: Week,
        forceRefresh: Boolean,
    ): ScheduleLoadResult = withContext(ioDispatcher) {
        val cached = readCache(group.slug)
        if (!forceRefresh && cached != null && weekIsFresh(cached, week)) {
            return@withContext fromCache(cached)
        }

        mutex.withLock {
            val current = readCache(group.slug)
            if (current != null && !forceRefresh && weekIsFresh(current, week)) {
                return@withLock fromCache(current)
            }
            loadWeekFromNetwork(group, week, current)
        }
    }

    override suspend fun loadWeeks(
        group: Group,
        weeks: List<Week>,
        forceRefresh: Boolean,
    ): ScheduleLoadResult = withContext(ioDispatcher) {
        val targets = weeks.distinctBy { it.id }
        if (targets.isEmpty()) {
            val cached = readCache(group.slug)
            return@withContext if (cached != null) {
                fromCache(cached)
            } else {
                ScheduleLoadResult(null, null, false, false, AppError.Parse())
            }
        }

        mutex.withLock {
            val current = readCache(group.slug)
                ?: return@withLock ScheduleLoadResult(null, null, false, false, AppError.Parse())

            val toLoad = targets.filter { week -> forceRefresh || !weekIsFresh(current, week) }
            if (toLoad.isEmpty()) return@withLock fromCache(current)

            // Ручное обновление не должно растягиваться на минуты. Четыре
            // параллельных запроса заметно быстрее, но не создают лавину на сайте.
            val fetched = coroutineScope {
                val semaphore = Semaphore(MAX_PARALLEL_WEEK_REQUESTS)
                toLoad.map { week ->
                    async {
                        semaphore.withPermit {
                            try {
                                WeekFetch(
                                    week = week,
                                    html = client.get(
                                        Urls.groupWeek(group.facultyCode, group.slug, week.id),
                                    ).body.orEmpty(),
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                WeekFetch(week = week, error = e.toAppError())
                            }
                        }
                    }
                }.awaitAll()
            }

            val now = Instant.now()
            var lessons = current.schedule.lessons
            val checkedWeeks = current.schedule.checkedWeeks.toMutableMap()
            var loadedCount = 0
            var firstError: AppError? = null

            for (item in fetched) {
                if (item.html == null) {
                    if (firstError == null) firstError = item.error
                    continue
                }

                val parsed = try {
                    weekScheduleParser.parse(item.html, item.week)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstError == null) firstError = e.toAppError()
                    continue
                }

                if (parsed.lessons.isEmpty() &&
                    (!parsed.hasSchedule || item.html.contains("lesson-cell")) &&
                    !isExplicitlyEmpty(item.html)
                ) {
                    if (firstError == null) firstError = AppError.Parse()
                    continue
                }

                val oldWeekLessons = lessons.filter { it.date in weekDateRange(item.week) }
                // Пустая HTML-страница не должна стирать занятия, которые уже
                // пришли из ICS: сайт мог временно отдать неполную страницу.
                val newWeekLessons = if (parsed.lessons.isEmpty() && oldWeekLessons.isNotEmpty()) {
                    oldWeekLessons
                } else {
                    parsed.lessons
                }
                lessons = lessons
                    .filterNot { it.date in weekDateRange(item.week) }
                    .plus(newWeekLessons)
                checkedWeeks[item.week.id] = now.toEpochMilli()
                loadedCount++
            }

            if (loadedCount == 0) {
                return@withLock fromCache(
                    cached = current,
                    isOffline = firstError is AppError.Network,
                    error = firstError,
                )
            }

            val newLessons = lessons.sortedWith(
                compareBy<Lesson> { it.date }.thenBy { it.start }.thenBy { it.discipline },
            )
            val newCheckedWeeks = checkedWeeks.filterKeys { id ->
                current.schedule.weeks.any { it.id == id }
            }
            val dataChanged = newLessons != current.schedule.lessons
            val updatedAt = if (dataChanged) {
                now
            } else {
                Instant.ofEpochMilli(current.entity.updatedAt)
            }
            val schedule = current.schedule.copy(
                lessons = newLessons,
                fetchedAt = updatedAt,
                checkedWeeks = newCheckedWeeks,
            )
            dao.upsert(
                ScheduleCacheEntity(
                    groupSlug = group.slug,
                    payload = ScheduleCacheCodec.encode(schedule),
                    icsEtag = current.entity.icsEtag,
                    weeksEtag = current.entity.weeksEtag,
                    updatedAt = updatedAt.toEpochMilli(),
                    checkedAt = now.toEpochMilli(),
                ),
            )

            ScheduleLoadResult(
                schedule = schedule,
                updatedAt = updatedAt,
                isFromCache = false,
                isOffline = firstError is AppError.Network,
                error = firstError,
            )
        }
    }

    override suspend fun clearCache() {
        withContext(ioDispatcher) { dao.deleteAll() }
    }

    private suspend fun loadFromNetwork(
        group: Group,
        cached: CachedSchedule?,
        forceRefresh: Boolean,
    ): ScheduleLoadResult =
        try {
            val requestWeeks = forceRefresh || cached == null || !weeksAreFresh(cached)

            // `.ics` и страница недель независимы, поэтому идут параллельно.
            // Для пустого кэша ETag не отправляем: иначе сервер может закрепить
            // ответ, полученный до публикации расписания, и 304 будет вечно
            // возвращать пустой список.
            val icsEtag = cached?.entity?.icsEtag
                ?.takeIf { cached?.let(::hasIcsLessons) == true }
            val weeksEtag = cached?.entity?.weeksEtag
                ?.takeIf { cached?.schedule?.weeks?.isNotEmpty() == true }

            val (icsResponse, weeksResponse) = coroutineScope {
                val ics = async {
                    client.get(
                        url = Urls.groupIcs(group.facultyCode, group.slug),
                        etag = icsEtag,
                    )
                }
                val weeks = if (requestWeeks) {
                    async {
                        client.get(
                            url = Urls.group(group.facultyCode, group.slug),
                            etag = weeksEtag,
                        )
                    }
                } else {
                    null
                }
                ics.await() to weeks?.await()
            }

            // 304 — содержимое не изменилось, повторно разбирать нечего.
            val icsLessons = if (icsResponse.notModified) {
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

            // Недели, догруженные из HTML, важнее ICS только пока ICS их не знает.
            // Если неделя уже есть в ICS, снимаем HTML-отметку: источником
            // становится более свежий экспорт, а не кэшированная таблица.
            val icsWeekIds = if (icsResponse.notModified) {
                emptySet()
            } else {
                icsLessons.mapNotNull { lesson ->
                    ScheduleTime.findWeek(weeks, lesson.date)?.id
                }.toSet()
            }
            val checkedWeeks = cached?.schedule?.checkedWeeks.orEmpty()
                .filterKeys { id -> weeks.any { it.id == id } && id !in icsWeekIds }
            // 304 означает, что в ICS ничего не изменилось: кэш уже правильный,
            // повторное слияние только может потерять HTML-недели.
            val lessons = if (icsResponse.notModified && cached != null) {
                cached.schedule.lessons
            } else {
                mergeWithLoadedWeeks(
                    icsLessons = icsLessons,
                    cached = cached?.schedule,
                    weeks = weeks,
                )
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
                checkedWeeks = checkedWeeks,
            )
            // 304 может не повторять ETag — тогда оставляем прежний. Ответ 200
            // без ETag означает, что старой метки больше нет: сохранять её
            // нельзя, иначе следующий запрос получит ложный 304.
            val storedIcsEtag = when {
                icsResponse.notModified -> icsResponse.etag ?: cached?.entity?.icsEtag
                icsLessons.isEmpty() -> null
                else -> icsResponse.etag
            }
            val storedWeeksEtag = when {
                weeksResponse == null -> cached?.entity?.weeksEtag
                weeksResponse.notModified -> weeksResponse.etag ?: cached?.entity?.weeksEtag
                else -> weeksResponse.etag
            }
            dao.upsert(
                ScheduleCacheEntity(
                    groupSlug = group.slug,
                    payload = ScheduleCacheCodec.encode(schedule),
                    icsEtag = storedIcsEtag,
                    weeksEtag = storedWeeksEtag,
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

    /**
     * Загружает HTML выбранной недели. Ответ сайта содержит и список недель,
     * поэтому заодно обновляем его: новая неделя может появиться раньше, чем
     * истечёт семидневный TTL страницы группы.
     */
    private suspend fun loadWeekFromNetwork(
        group: Group,
        week: Week,
        cached: CachedSchedule?,
    ): ScheduleLoadResult =
        try {
            val response = client.get(Urls.groupWeek(group.facultyCode, group.slug, week.id))
            val html = response.body.orEmpty()
            val parsedWeeks = parse { weekListParser.parse(html) }
            // Страница недели может отдать неполный список: сохраняем недели,
            // которых в ответе не оказалось, вместо полной замены.
            val weeks = mergeWeeks(
                cached = cached?.schedule?.weeks.orEmpty(),
                parsed = parsedWeeks,
                preferCached = true,
            )

            val parsed = parse { weekScheduleParser.parse(html, week) }
            if (parsed.lessons.isEmpty() &&
                (!parsed.hasSchedule || html.contains("lesson-cell")) &&
                !isExplicitlyEmpty(html)
            ) {
                // Страница не похожа ни на расписание, ни на плашку «не опубликовано»:
                // не затираем старые занятия случайным ответом.
                throw AppError.Parse()
            }

            val oldSchedule = cached?.schedule
            val oldLessons = oldSchedule?.lessons.orEmpty()
            val oldWeekLessons = oldLessons.filter { it.date in weekDateRange(week) }
            // Пустая страница не должна затирать занятия из ICS.
            val newWeekLessons = if (parsed.lessons.isEmpty() && oldWeekLessons.isNotEmpty()) {
                oldWeekLessons
            } else {
                parsed.lessons
            }
            val lessons = oldLessons
                .filterNot { it.date in weekDateRange(week) }
                .plus(newWeekLessons)
                .sortedWith(compareBy<Lesson> { it.date }.thenBy { it.start }.thenBy { it.discipline })

            val now = Instant.now()
            val checkedWeeks = oldSchedule?.checkedWeeks.orEmpty().toMutableMap().apply {
                put(week.id, now.toEpochMilli())
            }.filterKeys { id -> weeks.any { it.id == id } || id == week.id }

            val dataChanged = lessons != oldLessons || weeks != oldSchedule?.weeks.orEmpty()
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
                checkedWeeks = checkedWeeks,
            )
            dao.upsert(
                ScheduleCacheEntity(
                    groupSlug = group.slug,
                    payload = ScheduleCacheCodec.encode(schedule),
                    icsEtag = cached?.entity?.icsEtag,
                    weeksEtag = cached?.entity?.weeksEtag,
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

    private fun mergeWithLoadedWeeks(
        icsLessons: List<Lesson>,
        cached: GroupSchedule?,
        weeks: List<Week>,
    ): List<Lesson> {
        val loadedWeekIds = cached?.checkedWeeks.orEmpty().keys
        if (loadedWeekIds.isEmpty()) return icsLessons

        // HTML остаётся источником только для недель, которых нет в ICS.
        // Если ICS уже отдаёт эту неделю, он свежее: переносы и замены не
        // должны скрываться под кэшированной HTML-таблицей.
        val icsWeekIds = icsLessons.mapNotNull { lesson ->
            ScheduleTime.findWeek(weeks, lesson.date)?.id
        }.toSet()
        val htmlOnlyLessons = cached?.lessons.orEmpty().filter { lesson ->
            val weekId = ScheduleTime.findWeek(weeks, lesson.date)?.id
            weekId in loadedWeekIds && weekId !in icsWeekIds
        }
        return (icsLessons + htmlOnlyLessons)
            .sortedWith(compareBy<Lesson> { it.date }.thenBy { it.start }.thenBy { it.discipline })
    }

    /**
     * Есть ли в кэше занятия, которые пришли не из HTML-недели. Если всё
     * расписание загружено только из HTML, ETag пустого ICS отправлять нельзя.
     */
    private fun hasIcsLessons(cached: CachedSchedule): Boolean {
        val loadedWeekIds = cached.schedule.checkedWeeks.keys
        val weeks = cached.schedule.weeks
        return cached.schedule.lessons.any { lesson ->
            val weekId = ScheduleTime.findWeek(weeks, lesson.date)?.id
            weekId == null || weekId !in loadedWeekIds
        }
    }

    private fun mergeWeeks(
        cached: List<Week>,
        parsed: List<Week>,
        preferCached: Boolean = false,
    ): List<Week> {
        if (parsed.isEmpty()) return cached
        if (cached.isEmpty()) return parsed
        val cachedIds = cached.map { it.id }.toSet()
        return if (preferCached) {
            (cached + parsed.filterNot { it.id in cachedIds }).sortedBy { it.startDate }
        } else {
            val parsedIds = parsed.map { it.id }.toSet()
            (parsed + cached.filterNot { it.id in parsedIds }).sortedBy { it.startDate }
        }
    }

    private fun weekDateRange(week: Week): ClosedRange<java.time.LocalDate> =
        week.startDate..week.startDate.plusDays(6)

    private fun isExplicitlyEmpty(html: String): Boolean =
        html.contains("empty-schedule") ||
            html.contains("На эту неделю") ||
            html.contains("На данную неделю")

    /**
     * Отметка последней загрузки недели из HTML. ICS приходит отдельно, поэтому
     * проверяем именно неделю, а не общий `checkedAt` расписания.
     */
    private fun weekIsFresh(cached: CachedSchedule, week: Week): Boolean {
        val checkedAt = cached.schedule.checkedWeeks[week.id] ?: return false
        val age = System.currentTimeMillis() - checkedAt
        if (age < 0) return false
        return age < TTL_WEEK_MS
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
        // Часы перевели назад: кэш нельзя считать свежим, иначе он может остаться
        // «вечно актуальным» после публикации расписания.
        if (age < 0) return false

        val weeks = cached.schedule.weeks
        val lessons = cached.schedule.lessons
        val currentWeek = ScheduleTime.currentWeek(weeks)
        val currentWeekHasLessons = currentWeek != null && lessons.any { lesson ->
            lesson.date in weekDateRange(currentWeek)
        }
        val ttl = when {
            // Пустой ответ — это, скорее всего, ещё не опубликованное расписание.
            // Проверяем его часто, чтобы новая публикация появилась сама.
            lessons.isEmpty() || weeks.isEmpty() -> TTL_EMPTY_MS
            currentWeek == null -> TTL_DEFAULT_MS
            !currentWeekHasLessons -> TTL_EMPTY_MS
            else -> TTL_CURRENT_WEEK_MS
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

    private data class WeekFetch(
        val week: Week,
        val html: String? = null,
        val error: AppError? = null,
    )

    private companion object {
        const val TTL_CURRENT_WEEK_MS = 15L * 60 * 1000
        const val TTL_EMPTY_MS = 1L * 60 * 1000
        const val TTL_DEFAULT_MS = 6L * 60 * 60 * 1000
        const val TTL_WEEK_MS = 15L * 60 * 1000
        const val WEEKS_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_PARALLEL_WEEK_REQUESTS = 4
    }
}
