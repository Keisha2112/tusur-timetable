package com.keisha.tusur.timetable.data.repository

import androidx.room.withTransaction
import com.keisha.tusur.timetable.data.local.AppDatabase
import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec
import com.keisha.tusur.timetable.data.local.ScheduleCacheDao
import com.keisha.tusur.timetable.data.local.ScheduleCacheEntity
import com.keisha.tusur.timetable.data.local.ScheduleNotificationEntity
import com.keisha.tusur.timetable.data.local.StoredSchedule
import com.keisha.tusur.timetable.data.local.StoredWeek
import com.keisha.tusur.timetable.data.network.HttpResult
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.domain.change.ComparedWeek
import com.keisha.tusur.timetable.domain.change.ScheduleChangeDetector
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ScheduleRepositoryImpl(
    private val dao: ScheduleCacheDao,
    private val client: TusurClient,
    private val weekListParser: WeekListParser,
    private val icsParser: IcsParser,
    private val weekScheduleParser: WeekScheduleParser = WeekScheduleParser(weekListParser),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val database: AppDatabase? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ScheduleRepository {
    private val mutex = Mutex()
    private val detector = ScheduleChangeDetector()

    override suspend fun cached(group: Group): GroupSchedule? =
        withContext(ioDispatcher) { read(group)?.display }

    override fun observe(group: Group): Flow<GroupSchedule?> =
        dao.observe(group.slug)
            .map { entity ->
                entity?.toCache()?.takeIf { it.data.group.facultyCode == group.facultyCode }?.display
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override suspend fun lastUpdated(group: Group): Instant? =
        withContext(ioDispatcher) { read(group)?.display?.fetchedAt }

    override suspend fun load(group: Group, forceRefresh: Boolean): ScheduleLoadResult =
        withContext(ioDispatcher) {
            val startedAt = clock.millis()
            if (!forceRefresh) {
                val cached = read(group)
                if (cached != null && isFresh(cached)) return@withContext result(cached.display)
            }
            mutex.withLock {
                val cache = read(group)
                if (cache != null && (cache.entity.checkedAt > startedAt ||
                        !forceRefresh && isFresh(cache))
                ) {
                    return@withLock result(cache.display)
                }
                val update = Update(group, cache)
                loadBasics(update, forceRefresh)
                commit(update)
            }
        }

    override suspend fun loadWeek(group: Group, week: Week, forceRefresh: Boolean): ScheduleLoadResult =
        loadWeeks(group, listOf(week), forceRefresh)

    override suspend fun loadWeeks(
        group: Group,
        weeks: List<Week>,
        forceRefresh: Boolean,
    ): ScheduleLoadResult = withContext(ioDispatcher) {
        mutex.withLock {
            val update = Update(group, read(group))
            val targets = weeks.distinctBy { it.id }.filter { week ->
                forceRefresh || update.data.htmlWeek(week)?.checkedAt?.let(::htmlIsFresh) != true
            }
            loadHtmlWeeks(update, targets)
            commit(update)
        }
    }

    override suspend fun refresh(
        group: Group,
        reason: ScheduleRefreshReason,
        selectedWeekId: Int?,
        monitoringGeneration: String?,
    ): ScheduleLoadResult = withContext(ioDispatcher) {
        val startedAt = clock.millis()
        mutex.withLock {
            val update = Update(group, read(group))
            val today = today()
            val html = when (reason) {
                ScheduleRefreshReason.MANUAL -> loadBasics(update, force = true)
                ScheduleRefreshReason.BACKGROUND -> {
                    if (needsWeekList(update.data, today)) loadWeekList(update) else null
                }
            }

            val targets = when (reason) {
                ScheduleRefreshReason.MANUAL -> {
                    val coveredStarts = update.data.display(0).lessons
                        .mapTo(hashSetOf()) { ScheduleTime.weekStart(it.date) }
                    update.data.weeks.filter {
                        !ScheduleTime.weekEnd(it.startDate).isBefore(today) || it.id == selectedWeekId ||
                            it.id in update.data.htmlWeeks || it.startDate !in coveredStarts
                    }
                }
                ScheduleRefreshReason.BACKGROUND -> {
                    val current = ScheduleTime.weekStart(today)
                    update.data.weeks.filter { it.startDate == current || it.startDate == current.plusWeeks(1) }
                }
            }
            loadHtmlWeeks(update, targets, html, startedAt)
            commit(update, monitoringGeneration, targets)
        }
    }

    override suspend fun clearCache() {
        withContext(ioDispatcher) {
            mutex.withLock {
                val db = database
                if (db == null) dao.deleteAll() else db.withTransaction {
                    dao.deleteAll()
                    db.scheduleNotificationDao().clear()
                }
            }
        }
    }

    /** ICS и список недель независимы. Частичный сбой не откатывает удавшийся запрос. */
    private suspend fun loadBasics(update: Update, force: Boolean): HttpResult? {
        val shouldFetchWeeks = force || needsWeekList(update.data, today())
        val (ics, weeks) = coroutineScope {
            val icsRequest = async {
                request {
                    client.get(
                        Urls.groupIcs(update.group.facultyCode, update.group.slug),
                        update.icsEtag.takeIf { update.data.icsLessons.isNotEmpty() },
                    )
                }
            }
            val weeksRequest = if (shouldFetchWeeks) async { requestWeekList(update) } else null
            icsRequest.await() to weeksRequest?.await()
        }
        ics.onSuccess { response ->
            request { applyIcs(update, response) }.onFailure { update.recordError(it) }
        }.onFailure { update.recordError(it) }
        return weeks?.fold(
            onSuccess = { applyWeekList(update, it) },
            onFailure = { update.recordError(it); null },
        )
    }

    private suspend fun requestWeekList(update: Update): Result<HttpResult> = request {
        client.get(
            Urls.group(update.group.facultyCode, update.group.slug),
            update.weeksEtag.takeIf { update.data.weekListCheckedAt > 0 && update.data.weeks.isNotEmpty() },
        )
    }

    private suspend fun loadWeekList(update: Update): HttpResult? = requestWeekList(update).fold(
        onSuccess = { applyWeekList(update, it) },
        onFailure = { update.recordError(it); null },
    )

    private fun applyWeekList(update: Update, response: HttpResult): HttpResult? {
        val parsed = if (response.notModified) update.data.weeks else weekListParser.parse(response.body.orEmpty())
        if (parsed.isEmpty()) {
            update.recordError(AppError.Parse())
            return null
        }
        update.data = update.data.copy(
            weeks = mergeWeeks(update.data.weeks, parsed),
            weekListCheckedAt = clock.millis(),
        )
        update.weeksEtag = response.etag
        update.receivedData = true
        return response.takeUnless { it.notModified }
    }

    private fun applyIcs(update: Update, response: HttpResult) {
        if (response.notModified) {
            if (update.data.icsLessons.isEmpty()) throw AppError.Parse()
        } else {
            val body = response.body.orEmpty().trim().removePrefix("\uFEFF")
            if (body.isNotBlank() && (!body.startsWith("BEGIN:VCALENDAR", ignoreCase = true) ||
                    !body.endsWith("END:VCALENDAR", ignoreCase = true))
            ) {
                throw AppError.Parse()
            }
            val lessons = icsParser.parse(body)
            val coveredStarts = lessons.mapTo(hashSetOf()) { ScheduleTime.weekStart(it.date) }
            val verifiedStarts = update.data.htmlWeeks.values.filter { it.lessons != null }
                .mapTo(hashSetOf()) { it.startDate }
            val fallback = (update.data.olderLessons + update.data.icsLessons).filter {
                val week = ScheduleTime.weekStart(it.date)
                week !in coveredStarts && week !in verifiedStarts
            }.distinct()
            update.data = update.data.copy(icsLessons = lessons, olderLessons = fallback)
        }
        update.icsCheckedAt = clock.millis()
        update.icsEtag = response.etag.takeIf { update.data.icsLessons.isNotEmpty() }
        update.receivedData = true
    }

    private suspend fun loadHtmlWeeks(
        update: Update,
        weeks: List<Week>,
        groupPage: HttpResult? = null,
        startedAt: Long? = null,
    ) {
        val groupUrl = Urls.group(update.group.facultyCode, update.group.slug)
        val selected = groupPage?.body?.let(weekListParser::selected)
        val semaphore = Semaphore(MAX_PARALLEL_WEEK_REQUESTS)
        val responses = coroutineScope {
            weeks.map { week ->
                async {
                    semaphore.withPermit {
                        val previous = update.data.htmlWeek(week)
                        val fetched = request {
                            when {
                                startedAt != null && previous != null && previous.checkedAt > startedAt ->
                                    WeekResponse(week, previous, emptyList())
                                else -> fetchWeek(
                                    update.group, groupUrl, week, previous,
                                    groupPage.takeIf { selected?.id == week.id && selected.startDate == week.startDate },
                                )
                            }
                        }
                        week to fetched
                    }
                }
            }.awaitAll()
        }

        for ((week, response) in responses) {
            response.onSuccess { fetched ->
                val data = update.data
                update.data = data.copy(
                    weeks = mergeWeeks(data.weeks, fetched.weeks + fetched.week),
                    htmlWeeks = data.htmlWeeks + (week.id to fetched.stored),
                    olderLessons = if (fetched.stored.lessons != null) {
                        data.olderLessons.filterNot { ScheduleTime.weekStart(it.date) == week.startDate }
                    } else data.olderLessons,
                )
                update.checked += week.id
                update.receivedData = true
            }.onFailure {
                update.recordError(it)
                update.failed += week.id
            }
        }
    }

    private suspend fun fetchWeek(
        group: Group,
        groupUrl: String,
        week: Week,
        previous: StoredWeek?,
        groupPage: HttpResult?,
    ): WeekResponse {
        val fromGroupPage = groupPage?.body?.let { weekScheduleParser.parse(it, week, groupUrl) }
            ?.takeIf { it.publication != null }

        val parsed: WeekScheduleParser.ParsedWeek
        val etag: String?
        if (fromGroupPage != null) {
            parsed = fromGroupPage
            // У страницы по умолчанию свой ETag, использовать его для week_id нельзя.
            etag = null
        } else {
            val response = client.get(Urls.groupWeek(group.facultyCode, group.slug, week.id), previous?.etag)
            if (response.notModified) {
                if (previous?.etag == null) throw AppError.Parse()
                return WeekResponse(week, previous.copy(checkedAt = clock.millis()), emptyList())
            }
            parsed = weekScheduleParser.parse(response.body.orEmpty(), week, groupUrl)
            etag = response.etag
        }

        val publication = parsed.publication ?: throw AppError.Parse()
        val holidays = previous?.holidays.orEmpty().filterNot { it.date in parsed.calendarDates } + parsed.holidays
        return WeekResponse(
            week = week,
            stored = StoredWeek(
                startDate = week.startDate,
                checkedAt = clock.millis(),
                etag = etag,
                publication = publication,
                lessons = if (publication == WeekPublication.PUBLISHED) parsed.lessons else previous?.lessons,
                holidays = holidays.sortedBy { it.date },
            ),
            weeks = parsed.weeks,
        )
    }

    private suspend fun commit(
        update: Update,
        generation: String? = null,
        targets: List<Week> = emptyList(),
    ): ScheduleLoadResult {
        val previous = update.cache
        if (!update.receivedData) return result(previous?.display, update.error)

        val now = clock.instant()
        val db = database
        val notice = if (generation != null && db != null) {
            val verified = update.checked.mapNotNull { id ->
                val week = update.data.htmlWeeks.getValue(id)
                if (week.publication == WeekPublication.PUBLISHED && week.lessons != null) {
                    id to ComparedWeek(week.startDate, week.checkedAt, week.lessons, week.holidays)
                } else null
            }.toMap()
            val prior = update.data.baseline
            val unavailable = targets.filter { week ->
                week.id in update.failed || week.id in prior?.weeks.orEmpty() && week.id !in verified
            }
            val comparison = detector.compare(generation, prior, verified, unavailable, now)
            update.data = update.data.copy(baseline = comparison.baseline)
            comparison.changes.takeIf { it.isNotEmpty() }?.let {
                ScheduleNotificationEntity(
                    id = UUID.randomUUID().toString(),
                    generation = generation,
                    payload = ScheduleCacheCodec.json.encodeToString(it),
                    createdAt = now.toEpochMilli(),
                )
            }
        } else null

        val oldDisplay = previous?.display
        val newDisplay = update.data.display(previous?.entity?.updatedAt ?: now.toEpochMilli())
        val updatedAt = if (oldDisplay == null ||
            oldDisplay.copy(checkedWeeks = emptyMap()) != newDisplay.copy(checkedWeeks = emptyMap())
        ) now.toEpochMilli() else previous.entity.updatedAt
        val entity = ScheduleCacheEntity(
            groupSlug = update.group.slug,
            payload = ScheduleCacheCodec.encodeStored(update.data),
            icsEtag = update.icsEtag,
            weeksEtag = update.weeksEtag,
            updatedAt = updatedAt,
            checkedAt = update.icsCheckedAt,
        )
        if (notice != null && db != null) {
            db.withTransaction {
                dao.upsert(entity)
                db.scheduleNotificationDao().insert(notice)
            }
        } else dao.upsert(entity)
        return result(newDisplay.copy(fetchedAt = Instant.ofEpochMilli(updatedAt)), update.error, fromCache = false)
    }

    private suspend fun read(group: Group): CachedSchedule? =
        dao.get(group.slug)?.toCache()?.takeIf { it.data.group.facultyCode == group.facultyCode }

    private fun ScheduleCacheEntity.toCache(): CachedSchedule? =
        ScheduleCacheCodec.decodeStored(payload)?.let { CachedSchedule(this, it) }

    private fun StoredSchedule.htmlWeek(week: Week): StoredWeek? =
        htmlWeeks[week.id]?.takeIf { it.startDate == week.startDate }

    private fun result(
        schedule: GroupSchedule?,
        error: AppError? = null,
        fromCache: Boolean = schedule != null,
    ): ScheduleLoadResult = ScheduleLoadResult(
        schedule = schedule,
        updatedAt = schedule?.fetchedAt,
        isFromCache = fromCache,
        isOffline = error is AppError.Network,
        error = error,
    )

    private fun mergeWeeks(old: List<Week>, latest: List<Week>): List<Week> =
        (latest + old).distinctBy { it.id }.sortedBy { it.startDate }

    private fun today(): LocalDate = clock.instant().atZone(ScheduleTime.ZONE).toLocalDate()

    private fun needsWeekList(data: StoredSchedule, today: LocalDate): Boolean =
        ScheduleTime.findWeek(data.weeks, today) == null ||
            clock.millis() - data.weekListCheckedAt !in 0 until WEEK_LIST_TTL

    private fun htmlIsFresh(checkedAt: Long): Boolean = clock.millis() - checkedAt in 0 until CURRENT_TTL

    private fun isFresh(cache: CachedSchedule): Boolean {
        val date = today()
        val schedule = cache.display
        val current = ScheduleTime.findWeek(schedule.weeks, date)
        val hasCurrentLessons = current != null &&
            schedule.lessons.any { ScheduleTime.weekStart(it.date) == current.startDate }
        val ttl = when {
            schedule.weeks.isEmpty() || cache.data.icsLessons.isEmpty() -> EMPTY_TTL
            current == null -> OTHER_TTL
            hasCurrentLessons -> CURRENT_TTL
            else -> EMPTY_TTL
        }
        return clock.millis() - cache.entity.checkedAt in 0 until ttl
    }

    private suspend fun <T> request(operation: suspend () -> T): Result<T> =
        try {
            Result.success(operation())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private data class CachedSchedule(val entity: ScheduleCacheEntity, val data: StoredSchedule) {
        val display: GroupSchedule by lazy { data.display(entity.updatedAt) }
    }

    private data class WeekResponse(val week: Week, val stored: StoredWeek, val weeks: List<Week>)

    private class Update(val group: Group, val cache: CachedSchedule?) {
        var data = cache?.data?.copy(group = group) ?: StoredSchedule.empty(group)
        var icsEtag = cache?.entity?.icsEtag
        var weeksEtag = cache?.entity?.weeksEtag
        var icsCheckedAt = cache?.entity?.checkedAt ?: 0
        var receivedData = false
        var error: AppError? = null
        val checked = mutableSetOf<Int>()
        val failed = mutableSetOf<Int>()

        fun recordError(cause: Throwable) {
            val candidate = when (cause) {
                is AppError -> cause
                is IOException -> AppError.Network(cause)
                else -> AppError.Parse(cause)
            }
            if (error == null || candidate is AppError.Network ||
                candidate is AppError.Server && error !is AppError.Network
            ) error = candidate
        }
    }

    private companion object {
        val CURRENT_TTL = Duration.ofMinutes(15).toMillis()
        val EMPTY_TTL = Duration.ofMinutes(1).toMillis()
        val OTHER_TTL = Duration.ofHours(6).toMillis()
        val WEEK_LIST_TTL = Duration.ofDays(7).toMillis()
        const val MAX_PARALLEL_WEEK_REQUESTS = 4
    }
}
