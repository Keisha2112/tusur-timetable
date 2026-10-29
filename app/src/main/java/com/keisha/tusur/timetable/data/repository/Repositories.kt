package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Faculty
import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.ThemeMode
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.update.AppUpdate
import com.keisha.tusur.timetable.domain.update.DownloadProgress
import com.keisha.tusur.timetable.domain.update.DownloadedApk
import com.keisha.tusur.timetable.domain.update.InstallResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Значение из кэша вместе со временем его обновления.
 * [fromCache] = true, если сеть не использовалась (кэш свежий) или обновление не удалось.
 */
data class CachedValue<T>(
    val value: T,
    val updatedAt: Instant,
    val fromCache: Boolean,
)

/**
 * Результат загрузки расписания. Ошибка не выбрасывается исключением:
 * экран должен уметь показать старый кэш вместе с баннером.
 */
data class ScheduleLoadResult(
    val schedule: GroupSchedule?,
    val updatedAt: Instant?,
    val isFromCache: Boolean,
    val isOffline: Boolean,
    val error: AppError?,
)

data class UserSelection(
    val facultyCode: String,
    val facultyName: String,
    val groupSlug: String,
    val groupName: String,
)

interface CatalogRepository {

    /** Факультеты. Сначала кэш (TTL 7 дней), при необходимости — сеть. */
    suspend fun faculties(forceRefresh: Boolean = false): CachedValue<List<Faculty>>

    /** Группы факультета. Сначала кэш (TTL 7 дней), при необходимости — сеть. */
    suspend fun groups(facultyCode: String, forceRefresh: Boolean = false): CachedValue<List<Group>>

    suspend fun clearCache()
}

interface ScheduleRepository {

    /** Расписание из кэша без обращения к сети. */
    suspend fun cached(groupSlug: String): GroupSchedule?

    /** Время последнего успешного обновления, если кэш есть. */
    suspend fun lastUpdated(groupSlug: String): Instant?

    /**
     * Загружает расписание: кэш → при необходимости сеть.
     * Никогда не затирает кэш при ошибке сети или парсинга.
     */
    suspend fun load(group: Group, forceRefresh: Boolean = false): ScheduleLoadResult

    /**
     * Догружает занятия одной недели со страницы сайта. Нужен для будущих недель,
     * которых нет в `.ics`, и для случаев, когда ICS ещё не пересобрался после публикации.
     */
    suspend fun loadWeek(
        group: Group,
        week: Week,
        forceRefresh: Boolean = false,
    ): ScheduleLoadResult

    /**
     * Обновляет набор недель за один проход. Нужен для ручного обновления:
     * пользователь ожидает увидеть не только текущую неделю, но и все
     * опубликованные недели, которых ещё нет в кэше.
     */
    suspend fun loadWeeks(
        group: Group,
        weeks: List<Week>,
        forceRefresh: Boolean = false,
    ): ScheduleLoadResult

    suspend fun clearCache()
}

interface SettingsRepository {

    /** Сохранённый выбор факультета и группы, либо null при первом запуске. */
    val selection: Flow<UserSelection?>

    suspend fun saveSelection(selection: UserSelection)

    suspend fun clearSelection()

    /** Оформление приложения. При отсутствии сохранённого значения — [ThemeMode.SYSTEM]. */
    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)
}

interface UpdateRepository {
    val currentVersionName: String
    val isSupported: Boolean

    /** null — обновления нет. Бросает AppError при сетевой/серверной ошибке. */
    suspend fun checkForUpdate(): AppUpdate?

    /** Пора ли автоматически проверять обновления (не чаще 1 раза в 24 часа). */
    suspend fun shouldAutoCheck(now: Instant = Instant.now()): Boolean

    suspend fun markChecked(at: Instant = Instant.now())

    suspend fun isVersionSkipped(version: String): Boolean
    suspend fun skipVersion(version: String)

    fun canInstallPackages(): Boolean
    fun openInstallPermissionSettings()

    suspend fun download(
        update: AppUpdate,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadedApk

    suspend fun install(apk: DownloadedApk): InstallResult
}
