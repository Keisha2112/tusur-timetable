package com.keisha.tusur.timetable.data

import com.keisha.tusur.timetable.data.network.TusurClient
import com.keisha.tusur.timetable.data.network.Urls
import com.keisha.tusur.timetable.data.parser.CatalogParser
import com.keisha.tusur.timetable.data.parser.IcsParser
import com.keisha.tusur.timetable.data.parser.WeekListParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Ручная проверка живого сайта. В обычном прогоне пропускается:
 * тест зависит от внешнего сервиса и от интернета.
 *
 * Запуск: ./gradlew :app:testDebugUnitTest --tests "*LiveSiteSmokeTest"
 */
@Ignore("Требует интернет и доступ к timetable.tusur.ru; запускать вручную")
class LiveSiteSmokeTest {

    private val client = TusurClient()

    @Test
    fun `live site still matches parsers`() = runBlocking {
        val facultiesHtml = client.get(Urls.faculties()).body.orEmpty()
        val faculties = CatalogParser().parseFaculties(facultiesHtml)
        assertTrue("факультеты не разобраны", faculties.isNotEmpty())

        val groupsHtml = client.get(Urls.faculty("fb")).body.orEmpty()
        val groups = CatalogParser().parseGroups(groupsHtml, "fb")
        assertTrue("группы не разобраны", groups.isNotEmpty())

        val group = groups.first { it.slug == "714-2" }
        val weeksHtml = client.get(Urls.group(group.facultyCode, group.slug)).body.orEmpty()
        val weeks = WeekListParser().parse(weeksHtml)
        assertTrue("недели не разобраны", weeks.size >= 20)

        val ics = client.get(Urls.groupIcs(group.facultyCode, group.slug)).body.orEmpty()
        val lessons = IcsParser().parse(ics)
        assertTrue("занятия не разобраны", lessons.isNotEmpty())

        println(
            "live: faculties=${faculties.size}, groups=${groups.size}, " +
                "weeks=${weeks.size}, lessons=${lessons.size}",
        )
    }
}
