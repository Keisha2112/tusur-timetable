package com.keisha.tusur.timetable.ui.schedule

import com.keisha.tusur.timetable.domain.model.DaySchedule
import java.time.LocalDate

/**
 * Номер последней пары недели: до него список показывает свободные слоты.
 * 0 — ни у одного занятия нет номера пары (нестандартное время), окна не рисуем.
 */
internal fun weekMaxPair(days: List<DaySchedule>): Int =
    days.asSequence()
        .flatMap { it.lessons.asSequence() }
        .mapNotNull { it.pairNumber }
        .maxOrNull() ?: 0

/** Сколько свободных пар внутри дня нужно показать при сетке до [maxPair]. */
internal fun freeSlotCount(day: DaySchedule, maxPair: Int): Int {
    if (day.lessons.isEmpty() || maxPair <= 0) return 0
    return (1..maxPair).count { pair ->
        day.lessons.none { it.pairNumber == pair }
    }
}

/**
 * Число элементов LazyColumn у дня: заголовок, занятия, свободные пары
 * или одна заглушка «Занятий нет».
 */
internal fun dayItemCount(day: DaySchedule, maxPair: Int): Int =
    if (day.lessons.isEmpty()) {
        2
    } else {
        1 + day.lessons.size + freeSlotCount(day, maxPair)
    }

/**
 * Индекс заголовка дня в LazyColumn главного экрана.
 *
 * Список строится парами «заголовок + содержимое»: у дня без занятий содержимое —
 * одна заглушка «Занятий нет», у дня с занятиями — карточки и свободные пары
 * до [maxPair]. Функция не зависит от Compose, чтобы её можно было проверить
 * обычным JUnit-тестом.
 *
 * @param maxPair последняя пара недели; 0 — показывать только существующие занятия
 * @return индекс заголовка или null, если такой даты в списке нет
 * (например, пустое воскресенье [com.keisha.tusur.timetable.domain.time.ScheduleTime.daysForWeek]
 * не включает).
 */
internal fun dayHeaderIndex(
    days: List<DaySchedule>,
    date: LocalDate,
    maxPair: Int = 0,
): Int? {
    var index = 0
    days.forEach { day ->
        if (day.date == date) return index
        index += dayItemCount(day, maxPair)
    }
    return null
}

/**
 * С какого элемента открывать список дней недели.
 *
 * Текущая неделя открывается на сегодняшнем заголовке, все остальные — с понедельника.
 * Если сегодняшнего дня в списке нет (воскресенье без занятий) или неделя ещё не
 * выбрана, возвращается 0: список открывается с начала.
 *
 * Используется в [ScheduleWeekList] для одноразовой прокрутки при первом показе
 * недели или группы.
 */
internal fun weekStartIndex(
    days: List<DaySchedule>,
    weekId: Int?,
    currentWeekId: Int?,
    today: LocalDate,
    maxPair: Int = 0,
): Int {
    if (weekId == null || weekId != currentWeekId) return 0
    return dayHeaderIndex(days, today, maxPair) ?: 0
}
