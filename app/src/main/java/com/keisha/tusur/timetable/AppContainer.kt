package com.keisha.tusur.timetable

import android.content.Context
import com.keisha.tusur.timetable.data.local.AppDatabase
import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.parser.CatalogParser
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.TeacherParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import com.keisha.tusur.timetable.data.parser.WeekScheduleParser
import com.keisha.tusur.timetable.data.repository.CatalogRepository
import com.keisha.tusur.timetable.data.repository.CatalogRepositoryImpl
import com.keisha.tusur.timetable.data.repository.ScheduleRepository
import com.keisha.tusur.timetable.data.repository.ScheduleRepositoryImpl
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepositoryImpl
import com.keisha.tusur.timetable.data.repository.TeacherScheduleRepository
import com.keisha.tusur.timetable.data.repository.TeacherScheduleRepositoryImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Ручной DI-контейнер: всё создаётся лениво и живёт столько же, сколько процесс.
 * Сетевой клиент один на приложение — общий пул соединений и кэш TLS.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Фоновые задачи, которые не должны умирать вместе с экраном. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val httpClient: OkHttpClient by lazy { TusurClient.defaultClient() }

    private val client: TusurClient by lazy { TusurClient(httpClient) }

    private val database: AppDatabase by lazy {
        AppDatabase.build(appContext)
    }

    private val catalogParser: CatalogParser by lazy { CatalogParser() }

    private val weekListParser: WeekListParser by lazy { WeekListParser() }

    private val weekScheduleParser: WeekScheduleParser by lazy { WeekScheduleParser() }

    private val icsParser: IcsParser by lazy { IcsParser() }

    private val teacherParser: TeacherParser by lazy { TeacherParser() }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(appContext)
    }

    val catalogRepository: CatalogRepository by lazy {
        CatalogRepositoryImpl(
            dao = database.catalogDao(),
            client = client,
            catalogParser = catalogParser,
            ioDispatcher = ioDispatcher,
        )
    }

    val scheduleRepository: ScheduleRepository by lazy {
        ScheduleRepositoryImpl(
            dao = database.scheduleCacheDao(),
            client = client,
            weekListParser = weekListParser,
            icsParser = icsParser,
            weekScheduleParser = weekScheduleParser,
            ioDispatcher = ioDispatcher,
        )
    }

    val teacherScheduleRepository: TeacherScheduleRepository by lazy {
        TeacherScheduleRepositoryImpl(
            dao = database.teacherScheduleCacheDao(),
            client = client,
            weekListParser = weekListParser,
            icsParser = icsParser,
            teacherParser = teacherParser,
            ioDispatcher = ioDispatcher,
        )
    }
}
