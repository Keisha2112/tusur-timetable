package com.keisha.tusur.timetable.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Building(val code: String, val name: String)

@Serializable
data class Auditorium(val buildingCode: String, val slug: String, val number: String) {
    val label: String get() = "$buildingLabel $number"

    private val buildingLabel: String
        get() = when (buildingCode) {
            "gk" -> "гк"
            "f" -> "ф"
            "mk" -> "мк"
            "rk" -> "рк"
            "ulk" -> "улк"
            else -> buildingCode
        }
}

data class ScheduleEntries(
    val weeks: List<Week>,
    val lessons: List<Lesson>,
    val holidays: List<HolidayDay>,
    val publications: Map<Int, WeekPublication>,
)

@Serializable
data class RoomWeekCoverage(val checked: Int, val total: Int, val loading: Boolean) {
    val complete: Boolean get() = total > 0 && checked == total
}
