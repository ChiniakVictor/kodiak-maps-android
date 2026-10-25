package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.KGeo
import com.victor.kodiakmaps.ui.HistMaps
import com.victor.kodiakmaps.ui.UpdateCheck
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.5: field-GPS military points (mil_extra.py) and kadiak.org historic maps. */
class MilHistTest {
    private val fs = KGeo.parse(File("src/main/assets/kodiak/poi.geojson").readText())
    private val mil = fs.filter { it.k == "poi_mil" }
    private fun id(f: com.victor.kodiakmaps.core.KFeature) = f.props.optString("id")
    private val detail = mil.filter { it.props.has("detail") }
    private val summary = mil.filter { id(it).startsWith("milg_") }
    private val single = mil.filter { id(it).startsWith("milq_") }
    private val original = mil.filter { id(it).matches(Regex("mil\\d")) }
    private val hist = HistMaps.parse(File("src/main/assets/kodiak/hist/index.json").readText())

    private fun distM(a: DoubleArray, b: DoubleArray): Double {
        val dy = (a[0] - b[0]) * 111132.0; val dx = (a[1] - b[1]) * 111320.0 * Math.cos(Math.toRadians(a[0])); return Math.hypot(dx, dy)
    }

    @Test fun counts() {
        assertEquals(92, mil.size)
        assertEquals(72, detail.size); assertEquals(8, summary.size); assertEquals(7, single.size); assertEquals(5, original.size)
        val g = detail.groupingBy { it.props.optString("grp") }.eachCount()
        assertEquals(mapOf("BAT1" to 3, "BAT2" to 1, "BAT3" to 2, "BUSKIN" to 10, "BAT4" to 23, "LICAMP" to 3, "BAT5" to 9, "BAT6" to 10, "SL23" to 4, "SL25" to 7), g)
    }

    @Test fun originalPointsUnchanged() {
        val want = mapOf("mil0" to (57.62420 to -152.19482), "mil1" to (57.61429 to -152.19594), "mil2" to (57.59811 to -152.17988),
            "mil3" to (57.59 to -152.15722), "mil4" to (57.61917 to -152.22252))
        want.forEach { (i, ll) -> val p = original.first { id(it) == i }.point!!; assertEquals(ll.first, p[0], 1e-6); assertEquals(ll.second, p[1], 1e-6) }
    }

    @Test fun coordinatesInRange() {
        mil.forEach { f -> val p = f.point!!
            assertTrue(f.title, p[0] in 56.5..58.7 && p[1] in -154.9..-151.5) }  // Kodiak archipelago
        (detail + single + summary).forEach { f -> val p = f.point!!
            assertTrue(f.title, p[0] in 57.55..57.95 && p[1] in -152.70..-152.10) }  // surveyed sites: Chiniak, Buskin, Long Island, Fort Abercrombie, Spruce Island, Kizhuyak
        // spot checks: DM -> decimal
        fun pt(label: String) = detail.first { it.title == label }.point!!
        assertEquals(57.0 + 46.364 / 60, pt("BAT4 GUN1/DP")[0], 1e-6); assertEquals(-(152.0 + 14.490 / 60), pt("BAT4 GUN1/DP")[1], 1e-6)
        val g = single.first { it.title == "Gibson Cove BES" }.point!!
        assertEquals(57.0 + 46.0 / 60 + 31.7 / 3600, g[0], 1e-6); assertEquals(-(152.0 + 27.0 / 60 + 14.6 / 3600), g[1], 1e-6)
    }

    @Test fun noDuplicates() {
        assertEquals(fs.size, fs.map { id(it) }.toSet().size)
        assertEquals(detail.size + single.size, (detail + single).map { it.title }.toSet().size)
        val pts = (detail + single).map { it.point!! }
        for (i in pts.indices) for (j in i + 1 until pts.size) assertTrue("same spot $i $j", distM(pts[i], pts[j]) > 1.0)
        // a new group summary never sits within 150 m of an existing Military point (BAT1/BAT2 enrich mil0/mil4 instead)
        summary.forEach { s -> (original + summary.filter { it !== s }).forEach { o -> assertTrue("${s.title} ~ ${o.title}", distM(s.point!!, o.point!!) > 150.0) } }
        assertTrue(original.first { id(it) == "mil0" }.props.optString("rows").contains("BAT1 GUN1"))
        assertTrue(original.first { id(it) == "mil4" }.props.optString("rows").contains("BAT2 GUN1"))
    }

