package com.keisha.tusur.timetable.data.repository

import androidx.room.withTransaction
import com.keisha.tusur.timetable.data.local.AppDatabase
import com.keisha.tusur.timetable.data.local.RoomCacheDao
import com.keisha.tusur.timetable.data.local.RoomCatalogCacheEntity
import com.keisha.tusur.timetable.data.local.RoomNotificationBaselineEntity
import com.keisha.tusur.timetable.data.local.RoomWeekCacheEntity
import com.keisha.tusur.timetable.data.local.RoomWeekStateEntity
import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec.json
import com.keisha.tusur.timetable.data.local.ScheduleNotificationEntity
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.parser.RoomCatalogParser
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.domain.change.ComparedWeek
import com.keisha.tusur.timetable.domain.change.ScheduleBaseline
import com.keisha.tusur.timetable.domain.change.ScheduleChangeDetector
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.RoomWeekCoverage
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal class RoomGroupScheduleRepository(
    private val dao: RoomCacheDao,
    private val client: TusurClient,
    private val parser: WeekScheduleParser,
    private val scope: CoroutineScope,
    private val database: AppDatabase? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val monitoring: suspend () -> ScheduleMonitoring? = { null },
) : ScheduleRepository {
    private val catalogParser = RoomCatalogParser()
    private val calendarLock = Mutex()
    private val catalogLock = Mutex()
    private val scanLock = Mutex()
    private val baselineLock = Mutex()
    private val requests = Semaphore(MAX_REQUESTS)
    private val scans = mutableMapOf<Pair<Int, String>, Scan>()
    private val activeWeeks = MutableStateFlow(emptySet<Pair<Int, String>>())
    private val cacheEpoch = AtomicLong()
    private val detector = ScheduleChangeDetector()

    private class Scan(val task: Deferred<RoomWeekSnapshot>) {
        var users = 0
    }

    override fun observe(group: Group) = combine(
        dao.observeCatalog(CALENDAR), dao.observeWeeks(), activeWeeks,
    ) { calendar, states, active ->
        display(group, calendar, states, active)
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun cached(group: Group): GroupSchedule? =
        withContext(Dispatchers.IO) {
            display(group, dao.catalog(CALENDAR), dao.weeks(), activeWeeks.value)
        }

    override suspend fun lastUpdated(group: Group): Instant? =
        cached(group)?.fetchedAt?.takeUnless { it == Instant.EPOCH }

    override suspend fun load(group: Group, forceRefresh: Boolean): ScheduleLoadResult =
        update(group) {
            val weeks = calendar()
            val week = ScheduleTime.currentWeek(weeks, today()) ?: return@update false
            loadVisibleWeek(group, week, forceRefresh)
        }

    override suspend fun loadWeek(group: Group, week: Week, forceRefresh: Boolean): ScheduleLoadResult =
        update(group) { loadVisibleWeek(group, week, forceRefresh) }

    override suspend fun loadWeeks(group: Group, weeks: List<Week>, forceRefresh: Boolean): ScheduleLoadResult =
        update(group) {
            var changed = false
            for (week in weeks.distinctBy(Week::id)) {
                if (loadVisibleWeek(group, week, forceRefresh)) changed = true
            }
            changed
        }

    override suspend fun refresh(
        group: Group,
        reason: ScheduleRefreshReason,
        selectedWeekId: Int?,
        monitoringGeneration: String?,
    ): ScheduleLoadResult = update(group) {
        val epoch = cacheEpoch.get()
        var error: AppError? = null
        val completed = mutableMapOf<Week, RoomWeekSnapshot>()
        val targets = mutableListOf<Week>()
        val run: suspend () -> Unit = {
            val weeks = calendar()
            val currentStart = ScheduleTime.weekStart(today())
            when (reason) {
                ScheduleRefreshReason.MANUAL -> {
                    val selected = weeks.firstOrNull { it.id == selectedWeekId }
                        ?: ScheduleTime.currentWeek(weeks, today())
                    if (selected != null) targets += selected
                }
                ScheduleRefreshReason.BACKGROUND -> {
                    targets += weeks.filter {
                        it.startDate == currentStart || it.startDate == currentStart.plusWeeks(1)
                    }
                }
            }

            for (week in targets) {
                try {
                    val snapshot = checkWeek(
                        week, force = true, resume = reason == ScheduleRefreshReason.BACKGROUND,
                    )
                    if (snapshot != null) completed[week] = snapshot
                } catch (failure: AppError) {
                    error = error ?: failure
                    if (failure is AppError.Network || failure is AppError.Server && failure.code == 429) break
                }
            }
        }
        if (reason == ScheduleRefreshReason.BACKGROUND) {
            if (withTimeoutOrNull(BACKGROUND_LIMIT) { run() } == null) error = error ?: AppError.Network()
        } else {
            run()
        }
        if (monitoringGeneration != null && completed.isNotEmpty()) {
            recordChanges(group, monitoringGeneration, targets, completed, epoch)
        }
        error?.let { throw it }
        completed.isNotEmpty()
    }

    override suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            scanLock.withLock {
                cacheEpoch.incrementAndGet()
                val tasks = scans.values.map(Scan::task)
                tasks.forEach { it.cancel() }
                tasks.joinAll()
                calendarLock.withLock {
                    catalogLock.withLock {
                        baselineLock.withLock { dao.clear() }
                    }
                }
            }
        }
    }

    private suspend fun update(group: Group, action: suspend () -> Boolean): ScheduleLoadResult =
        withContext(Dispatchers.IO) {
            var refreshed = false
            val error = try {
                refreshed = action()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AppError) {
                if (failure is AppError.NotFound) AppError.Parse(failure) else failure
            } catch (failure: Exception) {
                AppError.Unknown(failure)
            }
            val schedule = cached(group)
            ScheduleLoadResult(
                schedule, schedule?.fetchedAt?.takeUnless { it == Instant.EPOCH },
                isFromCache = !refreshed, isOffline = error is AppError.Network, error = error,
            )
        }

    private suspend fun calendar(): List<Week> = calendarLock.withLock {
        val entry = dao.catalog(CALENDAR)
        val cached = entry?.let { decode<List<Week>>(it.payload) }.orEmpty()
        if (entry != null && fresh(entry.updatedAt, CATALOG_TTL) &&
            ScheduleTime.findWeek(cached, today()) != null
        ) return@withLock cached

        var failure: AppError = AppError.Parse()
        // Список недель общий. Если первая страница изменилась, пробуем по одной аудитории из остальных корпусов.
        for (room in rooms().distinctBy(Auditorium::buildingCode)) {
            try {
                val html = request(Urls.auditorium(room)).body.orEmpty()
                val (selected, parsed) = parser.parseRoom(html, room, null) ?: throw AppError.Parse()
                val weeks = (parsed.weeks + selected).distinctBy(Week::id).sortedBy(Week::startDate)
                dao.saveCatalog(RoomCatalogCacheEntity(CALENDAR, json.encodeToString(weeks), clock.millis()))
                return@withLock weeks
            } catch (error: AppError) {
                failure = error
                if (error is AppError.Network || error is AppError.Server && error.code == 429) break
            }
        }
        throw failure
    }

    private suspend fun rooms(): List<Auditorium> = catalogLock.withLock {
        val cached = dao.catalog(ROOMS)
        if (cached != null && fresh(cached.updatedAt, CATALOG_TTL)) {
            decode<List<Auditorium>>(cached.payload)?.takeIf { it.isNotEmpty() }?.let { return@withLock it }
        }
        val buildings = catalogParser.buildings(request(Urls.BASE).body.orEmpty())
        if (buildings.isEmpty()) throw AppError.Parse()

        val rooms = buildings.flatMap { building ->
            val before = try {
                catalogParser.rooms(request(Urls.building(building.code)).body.orEmpty(), building)
            } catch (error: AppError) {
                if (error is AppError.Network || error is AppError.Server && error.code == 429) throw error
                emptyList()
            }
            before.ifEmpty {
                catalogParser.rooms(request(Urls.building(building.code, false)).body.orEmpty(), building)
                    .ifEmpty { throw AppError.Parse() }
            }
        }.distinctBy(Auditorium::cacheKey)
        dao.saveCatalog(RoomCatalogCacheEntity(ROOMS, json.encodeToString(rooms), clock.millis()))
        rooms
    }

    private suspend fun loadVisibleWeek(group: Group, week: Week, force: Boolean): Boolean {
        val epoch = cacheEpoch.get()
        val snapshot = checkWeek(week, force, resume = !force) ?: return false
        val current = monitoring()
        val selection = current?.selection
        val generation = current?.generation
        if (generation != null && !current.blocked && current.source == ScheduleSource.GROUP_FROM_ROOMS &&
            selection?.facultyCode == group.facultyCode && selection.groupSlug == group.slug
        ) {
            recordChanges(group, generation, listOf(week), mapOf(week to snapshot), epoch, seedOnly = true)
        }
        return true
    }

    private suspend fun checkWeek(week: Week, force: Boolean, resume: Boolean): RoomWeekSnapshot? {
        val key = week.cacheKey
        val scan = scanLock.withLock {
            val previous = scans[key]
            if (previous != null && !previous.task.isCompleted && !previous.task.isCancelled) {
                return@withLock previous.also { it.users++ }
            }
            if (!force && !activeWeeks.value.contains(key)) {
                val entry = dao.week(week.id, key.second)
                val snapshot = entry?.snapshot?.let(::decodeSnapshot)
                if (snapshot != null && entry.checked == entry.total && fresh(snapshot.checkedAt, WEEK_TTL)) {
                    return null
                }
            }
            val task = scope.async(start = CoroutineStart.LAZY) {
                previous?.task?.join()
                activeWeeks.update { it + key }
                try {
                    scanWeek(week, resume)
                } finally {
                    activeWeeks.update { it - key }
                }
            }
            Scan(task).also {
                it.users = 1
                scans[key] = it
                task.start()
            }
        }
        try {
            return scan.task.await()
        } catch (cancelled: CancellationException) {
            // Очистка кэша может отменить общий проход независимо от вызывающей корутины.
            currentCoroutineContext().ensureActive()
            return null
        } finally {
            withContext(NonCancellable) {
                scanLock.withLock {
                    scan.users--
                    if (scan.users == 0) {
                        if (!scan.task.isCompleted) scan.task.cancel()
                        else if (scans[key] === scan) scans.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun scanWeek(week: Week, resume: Boolean): RoomWeekSnapshot {
        val rooms = rooms()
        val date = week.startDate.toString()
        val oldState = dao.week(week.id, date)
        val oldSnapshot = oldState?.snapshot
        val cached = dao.pages(week.id, date)
            .mapNotNull { entity -> decode<RoomWeekPage>(entity.payload)?.let { entity.roomKey to it } }.toMap()
        val roomKeys = rooms.map(Auditorium::cacheKey).toSet()
        val previousCompleteAt = oldSnapshot?.let(::decodeSnapshot)?.checkedAt ?: Long.MIN_VALUE
        val pages = if (resume && oldState != null && oldState.checked < oldState.total) {
            cached.filter { (key, page) ->
                key in roomKeys && page.checkedAt > previousCompleteAt && fresh(page.checkedAt, RESUME_TTL)
            }.toMutableMap()
        } else mutableMapOf()
        var failure: AppError? = null
        val initialPartial = if (oldSnapshot == null) {
            if (pages.isNotEmpty()) encodeSnapshot(snapshot(pages)) else oldState?.partial
        } else null
        dao.saveWeek(RoomWeekStateEntity(week.id, date, pages.size, rooms.size, oldSnapshot, initialPartial))

        for (batch in rooms.filterNot { it.cacheKey in pages }.chunked(MAX_REQUESTS)) {
            val results = coroutineScope {
                batch.map { room ->
                    async {
                        try {
                            val page = fetchPage(week, room, cached[room.cacheKey])
                            dao.savePage(RoomWeekCacheEntity(week.id, date, room.cacheKey, json.encodeToString(page)))
                            Result.success(room.cacheKey to page)
                        } catch (error: AppError) {
                            if (error is AppError.Server && error.code == 429) throw error
                            Result.failure(error)
                        }
                    }
                }.awaitAll()
            }
            for (result in results) {
                result.onSuccess { (key, page) -> pages[key] = page }
                    .onFailure { if (failure == null || it is AppError.Network) failure = it as AppError }
            }
            // Пока есть полный снимок, экран оставляет его видимым при любом частичном обновлении.
            val partial = if (oldSnapshot == null && pages.isNotEmpty() && pages.size < rooms.size) {
                encodeSnapshot(snapshot(pages))
            } else null
            if (pages.size < rooms.size) {
                dao.saveWeek(RoomWeekStateEntity(week.id, date, pages.size, rooms.size, oldSnapshot, partial))
            }
            if (pages.isEmpty() && results.all { it.exceptionOrNull() is AppError.Network }) break
        }
        if (pages.size != rooms.size) throw failure ?: AppError.Parse()
        val complete = snapshot(pages)
        dao.saveWeek(RoomWeekStateEntity(week.id, date, pages.size, rooms.size, encodeSnapshot(complete), null))
        return complete
    }

    private suspend fun fetchPage(week: Week, room: Auditorium, cached: RoomWeekPage?): RoomWeekPage {
        val url = Urls.auditorium(room, week.id)
        val result = request(url, cached?.etag)
        if (result.notModified) return cached?.takeIf { it.etag != null }?.copy(checkedAt = clock.millis())
            ?: throw AppError.Parse()
        val (_, parsed) = parser.parseRoom(result.body.orEmpty(), room, week) ?: throw AppError.Parse()
        return RoomWeekPage(
            publication = checkNotNull(parsed.publication),
            lessons = parsed.lessons.map { it.copy(sourceUrl = url) },
            holidays = parsed.holidays,
            etag = result.etag,
            checkedAt = clock.millis(),
        )
    }

    private suspend fun request(url: String, etag: String? = null) =
        requests.withPermit { client.get(url, etag) }

    private fun snapshot(pages: Map<String, RoomWeekPage>): RoomWeekSnapshot {
        val lessons = mergeRoomLessons(pages.values.flatMap(RoomWeekPage::lessons))
        val holidays = pages.values.flatMap(RoomWeekPage::holidays).groupBy(HolidayDay::date).values
            .mapNotNull { marks -> marks.distinct().singleOrNull() }
            .filterNot { mark -> lessons.any(mark::blocks) }
            .sortedBy(HolidayDay::date)
        return RoomWeekSnapshot(
            checkedAt = clock.millis(),
            coverage = pages.mapValues { it.value.publication }.toSortedMap(),
            lessons = lessons,
            holidays = holidays,
        )
    }

    private fun display(
        group: Group,
        calendar: RoomCatalogCacheEntity?,
        states: List<RoomWeekStateEntity>,
        active: Set<Pair<Int, String>>,
    ): GroupSchedule? {
        val weeks = calendar?.let { decode<List<Week>>(it.payload) } ?: return null
        val byWeek = states.associateBy { it.weekId to it.startDate }
        val lessons = mutableListOf<Lesson>()
        val holidays = mutableListOf<HolidayDay>()
        val publications = mutableMapOf<Int, WeekPublication>()
        val checkedAt = mutableMapOf<Int, Long>()
        val stale = mutableSetOf<Int>()
        val coverage = mutableMapOf<Int, RoomWeekCoverage>()
        var mostRecent = Instant.EPOCH

        for (week in weeks) {
            val entry = byWeek[week.cacheKey]
            val loading = week.cacheKey in active
            if (entry != null || loading) {
                coverage[week.id] = RoomWeekCoverage(entry?.checked ?: 0, entry?.total ?: 0, loading)
            }
            if (entry == null) continue
            val snapshot = entry.snapshot?.let(::decodeSnapshot)
            val visible = snapshot ?: entry.partial?.let(::decodeSnapshot)
            if (snapshot != null) {
                mostRecent = maxOf(mostRecent, Instant.ofEpochMilli(snapshot.checkedAt))
                publications[week.id] = snapshot.publication
                if (entry.checked == entry.total) checkedAt[week.id] = snapshot.checkedAt
                else if (!loading) stale += week.id
            }
            if (visible != null) {
                lessons += visible.lessons.filter { it.belongsTo(group) }
                holidays += visible.holidays
            }
        }

        return GroupSchedule(
            group, weeks, lessons,
            fetchedAt = mostRecent,
            checkedWeeks = checkedAt, holidays = holidays, publications = publications,
            staleWeeks = stale, roomCoverage = coverage,
        )
    }

    private suspend fun recordChanges(
        group: Group,
        generation: String,
        targets: List<Week>,
        completed: Map<Week, RoomWeekSnapshot>,
        epoch: Long,
        seedOnly: Boolean = false,
    ) {
        val db = database ?: return
        baselineLock.withLock {
            if (cacheEpoch.get() != epoch) return@withLock
            db.withTransaction {
                val previous = dao.baseline(generation)?.let { decode<RoomBaseline>(it.payload) }
                val verified = completed.filter { (week, snapshot) ->
                    val old = previous?.schedule?.weeks?.get(week.id)
                    snapshot.publication == WeekPublication.PUBLISHED &&
                        (old == null || !seedOnly && snapshot.checkedAt > old.checkedAt)
                }
                if (verified.isEmpty()) return@withTransaction

                val coverage = verified.mapKeys { it.key.id }.mapValues { it.value.coverage }
                val prior = previous?.schedule?.copy(weeks = previous.schedule.weeks.filterKeys { id ->
                    id !in coverage || previous.coverage[id] == coverage[id]
                })
                val checked = verified.map { (week, snapshot) ->
                    week.id to ComparedWeek(
                        week.startDate, snapshot.checkedAt, snapshot.lessons.filter { it.belongsTo(group) },
                        snapshot.holidays,
                    )
                }.toMap()
                val now = clock.instant()
                val comparison = detector.compare(generation, prior, checked, targets.filter { it !in verified }, now)
                val storedCoverage = (previous?.coverage.orEmpty() + coverage)
                    .filterKeys { it in comparison.baseline.weeks }
                dao.saveBaseline(RoomNotificationBaselineEntity(
                    generation, json.encodeToString(RoomBaseline(comparison.baseline, storedCoverage)), now.toEpochMilli(),
                ))
                dao.pruneBaselines(now.minusSeconds(7L * 24 * 3600).toEpochMilli())
                if (comparison.changes.isNotEmpty()) {
                    db.scheduleNotificationDao().insert(ScheduleNotificationEntity(
                        UUID.randomUUID().toString(), generation, json.encodeToString(comparison.changes), now.toEpochMilli(),
                    ))
                }
            }
        }
    }

    private fun today() = clock.instant().atZone(ScheduleTime.ZONE).toLocalDate()

    private fun fresh(timestamp: Long, ttl: Long) = clock.millis() - timestamp in 0 until ttl

    private inline fun <reified T> decode(payload: String): T? =
        runCatching { json.decodeFromString<T>(payload) }.getOrNull()

    // Снимок всех аудиторий может превышать размер строки, доступный через CursorWindow.
    private fun encodeSnapshot(snapshot: RoomWeekSnapshot): ByteArray =
        ByteArrayOutputStream().apply {
            GZIPOutputStream(this).bufferedWriter(Charsets.UTF_8).use { it.write(json.encodeToString(snapshot)) }
        }.toByteArray()

    private fun decodeSnapshot(payload: ByteArray): RoomWeekSnapshot? = runCatching {
        GZIPInputStream(payload.inputStream()).bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString<RoomWeekSnapshot>(it.readText())
        }
    }.getOrNull()

    private companion object {
        const val MAX_REQUESTS = 3
        const val CALENDAR = "weeks"
        const val ROOMS = "auditoriums"
        const val CATALOG_TTL = 24L * 60 * 60 * 1000
        const val WEEK_TTL = 6L * 60 * 60 * 1000
        const val RESUME_TTL = 60L * 60 * 1000
        const val BACKGROUND_LIMIT = 8L * 60 * 1000
    }
}

private val Week.cacheKey get() = id to startDate.toString()
private val Auditorium.cacheKey get() = "$buildingCode/$slug"

@Serializable
private data class RoomWeekPage(
    val publication: WeekPublication,
    val lessons: List<Lesson>,
    val holidays: List<HolidayDay>,
    val etag: String?,
    val checkedAt: Long,
)

@Serializable
private data class RoomWeekSnapshot(
    val checkedAt: Long,
    val coverage: Map<String, WeekPublication>,
    val lessons: List<Lesson>,
    val holidays: List<HolidayDay>,
) {
    val publication: WeekPublication
        get() = if (WeekPublication.PUBLISHED in coverage.values) WeekPublication.PUBLISHED else WeekPublication.UNPUBLISHED
}

@Serializable
private data class RoomBaseline(
    val schedule: ScheduleBaseline,
    val coverage: Map<Int, Map<String, WeekPublication>>,
)
