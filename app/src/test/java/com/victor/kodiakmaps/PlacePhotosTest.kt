package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.KGeo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** v1.5.11: photos on fishing, harbor/travel, trail and library pins. */
class PlacePhotosTest {
    private val dir = File("src/main/assets/kodiak/photos")
    private val mans = listOf("fish", "travel", "trails", "comm").associateWith { JSONObject(File(dir, "$it.json").readText()) }
    private val poiIds = KGeo.parse(File("src/main/assets/kodiak/poi.geojson").readText()).map { it.props.optString("id") }.toSet()
    private val fishNames = KGeo.parse(File("src/main/assets/kodiak/fishing.geojson").readText()).map { it.props.optString("name") }.toSet()
    private val trailIds = KGeo.parse(GZIPInputStream(File("src/main/assets/kodiak/trails.geojson.gz").inputStream()).bufferedReader().readText()).map { it.props.optString("id") }.toSet()

    @Test fun everyKeyIsARealPinAndEveryPhotoIsBundled() {
        mans.forEach { (n, j) ->
            assertEquals("Photos", j.getJSONObject("source").getString("heading"))
            val ph = j.getJSONObject("photos"); val fs = j.getJSONObject("features")
            fs.keys().forEach { k ->
                when {
                    k.startsWith("fish:") -> assertTrue(k, k.removePrefix("fish:") in fishNames)
                    k.startsWith("trail:") -> assertTrue(k, k.removePrefix("trail:") in trailIds)
                    else -> { assertTrue(k, k in poiIds); assertFalse("no photos on military pins: $k", k.startsWith("mil") || k.startsWith("chm_") || k.startsWith("kd_")) }
                }
                val a = fs.getJSONArray(k); assertTrue(k, a.length() > 0)
                for (i in 0 until a.length()) assertTrue("$n $k", ph.has(a.getString(i)))
            }
            ph.keys().forEach { id -> val p = ph.getJSONObject(id)
                val f = File(dir, p.getString("file")); assertTrue(f.path, f.exists() && f.name.endsWith(".webp") && f.length() < 400_000)
                val b = f.readBytes(); val s = String(b, Charsets.ISO_8859_1)
                assertFalse("EXIF/XMP left in ${f.name}", s.contains("EXIF") || s.contains("XMP ") || s.contains("GPS"))
                assertTrue(id, p.getString("caption").isNotBlank() && p.getString("credit").startsWith("Photo: ") && p.getString("url").startsWith("https://"))
                if (id.startsWith("v_")) assertEquals("Photo: Victor", p.getString("credit"))
                else {
                    assertTrue(id, p.getString("url").startsWith("https://commons.wikimedia.org/wiki/File:"))
                    // CC photos show author + licence + link; public-domain photos say so
                    if (p.has("license")) assertTrue(id, p.getString("license").startsWith("CC BY") && p.getString("licenseUrl").startsWith("https://creativecommons.org/"))
                    else assertTrue(id, p.getString("credit").contains("public domain"))
                }
            }
        }
        // no photos on food, roads, easements, parcels, logging
        val all = mans.values.flatMap { j -> j.getJSONObject("features").keys().asSequence().toList() }
        assertTrue(all.none { it.startsWith("food") || it.startsWith("poi_food") })
    }
}
