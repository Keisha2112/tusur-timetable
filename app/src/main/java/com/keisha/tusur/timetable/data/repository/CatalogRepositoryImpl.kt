package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.data.local.CatalogDao
import com.keisha.tusur.timetable.data.local.toDomain
import com.keisha.tusur.timetable.data.local.toEntity
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.parser.CatalogParser
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Faculty
import com.keisha.tusur.timetable.domain.model.Group
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Каталог факультетов и групп. TTL — 7 дней: сайт меняет состав групп редко,
 * а страницы отвечают медленно.
 */
class CatalogRepositoryImpl(
    private val dao: CatalogDao,
    private val client: TusurClient,
    private val catalogParser: CatalogParser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CatalogRepository {

    override suspend fun faculties(forceRefresh: Boolean): CachedValue<List<Faculty>> =
        withContext(ioDispatcher) {
            val cached = dao.faculties()
            val cachedAt = cached.maxOfOrNull { it.updatedAt }

            if (!forceRefresh && cachedAt != null && isFresh(cachedAt)) {
                return@withContext cachedValue(cached.map { it.toDomain() }, cachedAt)
            }

            try {
                val faculties = fetchFaculties()
                val now = System.currentTimeMillis()
                dao.replaceFaculties(faculties.map { it.toEntity(now) })
                CachedValue(
                    value = faculties,
                    updatedAt = Instant.ofEpochMilli(now),
                    fromCache = false,
                )
            } catch (e: AppError) {
                // Ошибка сети или разбора не должна обнулять каталог.
                if (cachedAt != null) {
                    cachedValue(cached.map { it.toDomain() }, cachedAt)
                } else {
                    throw e
                }
            }
        }

    override suspend fun groups(facultyCode: String, forceRefresh: Boolean): CachedValue<List<Group>> =
        withContext(ioDispatcher) {
            val cached = dao.groups(facultyCode)
            val cachedAt = cached.maxOfOrNull { it.updatedAt }

            if (!forceRefresh && cachedAt != null && isFresh(cachedAt)) {
                return@withContext cachedValue(cached.map { it.toDomain() }, cachedAt)
            }

            try {
                val groups = fetchGroups(facultyCode)
                val now = System.currentTimeMillis()
                dao.replaceGroups(facultyCode, groups.map { it.toEntity(now) })
                CachedValue(
                    value = groups,
                    updatedAt = Instant.ofEpochMilli(now),
                    fromCache = false,
                )
            } catch (e: AppError) {
                if (cachedAt != null) {
                    cachedValue(cached.map { it.toDomain() }, cachedAt)
                } else {
                    throw e
                }
            }
        }

    override suspend fun clearCache() {
        withContext(ioDispatcher) { dao.clearCatalog() }
    }

    private suspend fun fetchFaculties(): List<Faculty> {
        val html = client.get(Urls.faculties()).body ?: throw AppError.Parse()
        val faculties = parse { catalogParser.parseFaculties(html) }
        // Пустой список означает, что разметка изменилась, а не что факультетов не стало.
        if (faculties.isEmpty()) throw AppError.Parse()
        return faculties
    }

    private suspend fun fetchGroups(facultyCode: String): List<Group> {
        val html = client.get(Urls.faculty(facultyCode)).body ?: throw AppError.Parse()
        val groups = parse { catalogParser.parseGroups(html, facultyCode) }
        if (groups.isEmpty()) throw AppError.Parse()
        return groups
    }

    private fun isFresh(updatedAt: Long): Boolean {
        val age = System.currentTimeMillis() - updatedAt
        // Отрицательный возраст бывает при переводе часов назад — кэш считаем свежим.
        return age < CATALOG_TTL_MS
    }

    private fun <T> cachedValue(value: T, updatedAt: Long): CachedValue<T> =
        CachedValue(
            value = value,
            updatedAt = Instant.ofEpochMilli(updatedAt),
            fromCache = true,
        )

    /** Jsoup и парсеры бросают произвольные исключения — приводим их к ошибке разбора. */
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

    private companion object {
        const val CATALOG_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
