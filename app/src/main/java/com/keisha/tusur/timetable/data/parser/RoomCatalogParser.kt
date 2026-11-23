package com.keisha.tusur.timetable.data.parser

import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Building
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal class RoomCatalogParser {
    fun buildings(html: String): List<Building> = links(html).mapNotNull { (path, label) ->
        if (path.size == 2 && path[0] == "buildings") Building(path[1], label) else null
    }.distinctBy { it.code }

    fun rooms(html: String, building: Building): List<Auditorium> = links(html).mapNotNull { (path, label) ->
        val segments = if (path.firstOrNull() == "before_breaking") path.drop(1) else path
        if (segments.size != 4 || segments.take(3) != listOf("buildings", building.code, "auditoriums")) {
            return@mapNotNull null
        }
        Auditorium(building.code, segments[3], label)
    }.distinctBy { it.slug }

    private fun links(html: String): List<Pair<List<String>, String>> =
        Jsoup.parse(html, Urls.BASE).select("a[href]").mapNotNull(::link)

    private fun link(element: Element): Pair<List<String>, String>? {
        val url = element.absUrl("href").toHttpUrlOrNull() ?: return null
        val label = element.text()
        if (!url.isHttps || url.host != "timetable.tusur.ru" || url.query != null ||
            label.isBlank() || url.pathSegments.any(String::isBlank)
        ) return null
        return url.pathSegments to label
    }
}
