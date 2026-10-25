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
        assertEquals(42, by["poi_food"]); assertEquals(17, by["poi_travel"]); assertEquals(10, by["poi_school"])
        assertEquals(8, by["poi_zone"]); assertEquals(92, by["poi_mil"]); assertEquals(1, by["poi_comm"])
        val food = fs.filter { it.k == "poi_food" }.groupingBy { it.props.optString("cat") }.eachCount()
        assertEquals(30, food["prepared"]); assertEquals(12, food["grocery"])
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

    @Test fun chiniakSchoolAndLibraryAreSeparate() {
        fun pt(id: String) = fs.first { it.props.optString("id") == id }.point!!
        val s = pt("sch_chiniak"); val l = pt("comm0")
        // School on the KIB "CHINIAK SCHOOL" tract (42650 Chiniak Hwy); library at the KIB Tsunami Shelter parcel (43235 Spruce Way).
        assertEquals(57.61352, s[0], 1e-4); assertEquals(-152.23467, s[1], 1e-4)  // point = [lat, lon]
        assertEquals(57.61170, l[0], 1e-4); assertEquals(-152.23390, l[1], 1e-4)
        val dy = (s[0] - l[0]) * 110540.0; val dx = (s[1] - l[1]) * 111320.0 * Math.cos(Math.toRadians(57.6))
        assertTrue("school/library too close", Math.hypot(dx, dy) > 150.0)
        assertFalse(fs.first { it.props.optString("id") == "comm0" }.props.optString("rows").contains("approximate", ignoreCase = true))
    }

    @Test fun githubAssetNameParsesAsUpdate() {
        val html = """<a href="https://github.com/ChiniakVictor/sovereign-apps/releases/download/kodiak-maps-android-v1.4.1/kodiak-maps-1.4.1.apk">x</a>"""
        assertEquals("1.4.1", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.4.1", "1.4") > 0)
    }

    @Test fun closedRestaurantsAreGone() {
        // Victor 2026-10-24: Road's End and Kalsin Bay Inn are closed for good.
        val names = fs.map { it.props.optString("name") }
        assertFalse(names.any { it.contains("Road's End", true) || it.contains("Roads End", true) })
        assertFalse(names.any { it.contains("Kalsin Bay Inn", true) })
        listOf("Olds River Inn Restaurant & Lounge", "The Rendezvous Bar & Grill", "Java Flats", "Bell's Flats Little Store").forEach { assertTrue(it, it in names) }
        assertEquals(fs.size, fs.map { it.props.optString("id") }.toSet().size)
    }

    @Test fun update142IsNewerThan141() {
        val html = """<a href="https://github.com/ChiniakVictor/sovereign-apps/releases/download/kodiak-maps-android-v1.4.2/kodiak-maps-1.4.2.apk">x</a>"""
        assertEquals("1.4.2", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.4.2", "1.4.1") > 0)
    }
}
