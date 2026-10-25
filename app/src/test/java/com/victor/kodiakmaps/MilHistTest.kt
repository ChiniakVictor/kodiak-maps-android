package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.KGeo
import com.victor.kodiakmaps.ui.UpdateCheck
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.5: field-GPS military points (mil_extra.py); v1.5.1: features traced from the kadiak.org old maps (mil_hist.py). */
class MilHistTest {
    private val fs = KGeo.parse(File("src/main/assets/kodiak/poi.geojson").readText())
    private val mil = fs.filter { it.k == "poi_mil" }
    private val mil15 = mil.filter { !id(it).startsWith("milh_") }
    private fun id(f: com.victor.kodiakmaps.core.KFeature) = f.props.optString("id")
    private val detail = mil.filter { it.props.has("detail") && !id(it).startsWith("milh_") }
    private val summary = mil.filter { id(it).startsWith("milg_") }
    private val single = mil.filter { id(it).startsWith("milq_") }
    private val original = mil.filter { id(it).matches(Regex("mil\\d")) }

    private fun distM(a: DoubleArray, b: DoubleArray): Double {
        val dy = (a[0] - b[0]) * 111132.0; val dx = (a[1] - b[1]) * 111320.0 * Math.cos(Math.toRadians(a[0])); return Math.hypot(dx, dy)
    }

