package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.LessonType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone

class IcsParserTest {

    private val parser = IcsParser()

    @Test
    fun `все события группы 714-2 разбираются`() {
        val ics = fixture("714-2.ics")
        val expected = ics.lineSequence().count { it.trim() == "BEGIN:VEVENT" }

        val lessons = parser.parse(ics)

        assertEquals(172, expected)
        assertEquals(expected, lessons.size)
        assertEquals("идентификаторы занятий не должны повторяться", expected, lessons.map { it.id }.distinct().size)
        assertTrue(lessons.none { it.discipline.isBlank() })
        assertTrue(lessons.all { it.end >= it.start })
    }

    @Test
    fun `практика 2 сентября 2026 в 08-50`() {
        val lesson = parser.parse(fixture("714-2.ics"))
            .single { it.date == LocalDate.of(2026, 9, 2) && it.start == LocalTime.of(8, 50) }

        assertEquals("Элективные дисциплины по физической культуре и спорту", lesson.discipline)
        assertEquals(LessonType.PRACTICE, lesson.type)
        assertEquals("Практика", lesson.rawType)
        assertEquals(LocalTime.of(10, 25), lesson.end)
        assertEquals(1, lesson.pairNumber)
        assertEquals("2026-09-02T08:50|Элективные дисциплины по физической культуре и спорту", lesson.id)

        assertEquals(6, lesson.teachers.size)
        assertEquals(
            listOf(
                "Головчук О.О.",
                "Гужов Ф.А.",
                "Давыдов Д.М.",
                "Максименко С.П.",
                "Марченко Н.В.",
                "Цвиринько В.Н.",
            ),
            lesson.teachers,
        )
        assertTrue("пустой LOCATION даёт пустой список аудиторий", lesson.rooms.isEmpty())
    }

    @Test
    fun `перенос строки в описании не разрывает фамилию`() {
        val ics = fixture("714-2.ics")
        assertTrue(
            "в фикстуре «Максим» и «енко С.П.» должны стоять на разных строках",
            Regex("Максим\\R енко").containsMatchIn(ics),
        )

        val lesson = parser.parse(ics)
            .first { it.date == LocalDate.of(2026, 9, 2) && it.start == LocalTime.of(8, 50) }

        assertTrue(lesson.teachers.contains("Максименко С.П."))
    }

    @Test
    fun `несколько аудиторий через экранированную запятую`() {
        val lesson = parser.parse(fixture("714-2.ics"))
            .single { it.date == LocalDate.of(2026, 9, 10) && it.start == LocalTime.of(8, 50) }

        assertEquals("Проектная деятельность (ГПО-2)", lesson.discipline)
        assertEquals(LessonType.PRACTICE, lesson.type)
        assertEquals(listOf("улк 401", "улк 408", "улк 504", "улк 707", "ф 435"), lesson.rooms)
        assertEquals(6, lesson.teachers.size)
    }

    @Test
    fun `занятия группы 716-1 разбираются`() {
        val lessons = parser.parse(fixture("716-1.ics"))

        assertEquals(162, lessons.size)

        val lecture = lessons.single { it.date == LocalDate.of(2026, 9, 2) && it.start == LocalTime.of(10, 40) }
        assertEquals("Информатика", lecture.discipline)
        assertEquals(LessonType.LECTURE, lecture.type)
        assertEquals(listOf("Талашко А.К."), lecture.teachers)
        assertEquals(listOf("улк 808"), lecture.rooms)
        assertEquals(2, lecture.pairNumber)
    }

    @Test
    fun `пустые описание и аудитория не ломают занятие`() {
        val lesson = parser.parse(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20260902T085000
            DTEND;TZID=Asia/Novosibirsk:20260902T102500
            SUMMARY:Без описания
            DESCRIPTION:
            LOCATION:
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        ).single()

        assertEquals("", lesson.rawType)
        assertEquals(LessonType.OTHER, lesson.type)
        assertTrue(lesson.teachers.isEmpty())
        assertTrue(lesson.rooms.isEmpty())
    }

    @Test
    fun `событие без названия пропускается`() {
        val lessons = parser.parse(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20260902T085000
            DTEND;TZID=Asia/Novosibirsk:20260902T102500
            DESCRIPTION:Лекция\, Иванов И.И.
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20260902T104000
            DTEND;TZID=Asia/Novosibirsk:20260902T121500
            SUMMARY:
            DESCRIPTION:Практика\, Петров П.П.
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20260902T131500
            DTEND;TZID=Asia/Novosibirsk:20260902T145000
            SUMMARY:Информатика
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )

        assertEquals(1, lessons.size)
        assertEquals("Информатика", lessons.single().discipline)
    }

    @Test
    fun `преподаватели и аудитории очищаются от повторов`() {
        val lesson = parser.parse(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20260902T085000
            SUMMARY:Повтор
            DESCRIPTION:Практика\, Иванов И.И.\, Петров П.П.\, Иванов И.И.
            LOCATION: улк 401\, улк 408\, улк 401\, 
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        ).single()

        assertEquals(listOf("Иванов И.И.", "Петров П.П."), lesson.teachers)
        assertEquals(listOf("улк 401", "улк 408"), lesson.rooms)
    }

    @Test
    fun `полностью одинаковые события схлопываются`() {
        val event = """
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20260902T085000
            DTEND;TZID=Asia/Novosibirsk:20260902T102500
            SUMMARY:Повтор
            DESCRIPTION:Лекция\, Иванов И.И.
            LOCATION:улк 401
            END:VEVENT
        """.trimIndent()

        val lessons = parser.parse("BEGIN:VCALENDAR\nVERSION:2.0\n$event\n$event\nEND:VCALENDAR")

        assertEquals(1, lessons.size)
    }

    @Test
    fun `событие вне известных недель не отбрасывается`() {
        val lesson = parser.parse(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20270115T085000
            DTEND;TZID=Asia/Novosibirsk:20270115T102500
            SUMMARY:Далёкое занятие
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        ).single()

        assertEquals(LocalDate.of(2027, 1, 15), lesson.date)
    }

    @Test
    fun `время в UTC переводится в часовой пояс расписания`() {
        val lesson = parser.parse(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20260902T015000Z
            DTEND:20260902T032500Z
            SUMMARY:UTC занятие
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        ).single()

        assertEquals(LocalDate.of(2026, 9, 2), lesson.date)
        assertEquals(LocalTime.of(8, 50), lesson.start)
        assertEquals(LocalTime.of(10, 25), lesson.end)
    }

    @Test
    fun `время без часового пояса не зависит от пояса устройства`() {
        val default = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val lesson = parser.parse(
                """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                DTSTART:20260902T085000
                DTEND:20260902T102500
                SUMMARY:Без TZID
                END:VEVENT
                END:VCALENDAR
                """.trimIndent(),
            ).single()

            assertEquals(LocalDate.of(2026, 9, 2), lesson.date)
            assertEquals(LocalTime.of(8, 50), lesson.start)
        } finally {
            TimeZone.setDefault(default)
        }
    }

    @Test
    fun `нестандартное время остаётся без номера пары`() {
        val lesson = parser.parse(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Novosibirsk:20260902T090000
            DTEND;TZID=Asia/Novosibirsk:20260902T100000
            SUMMARY:Сдвинутая пара
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        ).single()

        assertNull(lesson.pairNumber)
    }

    @Test
    fun `пустой файл даёт пустой список`() {
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   \n  ").isEmpty())
    }

    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: error("Фикстура не найдена: fixtures/$name")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
