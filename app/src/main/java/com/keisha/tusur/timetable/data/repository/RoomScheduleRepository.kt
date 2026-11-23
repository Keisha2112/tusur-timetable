package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.RoomCacheDao
import com.keisha.tusur.timetable.data.local.RoomCatalogCacheEntity
import com.keisha.tusur.timetable.data.local.RoomScheduleCacheEntity
import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec.json
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.parser.RoomCatalogParser
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Building
import com.keisha.tusur.timetable.domain.model.HolidayDay
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.RoomSchedule
import com.keisha.tusur.timetable.domain.model.ScheduleEntries
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekPublication
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class RoomScheduleRepository(
    private val dao: RoomCacheDao,
    private val client: TusurClient,
    private val weekParser: WeekScheduleParser,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val catalogParser = RoomCatalogParser()
    private val catalogMutex = Mutex()
    private val scheduleMutex = Mutex()

    suspend fun buildings(forceRefresh: Boolean = false): List<Building> =
        catalog(
            key = "buildings",
            forceRefresh = forceRefresh,
            fetch = { catalogParser.buildings(client.get(Urls.BASE).body.orEmpty()) },
            fallback = { emptyList() },
        )

    suspend fun rooms(building: Building, forceRefresh: Boolean = false): List<Auditorium> =
        catalog(
            key = "rooms:${building.code}",
            forceRefresh = forceRefresh,
            fetch = { requestRooms(building, beforeBreaking = true) },
            fallback = { requestRooms(building, beforeBreaking = false) },
        )

    private suspend fun requestRooms(building: Building, beforeBreaking: Boolean): List<Auditorium> =
        catalogParser.rooms(client.get(Urls.building(building.code, beforeBreaking)).body.orEmpty(), building)

    private suspend inline fun <reified T> catalog(
        key: String,
        forceRefresh: Boolean,
        crossinline fetch: suspend () -> List<T>,
        crossinline fallback: suspend () -> List<T>,
    ): List<T> = withContext(ioDispatcher) {
        catalogMutex.withLock {
            val entry = dao.catalog(key)
            val cached = entry?.payload?.let { decode<List<T>>(it) }?.takeIf { it.isNotEmpty() }
            if (!forceRefresh && cached != null && fresh(entry.updatedAt, CATALOG_TTL)) return@withLock cached
            val fresh = try {
                fetch().takeIf { it.isNotEmpty() } ?: throw AppError.Parse()
            } catch (error: AppError) {
                if (cached != null) return@withLock cached
                fallback().takeIf { it.isNotEmpty() } ?: throw error
            }
            dao.saveCatalog(RoomCatalogCacheEntity(key, json.encodeToString(fresh), clock.millis()))
            fresh
        }
    }

    fun observe(room: Auditorium) = dao.observeSchedule(room.buildingCode, room.slug)
        .map { it?.payload?.let { decode<StoredRoomSchedule>(it) }?.display() }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    suspend fun lastUpdated(room: Auditorium): Instant? = withContext(ioDispatcher) {
        cached(room)?.weeks?.values?.maxOfOrNull { it.updatedAt }?.let(Instant::ofEpochMilli)
    }

    /**
     * Без явной недели берём текущую из проверенного списка или открываем страницу
     * аудитории, чтобы получить её список и расписание одним запросом.
     */
    suspend fun load(room: Auditorium, week: Week? = null, forceRefresh: Boolean = false): AppError? =
        withContext(ioDispatcher) {
            try {
                scheduleMutex.withLock {
                    val stored = cached(room) ?: StoredRoomSchedule()
                    val today = clock.instant().atZone(ScheduleTime.ZONE).toLocalDate()
                    val target = week ?: ScheduleTime.findWeek(stored.calendar, today)
                    val previous = target?.let { expected ->
                        stored.weeks[expected.id]?.takeIf { it.week.startDate == expected.startDate }
                    }
                    if (!forceRefresh && previous != null && fresh(previous.checkedAt, WEEK_TTL)) return@withLock

                    val response = client.get(Urls.auditorium(room, target?.id), previous?.etag)
                    val now = clock.millis()
                    if (response.notModified) {
                        if (previous?.etag == null) throw AppError.Parse()
                        store(room, stored.copy(weeks = stored.weeks + (previous.week.id to previous.copy(checkedAt = now))))
                        return@withLock
                    }

                    val (loaded, parsed) = weekParser.parseRoom(response.body.orEmpty(), room, target)
                        ?: throw AppError.Parse()
                    val publication = parsed.publication ?: throw AppError.Parse()
                    val oldWeek = stored.weeks[loaded.id]?.takeIf { it.week.startDate == loaded.startDate }
                    val savedWeek = StoredRoomWeek(
                        week = loaded,
                        publication = publication,
                        lessons = if (publication == WeekPublication.PUBLISHED) parsed.lessons else oldWeek?.lessons.orEmpty(),
                        holidays = (oldWeek?.holidays.orEmpty().filterNot { it.date in parsed.calendarDates } + parsed.holidays)
                            .sortedBy { it.date },
                        etag = response.etag.takeIf { target != null },
                        updatedAt = now,
                        checkedAt = now,
                    )
                    val calendar = (parsed.weeks + loaded + stored.calendar).distinctBy { it.id }.sortedBy { it.startDate }
                    store(room, StoredRoomSchedule(calendar, stored.weeks + (loaded.id to savedWeek)))
                }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppError) {
                error
            } catch (error: Exception) {
                AppError.Unknown(error)
            }
        }

    suspend fun clearCache() = withContext(ioDispatcher) {
        catalogMutex.withLock { scheduleMutex.withLock { dao.clear() } }
    }

    private suspend fun cached(room: Auditorium): StoredRoomSchedule? =
        dao.schedule(room.buildingCode, room.slug)?.payload?.let { decode<StoredRoomSchedule>(it) }

    private suspend fun store(room: Auditorium, stored: StoredRoomSchedule) {
        dao.saveSchedule(RoomScheduleCacheEntity(room.buildingCode, room.slug, json.encodeToString(stored)))
    }

    private fun fresh(timestamp: Long, ttl: Long): Boolean = clock.millis() - timestamp in 0 until ttl

    private inline fun <reified T> decode(payload: String): T? =
        runCatching { json.decodeFromString<T>(payload) }.getOrNull()

    companion object {
        private val CATALOG_TTL = Duration.ofDays(1).toMillis()
        private val WEEK_TTL = Duration.ofMinutes(15).toMillis()
    }
}

@Serializable
private data class StoredRoomSchedule(
    val calendar: List<Week> = emptyList(),
    val weeks: Map<Int, StoredRoomWeek> = emptyMap(),
) {
    fun display(): RoomSchedule {
        val visible = calendar.mapNotNull { date -> weeks[date.id]?.takeIf { it.week.startDate == date.startDate } }
        return RoomSchedule(
            entries = ScheduleEntries(
                weeks = calendar,
                lessons = visible.flatMap { it.lessons },
                holidays = visible.flatMap { it.holidays },
                publications = visible.associate { it.week.id to it.publication },
            ),
            updatedWeeks = visible.associate { it.week.id to Instant.ofEpochMilli(it.updatedAt) },
            staleWeeks = visible.filter { it.publication == WeekPublication.UNPUBLISHED && it.lessons.isNotEmpty() }
                .mapTo(mutableSetOf()) { it.week.id },
        )
    }
}

@Serializable
private data class StoredRoomWeek(
    val week: Week,
    val publication: WeekPublication,
    val lessons: List<Lesson>,
    val holidays: List<HolidayDay>,
    val etag: String?,
    val updatedAt: Long,
    val checkedAt: Long,
)
