package com.keisha.tusur.timetable.domain.model

import java.time.LocalTime

internal object LessonNotes {
    private val spaces = Regex("""[\s\p{Z}]+""")
    private val start = Regex("""с (\d{1,2}):(\d{2})""")
    private val subgroup = Regex("""\bподгруппа\s+([а-яa-z0-9]+)""")
    private val room = Regex("""(?:^|\s)(?:гк|ф|мк|рк|улк)\s*\d""")
    private val instruction = Regex("""\b(?:аудитори[яию]|перенесено|перенос|дистанционно|онлайн)\b""")
    private val remoteNotes = setOf("эиос", "в эиос", "реализуется в эиос")
    private val cancellationNotes = setOf("отменено", "занятие отменено", "пара отменена")

    fun normalize(value: String): String =
        value.replace(spaces, " ").trim().lowercase().replace('ё', 'е')

    private fun noteText(value: String?): String =
        normalize(value.orEmpty()).trimEnd('.', '!', ' ')

    fun isCancellation(value: String?): Boolean = noteText(value) in cancellationNotes

    fun statedStart(value: String?): LocalTime? {
        val match = start.matchEntire(noteText(value)) ?: return null
        val hours = match.groupValues[1].toInt()
        val minutes = match.groupValues[2].toInt()
        return if (hours < 24 && minutes < 60) LocalTime.of(hours, minutes) else null
    }

    fun subgroupKey(value: String?): String? =
        subgroup.findAll(noteText(value)).map { it.groupValues[1] }.distinct().singleOrNull()

    /** В плоском DESCRIPTION выделяем только проверенные формулировки ТУСУРа. */
    fun isExportedNote(value: String): Boolean {
        val text = noteText(value)
        return text in remoteNotes || subgroup.containsMatchIn(text) ||
            statedStart(text) != null || text in cancellationNotes
    }

    /** Информационные комментарии показываются в карточке, но сами не создают оповещение. */
    fun operationalKey(value: String?): String? {
        val text = noteText(value)
        return when {
            text in remoteNotes -> "эиос"
            text in cancellationNotes -> null
            statedStart(text) != null -> statedStart(text).toString()
            subgroup.containsMatchIn(text) || room.containsMatchIn(text) ||
                instruction.containsMatchIn(text) -> text
            else -> null
        }
    }
}
