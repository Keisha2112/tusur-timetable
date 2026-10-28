package com.keisha.tusur.timetable.ui.schedule

import com.keisha.tusur.timetable.domain.model.DaySchedule
import java.time.LocalDate

/**
 * Индекс заголовка дня в LazyColumn главного экрана.
 *
 * Список строится парами «заголовок + содержимое»: у дня без занятий содержимое —
 * одна заглушка «Занятий нет», у дня с занятиями — по элементу на каждое занятие.
 * Поэтому позиция заголовка зависит от числа карточек всех предыдущих дней.
 * Функция не зависит от Compose, чтобы её можно было проверить обычным JUnit-тестом.
 *
 * @return индекс заголовка или null, если такой даты в списке нет
 * (например, пустое воскресенье [com.keisha.tusur.timetable.domain.time.ScheduleTime.daysForWeek]
 * не включает).
 */
internal fun dayHeaderIndex(days: List<DaySchedule>, date: LocalDate): Int? {
    var index = 0
    days.forEach { day ->
        if (day.date == date) return index
        index += 1 + day.lessons.size.coerceAtLeast(1)
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
 * Используется в [WeekLessonsList] для одноразовой прокрутки при первом показе
 * недели или группы.
 */
internal fun weekStartIndex(
    days: List<DaySchedule>,
    weekId: Int?,
    currentWeekId: Int?,
    today: LocalDate,
): Int {
    if (weekId == null || weekId != currentWeekId) return 0
    return dayHeaderIndex(days, today) ?: 0
}
