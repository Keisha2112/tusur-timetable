package com.keisha.tusur.timetable.data.local

import com.keisha.tusur.timetable.domain.model.Group
import com.keisha.tusur.timetable.domain.model.GroupSchedule
import com.keisha.tusur.timetable.domain.model.Lesson
import com.keisha.tusur.timetable.domain.model.LessonType
import com.keisha.tusur.timetable.domain.model.Teacher
import com.keisha.tusur.timetable.domain.model.TeacherSchedule
import com.keisha.tusur.timetable.domain.model.Week
import com.keisha.tusur.timetable.domain.model.WeekKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ScheduleCacheCodecTest {

    private val group = Group(
        facultyCode = "fb",
        slug = "714-2",
        name = "714-2",
        course = 2,
    )

    @Test
    fun `round trip keeps all fields`() {
        val schedule = GroupSchedule(
            group = group,
            weeks = listOf(
                Week(
                    id = 9,
                    number = 9,
                    startDate = LocalDate.of(2026, 10, 26),
                    kind = WeekKind.THEORETICAL,
                    isCurrent = true,
                ),
                Week(
                    id = 10,
                    number = 10,
                    startDate = LocalDate.of(2026, 11, 2),
                    kind = WeekKind.EXAM_SESSION,
                    isCurrent = false,
                ),
            ),
            lessons = listOf(
                Lesson(
                    id = "2026-10-26T08:50-Программирование",
                    date = LocalDate.of(2026, 10, 26),
                    start = LocalTime.of(8, 50),
                    end = LocalTime.of(10, 25),
                    pairNumber = 1,
                    discipline = "Программирование, часть 2",
                    type = LessonType.LECTURE,
                    rawType = "Лекция",
                    rooms = listOf("ауд. 100", "УЛК-2"),
                    teachers = listOf("Иванов И. И.", "Петрова А. С."),
                ),
                Lesson(
                    id = "2026-10-27T13:15-Физика",
                    date = LocalDate.of(2026, 10, 27),
                    start = LocalTime.of(13, 15),
                    end = LocalTime.of(14, 50),
                    pairNumber = null,
                    discipline = "Физика",
                    type = LessonType.OTHER,
                    rawType = "Семинар",
                ),
            ),
            fetchedAt = Instant.parse("2026-10-27T03:15:42Z"),
        )

        val decoded = ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(schedule))

        assertEquals(schedule, decoded)
    }

    @Test
    fun `round trip keeps empty lists`() {
        val schedule = GroupSchedule(
            group = group,
            weeks = emptyList(),
            lessons = emptyList(),
            fetchedAt = Instant.parse("2026-10-27T00:00:00Z"),
        )

        val json = ScheduleCacheCodec.encode(schedule)

        // encodeDefaults = true: пустые списки попадают в JSON явно, а не пропадают.
        assertTrue(json.contains("\"weeks\":[]"))
        assertTrue(json.contains("\"lessons\":[]"))
        assertEquals(schedule, ScheduleCacheCodec.decode(json))
    }

    @Test
    fun `json stores dates as iso strings`() {
        val schedule = GroupSchedule(
            group = group,
            weeks = listOf(
                Week(9, 9, LocalDate.of(2026, 10, 26), WeekKind.THEORETICAL, true),
            ),
            lessons = listOf(
                Lesson(
                    id = "lesson",
                    date = LocalDate.of(2026, 10, 26),
                    start = LocalTime.of(8, 50),
                    end = LocalTime.of(10, 25),
                    discipline = "Физика",
                    type = LessonType.PRACTICE,
                    rawType = "Практика",
                ),
            ),
            fetchedAt = Instant.parse("2026-10-27T03:15:42Z"),
        )

        val json = ScheduleCacheCodec.encode(schedule)

        assertTrue(json.contains("\"2026-10-26\""))
        assertTrue(json.contains("\"08:50\""))
        assertTrue(json.contains("2026-10-27T03:15:42Z"))
    }

    @Test
    fun `broken json decodes to null`() {
        assertNull(ScheduleCacheCodec.decode(""))
        assertNull(ScheduleCacheCodec.decode("не json"))
        assertNull(ScheduleCacheCodec.decode("{"))
        assertNull(ScheduleCacheCodec.decode("[]"))
        assertNull(ScheduleCacheCodec.decode("{}"))
        assertNull(ScheduleCacheCodec.decode("""{"group":{}}"""))
    }

    @Test
    fun `teacher schedule round trip keeps groups and identity`() {
        val schedule = TeacherSchedule(
            teacher = Teacher(
                id = "35144",
                shortName = "Жидова Л.А.",
                fullName = "Жидова Любовь Александровна",
            ),
            weeks = listOf(
                Week(9, 9, LocalDate.of(2026, 10, 26), WeekKind.THEORETICAL, true),
            ),
            lessons = listOf(
                Lesson(
                    id = "teacher-lesson",
                    date = LocalDate.of(2026, 10, 26),
                    start = LocalTime.of(8, 50),
                    end = LocalTime.of(10, 25),
                    pairNumber = 1,
                    discipline = "История России",
                    type = LessonType.LECTURE,
                    rawType = "Лекция",
                    groups = listOf("716-1", "716-2"),
                ),
            ),
            fetchedAt = Instant.parse("2026-10-27T03:15:42Z"),
        )

        val json = ScheduleCacheCodec.encodeTeacher(schedule)

        assertEquals(schedule, ScheduleCacheCodec.decodeTeacher(json))
        assertTrue(json.contains("\"716-1\""))
        assertNull(ScheduleCacheCodec.decodeTeacher("{})"))
    }

    @Test
    fun `старый кэш без групп читается с пустым списком`() {
        val legacy = """
            {
              "group": {"facultyCode": "fb", "slug": "714-2", "name": "714-2"},
              "weeks": [],
              "lessons": [
                {
                  "id": "x",
                  "date": "2026-10-26",
                  "start": "08:50",
                  "end": "10:25",
                  "discipline": "Физика",
                  "type": "LECTURE",
                  "rawType": "Лекция"
                }
              ],
              "fetchedAt": "2026-10-27T03:15:42Z"
            }
        """.trimIndent()

        val decoded = ScheduleCacheCodec.decode(legacy)

        assertTrue(decoded != null)
        assertTrue(decoded!!.lessons.single().groups.isEmpty())
    }
}
