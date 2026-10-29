package com.keisha.tusur.timetable.data.update

/**
 * Сравнение версий из `versionName` приложения и тега GitHub-релиза.
 *
 * Версия — набор чисел через точку: `1.2`, `v1.1.10`, `1.1.2-debug`.
 * Хвост после `-` (или `+`) отбрасывается: это метка сборки, а не номер версии.
 */
internal object AppVersion {

    /** "v1.1.2" / "1.1.2" -> [1, 1, 2]; null для невалидной версии. */
    fun parse(raw: String): List<Int>? {
        val normalized = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')

        if (normalized.isEmpty()) return null

        val parts = normalized.split('.')
        // Компонент — только цифры: знаки и пустые части версией не считаем.
        if (parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) return null

        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** true, только если обе версии разобраны и [candidate] строго больше [current]. */
    fun isNewer(current: String, candidate: String): Boolean {
        val currentParts = parse(current) ?: return false
        val candidateParts = parse(candidate) ?: return false

        val size = maxOf(currentParts.size, candidateParts.size)
        for (index in 0 until size) {
            val left = currentParts.getOrElse(index) { 0 }
            val right = candidateParts.getOrElse(index) { 0 }
            if (left != right) return right > left
        }

        return false
    }
}
