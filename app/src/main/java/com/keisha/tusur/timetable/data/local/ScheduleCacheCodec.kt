package com.keisha.tusur.timetable.data.local

import com.keisha.tusur.timetable.domain.model.GroupSchedule
import kotlinx.serialization.json.Json

/**
 * JSON-кодек кэша расписания.
 *
 * [decode] возвращает null при любом повреждении данных: битый JSON, несовпадение
 * модели после обновления приложения, некорректная дата. Для вызывающего это значит
 * «кэша нет» — запись удаляется и расписание загружается заново.
 */
object ScheduleCacheCodec {

    val json: Json = Json {
        // Поля, добавленные в модель позже, не должны ломать чтение старого кэша.
        ignoreUnknownKeys = true
        // Пустые списки и null-поля пишем явно, чтобы JSON был предсказуемым.
        encodeDefaults = true
    }

    fun encode(schedule: GroupSchedule): String =
        json.encodeToString(GroupSchedule.serializer(), schedule)

    fun decode(payload: String): GroupSchedule? =
        try {
            json.decodeFromString(GroupSchedule.serializer(), payload)
        } catch (e: Exception) {
            // Кэш — вспомогательные данные, поэтому любая ошибка разбора равнозначна его отсутствию.
            null
        }
}
