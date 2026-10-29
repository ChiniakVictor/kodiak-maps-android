package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.KGeo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.5.9: kadiak.org (Joe Stevens) photo manifests and the new kd_* military points. */
class Kadiak159Test {
    private val base = File("src/main/assets/kodiak")
    private val fs = KGeo.parse(File(base, "poi.geojson").readText())
    private val ids = fs.map { it.props.optString("id") }.toSet()
    private val mans = base.resolve("photos").listFiles()!!.filter { it.name.startsWith("kad_") && it.name.endsWith(".json") }
    private val src = File("src/main/java/com/victor/kodiakmaps/ui/PhotoGallery.kt").readText()
    private val bad = Regex("(?i)\\(at\\)|@|\\b\\d{3}[-. ]\\d{3}[-. ]\\d{4}\\b|PO box|\\b\\w+ at \\w+\\.(net|com|org)\\b")

    @Test fun manifestsComplete() {
        assertEquals(23, mans.size)
        var n = 0
        mans.forEach { m ->
            assertTrue(m.name, src.contains("\"kodiak/photos/${m.name}\""))
            val j = JSONObject(m.readText()); val s = j.getJSONObject("source")
            assertTrue(s.getString("url").startsWith("https://www.kadiak.org/")); assertTrue(s.getString("label").startsWith("kadiak.org"))
            val ph = j.getJSONObject("photos"); n += ph.length()
            ph.keys().forEach { k -> val p = ph.getJSONObject(k)
                assertTrue(p.getString("file"), File(base, "photos/" + p.getString("file")).length() > 1000)
                listOf("caption", "credit").forEach { assertFalse("$k $it", bad.containsMatchIn(p.getString(it))) }
                assertTrue(p.getString("credit").contains("kadiak.org")) }
            val f = j.getJSONObject("features")
            f.keys().forEach { fid -> assertTrue("${m.name} $fid", fid in ids)
                val a = f.getJSONArray(fid); for (i in 0 until a.length()) assertTrue(ph.has(a.getString(i))) }
        }
        assertEquals(155, n)  // 1.5.14: modern colour photos showing people removed (1.5.12: 174)
    }

    @Test fun newPoints() {
        val kd = fs.filter { it.props.optString("id").startsWith("kd_") }
        assertEquals(11, kd.size)
        kd.forEach { f -> val p = f.props
            assertEquals("poi_mil", f.k); assertTrue(f.title, p.has("detail") && p.has("src_map"))
            val r = p.getString("rows"); listOf("Summary", "What it was", "Position", "Coordinates", "Sources").forEach { assertTrue("${f.title} $it", r.contains(it + "\u001e")) }
            assertEquals(f.title, p.getInt("approx") == 1, f.title.contains("(approx.)"))
            val pt = f.point!!; assertTrue(f.title, pt[0] in 57.55..58.0 && pt[1] in -152.7..-152.1)
            assertFalse(bad.containsMatchIn(r)) }
    }
}
