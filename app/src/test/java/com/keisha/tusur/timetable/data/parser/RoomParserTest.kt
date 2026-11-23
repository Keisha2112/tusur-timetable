package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Building
import com.keisha.tusur.timetable.domain.model.WeekPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RoomParserTest {
    private val catalog = RoomCatalogParser()
    private val schedule = WeekScheduleParser()

    @Test
    fun `catalog uses links for five buildings and irregular room numbers`() {
        val buildings = catalog.buildings(FixtureLoader.text("room_buildings.html"))
        assertEquals(listOf("gk", "f", "mk", "rk", "ulk"), buildings.map { it.code })
        val main = catalog.rooms(FixtureLoader.text("room_catalog_gk.html"), buildings.first())
        val splitNumber = main.single { it.number == "416/1" }
        assertEquals("https://timetable.tusur.ru/buildings/gk/auditoriums/416-1", Urls.auditorium(splitNumber))

        val small = catalog.rooms(FixtureLoader.text("room_catalog_mk.html"), Building("mk", "мк"))
        assertEquals(4, small.size)
        assertTrue(small.single { it.number == "205" }.slug.startsWith("205-"))
        assertEquals(emptyList<Auditorium>(), catalog.rooms(FixtureLoader.text("room_catalog_mk.html"), buildings.first()))
    }

    @Test
    fun `real room table has one entry per training and includes groups teachers and notes`() {
        val room = Auditorium("ulk", "401", "401")
        val (week, parsed) = checkNotNull(schedule.parseRoom(FixtureLoader.text("room_ulk_401.html"), room, null))
        assertEquals(851, week.id)
        assertEquals(LocalDate.of(2026, 11, 23), week.startDate)
        assertEquals(WeekPublication.PUBLISHED, parsed.publication)
        assertEquals(33, parsed.lessons.size)
        val lecture = parsed.lessons.first()
        assertEquals(listOf("713-1", "723-1", "723-2"), lecture.groups)
        assertEquals(listOf("Суровцев Р.С."), lecture.teachers)
        assertEquals(listOf("35243"), lecture.teacherIds)
        assertEquals(listOf("улк 401"), lecture.rooms)
        val parallel = parsed.lessons.filter { it.note == "с 12:30" }
        assertTrue(parallel.isNotEmpty())
        assertTrue(parallel.any { "716-М" in it.groups })
        assertTrue(parallel.any { "715-М" in it.groups })
    }

    @Test
    fun `empty known week and mismatched pages are distinguished`() {
        val room = Auditorium("rk", "328", "328")
        val (emptyWeek, empty) = checkNotNull(schedule.parseRoom(FixtureLoader.text("room_rk_328_empty.html"), room, null))
        assertEquals(876, emptyWeek.id)
        assertEquals(WeekPublication.PUBLISHED, empty.publication)
        assertTrue(empty.lessons.isEmpty())
        val next = FixtureLoader.text("room_rk_328_next.html")
        assertNotNull(schedule.parseRoom(next, room, null))
        assertNull(schedule.parseRoom(next, room, emptyWeek))
        assertNull(schedule.parseRoom(next, Auditorium("ulk", "328", "328"), null))
    }
}
