package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.domain.model.LessonType
import org.junit.Assert.assertEquals
import org.junit.Test

class LessonTypeMapperTest {

    @Test
    fun `все типы занятий сопоставляются`() {
        assertEquals(LessonType.LECTURE, LessonTypeMapper.map("Лекция"))
        assertEquals(LessonType.PRACTICE, LessonTypeMapper.map("Практика"))
        assertEquals(LessonType.LABORATORY, LessonTypeMapper.map("Лабораторная работа"))
        assertEquals(LessonType.RESEARCH, LessonTypeMapper.map("Научно-исследовательская работа"))
        assertEquals(LessonType.CONSULTATION, LessonTypeMapper.map("Консультация"))
        assertEquals(LessonType.EXAM, LessonTypeMapper.map("Экзамен"))
        assertEquals(LessonType.OTHER, LessonTypeMapper.map("Курсовая работа"))
    }

    @Test
    fun `зачёт и зачет дают кредит`() {
        assertEquals(LessonType.CREDIT, LessonTypeMapper.map("Зачёт"))
        assertEquals(LessonType.CREDIT, LessonTypeMapper.map("Зачет"))
        assertEquals(LessonType.CREDIT, LessonTypeMapper.map("ЗАЧЁТ"))
        assertEquals(LessonType.CREDIT, LessonTypeMapper.map("зачет"))
    }

    @Test
    fun `регистр лишние пробелы и ё не мешают`() {
        assertEquals(LessonType.LECTURE, LessonTypeMapper.map("  лекция "))
        assertEquals(LessonType.PRACTICE, LessonTypeMapper.map("ПРАКТИКА"))
        assertEquals(LessonType.LABORATORY, LessonTypeMapper.map("Лабораторная   работа"))
        assertEquals(LessonType.RESEARCH, LessonTypeMapper.map("научно-исследовательская работа"))
        assertEquals(LessonType.CONSULTATION, LessonTypeMapper.map("КОНСУЛЬТАЦИЯ"))
        assertEquals(LessonType.EXAM, LessonTypeMapper.map("экзамен"))
    }

    @Test
    fun `неизвестный или пустой тип становится другим`() {
        assertEquals(LessonType.OTHER, LessonTypeMapper.map(""))
        assertEquals(LessonType.OTHER, LessonTypeMapper.map("   "))
        assertEquals(LessonType.OTHER, LessonTypeMapper.map("Семинар"))
        assertEquals(LessonType.OTHER, LessonTypeMapper.map("Лекция-практика"))
    }
}