    @Test fun counts() {
        assertEquals(92, mil15.size); assertEquals(154 + Hist1965Test.COUNTS.values.sum(), mil.size)
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
        mil15.forEach { f -> val p = f.point!!
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

    // ---- v1.5.1: features traced from the kadiak.org old maps replace the bundled map images
    private val traced = mil.filter { id(it).startsWith("milh_") && it.props.optString("src_map") !in Hist1965Test.SHEETS }   // v1.5.3: the 1965 Navy sheets are checked in Hist1965Test
    private fun rows(f: com.victor.kodiakmaps.core.KFeature) = f.props.optString("rows").split('\u001f').associate { it.substringBefore('\u001e') to it.substringAfter('\u001e') }
    private fun allPts(f: com.victor.kodiakmaps.core.KFeature) = listOfNotNull(f.point) + f.lines.flatten() + f.polys.flatten()

    @Test fun tracedFeatureCounts() {
        assertEquals(62, traced.size)
        val bySheet = traced.groupingBy { it.props.optString("src_map") }.eachCount()
        assertEquals(mapOf("chinmap2" to 3, "chinmap3" to 6, "chinmap5" to 2, "chinmap7" to 5, "lilnav3" to 31, "track9" to 15), bySheet)
        val shapes = traced.groupingBy { it.props.optString("shape") }.eachCount()
        assertEquals(mapOf("pt" to 47, "ln" to 8, "poly" to 7), shapes)
        // points are detail points (zoom 14+), lines/polygons are not
        traced.forEach { f -> assertEquals(f.title, f.props.optString("shape") == "pt", f.props.has("detail")) }
        listOf("Composite Bldg.", "Tower C", "P.O.L. Tank", "AR-1 Low Band Array", "DW-1 Wide Band Doublet Trans Antenna No. 5", "VAM", "D20",
            "Steel Mat Runway – Miller Field", "U.S. Naval Reservation Boundary").forEach { n -> assertTrue(n, traced.any { it.title == n }) }
        assertTrue(traced.any { it.title.startsWith("U.S. Air Force Installation") })
    }

    @Test fun tracedFeaturesPlacedSourcedAndAccurate() {
        traced.forEach { f ->
            val r = rows(f)
            assertEquals(f.title, "kadiak.org (Kodiak Historical Army/Navy site)", r["Source"])
            assertTrue(f.title, r.containsKey("Source map") && r.containsKey("How it was placed") && r.containsKey("On the map"))
            val acc = f.props.getInt("acc_m"); assertTrue("${f.title} ±$acc", acc in 20..51)
            assertTrue(f.title, r["Approx. accuracy"]!!.startsWith("± $acc m"))
            assertTrue(f.title, f.props.optString("shape") == "pt" || f.point == null || f.polys.isNotEmpty())
            val pts = allPts(f); assertTrue(f.title, pts.isNotEmpty())
            pts.forEach { p -> assertTrue(f.title, p[0] in 57.57..57.65 && p[1] in -152.25..-152.12) }  // Chiniak peninsula
            assertFalse(f.props.has("imgs") || f.props.has("ovl"))
        }
        // the 226.7-acre Air Force installation polygon really is ~226.7 acres (outline only)
        val af = traced.first { it.title.startsWith("U.S. Air Force Installation") }; val ring = af.polys[0]
        val lat0 = Math.toRadians(ring[0][0]); var a = 0.0
        for (i in 0 until ring.size - 1) { val (y1, x1) = ring[i][0] * 111132.0 to ring[i][1] * 111320.0 * Math.cos(lat0); val (y2, x2) = ring[i + 1][0] * 111132.0 to ring[i + 1][1] * 111320.0 * Math.cos(lat0); a += x1 * y2 - x2 * y1 }
        assertEquals(226.7, Math.abs(a) / 2 / 4046.86, 6.0); assertEquals(0, af.props.optInt("fill", 1))
    }

    @Test fun tracedFeaturesDoNotDuplicateGpsPoints() {
        // the old-map gun circles / structure that are the same thing as a field-GPS point were merged into that card, not added again
        listOf("BAT1 GUN1", "BAT1 GUN 2", "BAT1 EBC", "BAT2 GUN1").forEach { l -> val g = detail.first { it.title == l }
            assertTrue(l, rows(g)["Old map"]!!.contains("kadiak.org"))
            traced.mapNotNull { it.point?.let { p -> it.title to p } }.forEach { (t, p) -> assertTrue("$t duplicates $l", distM(p, g.point!!) > 30.0) } }
        assertEquals(3, traced.count { it.title.startsWith("Circle") })   // chinmap7 circles 2–4
        listOf("mil0", "mil1", "mil2", "mil4").forEach { i -> assertTrue(i, rows(original.first { id(it) == i }).containsKey("Old maps")) }
        assertEquals(traced.size, traced.map { it.title + allPts(it).first()[0] }.toSet().size)
    }

    @Test fun structureListOnlyOnMatchedFeatures() {
        val sl = traced.filter { rows(it).containsKey("1965 structure list") }
        assertEquals(20, sl.size)
        sl.forEach { f -> assertEquals(f.title, "lilnav3", f.props.optString("src_map")); assertFalse(f.title, f.title.contains("?")) }
        assertTrue(rows(traced.first { it.title == "D20" })["1965 structure list"]!!.startsWith("D-20 · recreation building"))
        assertTrue(rows(traced.first { it.title == "AR-1 Low Band Array" })["1965 structure list"]!!.contains("I-21"))
        traced.filter { it.props.optString("src_map") == "lilnav3" }.forEach { assertTrue(it.title, rows(it)["Site"]!!.startsWith("Cape Chiniak NSGA")) }
    }

    @Test fun oldMapImagesNoLongerBundled() {
        assertFalse(File("src/main/assets/kodiak/hist").exists())
        assertFalse(File("src/main/java/com/victor/kodiakmaps/ui/HistMaps.kt").exists())
        assertTrue(mil.none { it.props.has("imgs") || it.props.has("ovl") })
    }

    @Test fun multiPointParses() {
        val f = KGeo.parse("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"k":"poi_mil"},"geometry":{"type":"MultiPoint","coordinates":[[-152.2,57.6],[-152.1,57.7]]}}]}""").single()
        assertEquals(57.65, f.point!![0], 1e-9); assertEquals(-152.15, f.point!![1], 1e-9)
    }

    @Test fun update151IsNewerThan150() {
        val html = """<a href="https://github.com/ChiniakVictor/sovereign-apps/releases/download/kodiak-maps-android-v1.5.1/kodiak-maps-1.5.1.apk">x</a>"""
        assertEquals("1.5.1", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.5.1", "1.5") > 0)
    }

    @Test fun update150IsNewerThan142() {
        val html = """<a href="https://github.com/ChiniakVictor/sovereign-apps/releases/download/kodiak-maps-android-v1.5/kodiak-maps-1.5.apk">x</a>"""
        assertEquals("1.5", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.5", "1.4.2") > 0)
    }
}
