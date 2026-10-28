package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.ScheduleCacheCodec
import com.keisha.tusur.timetable.data.local.TeacherScheduleCacheDao
import com.keisha.tusur.timetable.data.local.TeacherScheduleCacheEntity
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.TeacherParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Teacher
import com.keisha.tusur.timetable.domain.model.TeacherSchedule
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
 * Расписание преподавателя.
 *
 * Особенности по сравнению с расписанием группы:
 * - ключ кэша — короткое имя из карточки занятия, id преподавателя лежит внутри JSON;
 * - имя разрешается через общий поиск сайта: по фамилии сайт либо перенаправляет
 *   на страницу преподавателя, либо показывает список (тогда выбираем по инициалам);
 * - `.ics` преподавателя содержит в описании группы, а не преподавателей;
 * - страница преподавателя — та же, что отдаёт поиск при единственном совпадении,
 *   поэтому при первом открытии список недель берётся из ответа поиска.
 */
class TeacherScheduleRepositoryImpl(
    private val dao: TeacherScheduleCacheDao,
    private val client: TusurClient,
    private val weekListParser: WeekListParser,
    private val icsParser: IcsParser,
    private val teacherParser: TeacherParser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TeacherScheduleRepository {

    private val mutex = Mutex()

    override suspend fun cached(teacherName: String): TeacherSchedule? =
        withContext(ioDispatcher) { readCache(teacherKey(teacherName))?.schedule }

    override suspend fun lastUpdated(teacherName: String): Instant? =
        withContext(ioDispatcher) {
            dao.updatedAt(teacherKey(teacherName))?.let { Instant.ofEpochMilli(it) }
        }

    override suspend fun load(
        teacherName: String,
        forceRefresh: Boolean,
    ): TeacherScheduleLoadResult = withContext(ioDispatcher) {
        val key = teacherKey(teacherName)
        val startedAt = System.currentTimeMillis()
        val cached = readCache(key)

        if (!forceRefresh && cached != null && isFresh(cached)) {
            return@withContext fromCache(cached)
        }

        mutex.withLock {
            val current = readCache(key)
            if (current != null) {
                // Строго больше: запись, сделанная в ту же миллисекунду, — это наш же
                // предыдущий вызов, а не параллельное обновление.
                val refreshedByAnotherCall = current.entity.checkedAt > startedAt
                if (refreshedByAnotherCall || (!forceRefresh && isFresh(current))) {
                    return@withLock fromCache(current)
                }
            }
            loadFromNetwork(teacherName, key, current)
        }
    }

    override suspend fun clearCache() {
        withContext(ioDispatcher) { dao.deleteAll() }
    }

    private suspend fun loadFromNetwork(
        teacherName: String,
        key: String,
        cached: CachedTeacher?,
    ): TeacherScheduleLoadResult =
        try {
            val knownTeacher = cached?.schedule?.teacher
            val requestWeeks = cached == null || !weeksAreFresh(cached)
            // При первом открытии поиск уже вернул страницу преподавателя.
            val resolved = if (knownTeacher == null) resolve(teacherName) else null
            val teacher = resolved?.teacher ?: knownTeacher ?: throw AppError.NotFound()

            val (icsResponse, weeksHtml) = coroutineScope {
                val ics = async { client.get(Urls.teacherIcs(teacher.id)) }
                val weeksPage = if (requestWeeks && resolved?.pageHtml == null) {
                    async { client.get(Urls.teacher(teacher.id)).body.orEmpty() }
                } else {
                    null
                }
                ics.await() to (resolved?.pageHtml ?: weeksPage?.await())
            }

            val lessons = parse {
                icsParser.parse(
                    ics = icsResponse.body.orEmpty(),
                    descriptionMode = IcsParser.DescriptionMode.GROUPS,
                )
            }

            val weeks = when {
                !requestWeeks -> cached?.schedule?.weeks.orEmpty()
                weeksHtml.isNullOrBlank() -> cached?.schedule?.weeks.orEmpty()
                else -> {
                    val parsed = parse { weekListParser.parse(weeksHtml) }
                    if (parsed.isEmpty()) cached?.schedule?.weeks.orEmpty() else parsed
                }
            }
            if (weeks.isEmpty()) throw AppError.Parse()

            val now = Instant.now()
            val schedule = TeacherSchedule(
                teacher = teacher,
                weeks = weeks,
                lessons = lessons,
                fetchedAt = now,
            )
            dao.upsert(
                TeacherScheduleCacheEntity(
                    teacherKey = key,
                    payload = ScheduleCacheCodec.encodeTeacher(schedule),
                    updatedAt = now.toEpochMilli(),
                    checkedAt = now.toEpochMilli(),
                ),
            )

            TeacherScheduleLoadResult(
                schedule = schedule,
                updatedAt = now,
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
                TeacherScheduleLoadResult(
                    schedule = null,
                    updatedAt = null,
                    isFromCache = false,
                    isOffline = error is AppError.Network,
                    error = error,
                )
            }
        }

    private suspend fun resolve(teacherName: String): ResolvedTeacher {
        // В расписании только «Фамилия И.О.», поэтому ищем по фамилии.
        val surname = teacherName.trim().substringBefore(' ').ifBlank { teacherName.trim() }
        val response = client.get(Urls.search(surname))

        val redirectedId = TEACHER_PATH.find(response.finalUrl.orEmpty())?.groupValues?.get(1)
        if (redirectedId != null) {
            val html = response.body.orEmpty()
            return ResolvedTeacher(
                teacher = teacherParser.parsePage(html, redirectedId, teacherName),
                pageHtml = html,
            )
        }

        val candidates = teacherParser.parseSearchResults(response.body.orEmpty())
        val candidate = teacherParser.match(candidates, teacherName) ?: throw AppError.NotFound()
        return ResolvedTeacher(
            teacher = teacherParser.teacherFromCandidate(candidate, teacherName),
            pageHtml = null,
        )
    }

    private suspend fun readCache(key: String): CachedTeacher? {
        val entity = dao.get(key) ?: return null
        val schedule = ScheduleCacheCodec.decodeTeacher(entity.payload)
        if (schedule == null) {
            dao.delete(key)
            return null
        }
        return CachedTeacher(entity = entity, schedule = schedule)
    }

    private fun isFresh(cached: CachedTeacher): Boolean {
        val age = System.currentTimeMillis() - cached.entity.checkedAt
        if (age < 0) return true
        val ttl = if (ScheduleTime.currentWeek(cached.schedule.weeks) != null) {
            TTL_CURRENT_WEEK_MS
        } else {
            TTL_DEFAULT_MS
        }
        return age < ttl
    }

    private fun weeksAreFresh(cached: CachedTeacher): Boolean {
        val weeks = cached.schedule.weeks
        if (weeks.isEmpty()) return false
        if (ScheduleTime.findWeek(weeks, ScheduleTime.today()) == null) return false
        val age = System.currentTimeMillis() - cached.entity.updatedAt
        return age >= 0 && age < WEEKS_TTL_MS
    }

    private fun fromCache(
        cached: CachedTeacher,
        isOffline: Boolean = false,
        error: AppError? = null,
    ): TeacherScheduleLoadResult = TeacherScheduleLoadResult(
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

    /** Устойчивый ключ кэша: регистр, «ё» и лишние пробелы не должны создавать дубли. */
    private fun teacherKey(name: String): String = name.trim()
        .lowercase()
        .replace('ё', 'е')
        .replace(WHITESPACE, " ")

    private data class CachedTeacher(
        val entity: TeacherScheduleCacheEntity,
        val schedule: TeacherSchedule,
    )

    private data class ResolvedTeacher(
        val teacher: Teacher,
        val pageHtml: String?,
    )

    private companion object {
        const val TTL_CURRENT_WEEK_MS = 15L * 60 * 1000
        const val TTL_DEFAULT_MS = 6L * 60 * 60 * 1000
        const val WEEKS_TTL_MS = 7L * 24 * 60 * 60 * 1000

        val TEACHER_PATH = Regex("""/teachers/(\d+)""")
        val WHITESPACE = Regex("""\s+""")
    }
}
