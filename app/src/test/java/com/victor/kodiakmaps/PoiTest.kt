package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.KGeo
import com.victor.kodiakmaps.ui.UpdateCheck
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PoiTest {
    private val json = File("src/main/assets/kodiak/poi.geojson").readText()
    private val fs = KGeo.parse(json)

    @Test fun layersAndCounts() {
        val by = fs.groupingBy { it.k }.eachCount()
        assertEquals(44, by["poi_food"]); assertEquals(17, by["poi_travel"]); assertEquals(10, by["poi_school"])
        assertEquals(8, by["poi_zone"]); assertEquals(5, by["poi_mil"]); assertEquals(1, by["poi_comm"])
        val food = fs.filter { it.k == "poi_food" }.groupingBy { it.props.optString("cat") }.eachCount()
        assertEquals(32, food["prepared"]); assertEquals(12, food["grocery"])
    }

    @Test fun everyPlaceHasNameColourSourcesAndTitle() {
        fs.forEach { f ->
            assertTrue(f.props.optString("name").isNotBlank()); assertEquals(f.props.optString("name"), f.title)
            assertTrue(f.props.optString("color").matches(Regex("#[0-9A-Fa-f]{6}")))
            assertTrue(f.props.optString("id"), f.props.optString("rows").contains("Source"))
        }
    }

    @Test fun schoolsSitInsideTheirAttendanceArea() {
        val zones = fs.filter { it.k == "poi_zone" }.associateBy { it.props.optString("cat") }
        fs.filter { it.k == "poi_school" }.forEach { s ->
            val z = zones[s.props.optString("cat")] ?: error("no zone for ${s.title}")
            val p = s.point!!
            assertTrue(s.title, z.polys.any { KGeo.inPoly(p[0], p[1], it) })
        }
    }

    @Test fun updateLinkOnKodiakking() {
        val html = """<a href="https://kodiakking.com/wp-content/uploads/2026/10/kodiak-maps-1.8.zip">x</a> <a href="https://kodiakking.com/wp-content/uploads/2026/11/kodiak-maps-1.10.zip">y</a>"""
        assertEquals("1.10", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.10", "1.9") > 0)
    }
}