    @Test fun surveyPointsCarryDateAltitudeAndSource() {
        detail.forEach { f -> val r = f.props.optString("rows")
            assertTrue(f.title, r.contains("Survey date\u001e") && Regex("Altitude\u001e-?\\d+ ft").containsMatchIn(r) && r.contains("Field GPS survey (Datum WGS84)"))
            val alt = Regex("Altitude\u001e(-?\\d+) ft").find(r)!!.groupValues[1].toInt()
            if (alt < 0) assertTrue("negative altitude must be flagged: ${f.title}", r.contains("negative altitude"))
            assertEquals(f.props.optString("label"), f.title)
        }
        single.forEach { assertTrue(it.title, it.props.optString("rows").contains("Field GPS survey (Datum WGS84)")) }
        // offshore points are flagged, not moved
        listOf("SL24", "SL26DEC").forEach { l -> assertTrue(l, detail.first { it.title == l }.props.optString("rows").contains("offshore")) }
        assertTrue(single.first { it.title == "Miller Pt ammo" }.props.optString("rows").contains("offshore"))
        // every group summary lists all its points
        summary.forEach { s -> detail.filter { it.props.optString("grp") == s.props.optString("grp") }.forEach { d ->
            assertTrue("${s.title} lists ${d.title}", s.props.optString("rows").contains(d.title + " · ")) } }
    }

    @Test fun historicMapsBundledAndReferenced() {
        val dir = File("src/main/assets/kodiak/hist")
        assertEquals("Source: kadiak.org (Kodiak Historical Army/Navy site)", hist.credit)
        assertEquals(32, hist.items.size)
        hist.items.forEach { assertTrue(it.file, File(dir, it.file).isFile); it.ovlFile?.let { o -> assertTrue(o, File(dir, o).isFile) } }
        assertTrue(File(dir, hist.structFile).readLines().size > 1800)
        val ids = hist.items.map { it.id }.toSet()
        mil.forEach { f -> (f.props.optString("imgs").split(',') + f.props.optString("ovl").split(',')).filter { it.isNotBlank() }.forEach { assertTrue("${f.title}: $it", it in ids) } }
        mil.forEach { f -> f.props.optString("ovl").split(',').filter { it.isNotBlank() }.forEach { o -> assertTrue(o, hist.items.first { it.id == o }.corners != null) } }
        assertTrue(dir.walk().filter { it.isFile }.sumOf { it.length() } < 5_000_000L)
    }

    @Test fun overlaysAreSaneParallelograms() {
        val ov = hist.items.filter { it.corners != null }
        assertEquals(setOf("chiniak1949topo", "chiniak1949topo2", "li_map"), ov.map { it.id }.toSet())
        ov.forEach { o -> val c = o.corners!!
            assertEquals(4, c.size)
            c.forEach { assertTrue(o.id, it[1] in 57.4..57.9 && it[0] in -152.6..-152.0) }
            // affine fit: TL + BR == TR + BL
            assertEquals(o.id, c[0][0] + c[2][0], c[1][0] + c[3][0], 1e-4); assertEquals(o.id, c[0][1] + c[2][1], c[1][1] + c[3][1], 1e-4)
            assertNotNull(o.verify)
        }
        // the Long Island map covers the BAT4/BAT5 survey points
        val li = ov.first { it.id == "li_map" }.corners!!
        listOf("BAT4 GUN1/DP", "BAT5 GUN1/DP").forEach { l -> val p = detail.first { it.title == l }.point!!
            assertTrue(l, p[0] in li.minOf { it[1] }..li.maxOf { it[1] } && p[1] in li.minOf { it[0] }..li.maxOf { it[0] }) }
    }

    @Test fun update150IsNewerThan142() {
        val html = """<a href="https://github.com/ChiniakVictor/sovereign-apps/releases/download/kodiak-maps-android-v1.5/kodiak-maps-1.5.apk">x</a>"""
        assertEquals("1.5", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.5", "1.4.2") > 0)
    }
}
