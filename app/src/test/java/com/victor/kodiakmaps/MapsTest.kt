package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class MapsTest {
    private fun gz(n: String) = GZIPInputStream(File("src/main/assets/kodiak/$n").inputStream()).bufferedReader().readText()

    @Test fun gpxWritesTracksAndPins() {
        val pts = listOf(Gpx.Pt(57.6, -152.4, 10.0, 0), Gpx.Pt(57.601, -152.4, null, 60_000), Gpx.Pt(57.602, -152.4, null, 60_000 + 11 * 60_000))
        val segs = Gpx.segments(pts)
        assertEquals(2, segs.size)
        val x = Gpx.write(listOf(Gpx.Trk("A & B", segs)), listOf(Gpx.Wpt(57.0, -152.0, "Camp <1>", "", 0)), 0)
        assertTrue(x.contains("<trkseg>")); assertEquals(2, Regex("<trkseg>").findAll(x).count())
        assertTrue(x.contains("A &amp; B")); assertTrue(x.contains("Camp &lt;1&gt;")); assertTrue(x.contains("<ele>10.0</ele>"))
        assertEquals(111.2, Gpx.lengthM(pts.take(2)), 1.0)
    }
    @Test fun gpxAcceptFilter() {
        val a = Gpx.Pt(57.6, -152.4, null, 0)
        assertTrue(Gpx.accept(null, a, 5f)); assertFalse(Gpx.accept(null, a, 80f))
        assertFalse(Gpx.accept(a, a.copy(time = 2000), 5f)); assertTrue(Gpx.accept(a, a.copy(lat = 57.6001, time = 2000), 5f))
        assertTrue(Gpx.accept(a, a.copy(time = 61_000), 5f))
    }
    @Test fun geoShare() {
        assertEquals("geo:57.600000,-152.400000?q=57.600000,-152.400000(Bear%20camp)", PinUtil.geoUri(57.6, -152.4, "Bear camp"))
        assertTrue(PinUtil.shareText("X", 57.6, -152.4).contains("https://maps.google.com/?q=57.600000,-152.400000"))
    }
    @Test fun coords() {
        assertEquals("57.50000, -152.25000", Coords.format(57.5, -152.25, 0))
        assertEquals("57°30.000'N  152°15.000'W", Coords.format(57.5, -152.25, 1))
        assertEquals("57°30'00.0\"N  152°15'00.0\"W", Coords.format(57.5, -152.25, 2))
    }
    @Test fun everyEasementHasAccuracyNote() {
        val fs = KGeo.parse(gz("easements.geojson.gz")).filter { it.k == "esmt" }
        assertTrue(fs.size > 300)
        assertTrue(fs.all { it.props.optString("acc").isNotBlank() && it.props.optString("accq") in setOf("ok", "fair", "poor") })
        val chiniak = fs.first { it.props.optString("ein") == "36c D9, L" && it.props.optString("geom") == "pt" }
        // 2026-10-17 BLM data: this Chiniak site now plots on land (was offshore in earlier BLM data)
        assertEquals("ok", chiniak.props.getString("accq"))
        assertTrue(fs.filter { it.props.optString("accq") == "poor" }.all { it.props.getString("acc").contains("⚠") || it.props.getString("acc").contains("offset") || it.props.getString("acc").contains("error") })
    }
    @Test fun propertyAndRoadLayers() {
        val parcels = JSONObject(gz("parcels.geojson.gz")).getJSONArray("features")
        assertTrue(parcels.length() > 8000)
        val withOwner = (0 until parcels.length()).count { parcels.getJSONObject(it).getJSONObject("properties").has("owner") }
        assertTrue(withOwner > parcels.length() * 0.9)
        val roads = KGeo.parse(gz("roads.geojson.gz"))
        assertTrue(roads.any { it.title == "Chiniak Highway" }); assertTrue(roads.count { it.props.has("label") } > 1000)
        assertTrue(KGeo.parse(gz("ownership.geojson.gz")).any { it.props.optString("owner").contains("Leisnoi") })
    }
}
