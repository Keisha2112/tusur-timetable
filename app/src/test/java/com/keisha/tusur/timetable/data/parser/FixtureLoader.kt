package com.keisha.tusur.timetable.data.parser

import java.nio.charset.StandardCharsets

/**
 * Читает фикстуру из classpath (app/src/test/resources/fixtures).
 * Файлы — реальные снимки сайта в UTF-8, поэтому кодировку задаём явно.
 */
object FixtureLoader {

    fun text(name: String): String {
        val stream = FixtureLoader::class.java.classLoader?.getResourceAsStream("fixtures/$name")
            ?: error("Фикстура не найдена: fixtures/$name")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    /**
     * Прежнее имя метода: на него ещё ссылаются тесты парсеров недель и .ics.
     * Новые тесты читают фикстуры через [text].
     */
    fun read(name: String): String = text(name)
}
