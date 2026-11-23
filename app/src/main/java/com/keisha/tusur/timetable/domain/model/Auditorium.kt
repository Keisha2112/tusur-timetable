package com.keisha.tusur.timetable.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant

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

data class RoomSchedule(
    val entries: ScheduleEntries,
    val updatedWeeks: Map<Int, Instant>,
    val staleWeeks: Set<Int>,
)
