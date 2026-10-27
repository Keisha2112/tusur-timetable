package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.LessonType

/**
 * Соответствие типа занятия из расписания доменному перечислению.
 * Сравнение терпит лишние пробелы, регистр и разницу «е»/«ё».
 */
object LessonTypeMapper {

    private val WHITESPACE = Regex("""\s+""")

    fun map(rawType: String): LessonType = when (normalize(rawType)) {
        "лекция" -> LessonType.LECTURE
        "практика" -> LessonType.PRACTICE
        "лабораторная работа" -> LessonType.LABORATORY
        "научно-исследовательская работа" -> LessonType.RESEARCH
        "консультация" -> LessonType.CONSULTATION
        "экзамен" -> LessonType.EXAM
        // После нормализации «Зачёт» и «Зачет» — одна и та же строка.
        "зачет" -> LessonType.CREDIT
        else -> LessonType.OTHER
    }

    private fun normalize(rawType: String): String =
        rawType.trim()
            .lowercase()
            .replace('ё', 'е')
            .replace(WHITESPACE, " ")
}
