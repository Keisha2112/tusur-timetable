package com.keisha.tusur.timetable.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Сериализаторы java.time для кэша расписания.
 * Храним ISO-строки, чтобы JSON оставался читаемым при отладке.
 */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
data class Faculty(
    val code: String,
    val name: String,
)

@Serializable
data class Group(
    val facultyCode: String,
    val slug: String,
    val name: String,
    val course: Int? = null,
)

@Serializable
enum class WeekKind {
    THEORETICAL,
    EXAM_SESSION,
    WEEKEND,
}

@Serializable
data class Week(
    val id: Int,
    val number: Int?,
    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,
    val kind: WeekKind,
    val isCurrent: Boolean,
)

@Serializable
enum class LessonType {
    LECTURE,
    PRACTICE,
    LABORATORY,
    RESEARCH,
    CONSULTATION,
    EXAM,
    CREDIT,
    OTHER,
}

@Serializable
data class Lesson(
    val id: String,
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    @Serializable(with = LocalTimeSerializer::class)
    val start: LocalTime,
    @Serializable(with = LocalTimeSerializer::class)
    val end: LocalTime,
    val pairNumber: Int? = null,
    val discipline: String,
    val type: LessonType,
    val rawType: String,
    val rooms: List<String> = emptyList(),
    val teachers: List<String> = emptyList(),
    /** Группы занятия: заполняется только в расписании преподавателя. */
    val groups: List<String> = emptyList(),
)

@Serializable
data class DaySchedule(
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    val lessons: List<Lesson>,
)

@Serializable
data class GroupSchedule(
    val group: Group,
    val weeks: List<Week>,
    val lessons: List<Lesson>,
    @Serializable(with = InstantSerializer::class)
    val fetchedAt: Instant,
)

@Serializable
data class Teacher(
    val id: String,
    /** Короткое имя как на сайте: «Жидова Л.А.». */
    val shortName: String,
    /** Полное ФИО из заголовка страницы преподавателя. */
    val fullName: String = shortName,
)

@Serializable
data class TeacherSchedule(
    val teacher: Teacher,
    val weeks: List<Week>,
    val lessons: List<Lesson>,
    @Serializable(with = InstantSerializer::class)
    val fetchedAt: Instant,
)
