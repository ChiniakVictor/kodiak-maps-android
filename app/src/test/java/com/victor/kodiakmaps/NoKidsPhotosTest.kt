package com.victor.kodiakmaps

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.5.12: photos showing children (or people who may be minors) are removed from every manifest and the bundle. */
class NoKidsPhotosTest {
    private val dir = File("src/main/assets/kodiak/photos")
    private val removed = mapOf(
        "fish.json" to listOf("c000", "c002", "c003", "c006"),
        "kad_deer.json" to listOf("k_long_is_deer7_1200x800"))
    private val files = listOf("places/c000_first_fish_35011266233.webp", "places/c002_fishing_buddies_34980618264.webp",
        "places/c003_bring_your_boots_35688574031.webp", "places/c006_reeling_in_34980597954.webp", "kadiak/deer/long_is_deer7_1200x800.webp")

    @Test fun removedPhotosAreGone() {
        removed.forEach { (m, pids) ->
            val j = JSONObject(File(dir, m).readText()); val ph = j.getJSONObject("photos"); val fs = j.getJSONObject("features")
            pids.forEach { pid -> assertFalse("$m $pid", ph.has(pid))
                fs.keys().forEach { k -> val a = fs.getJSONArray(k); for (i in 0 until a.length()) assertNotEquals("$m $k", pid, a.getString(i)) } }
        }
        files.forEach { assertFalse(it, File(dir, it).exists()) }
        // every manifest: no file reference to a removed image
        dir.listFiles()!!.filter { it.name.endsWith(".json") }.forEach { m -> val t = m.readText(); files.forEach { assertFalse("${m.name} $it", t.contains(it)) } }
    }

    @Test fun buskinPinsKeepTheirOtherPhotos() {
        val f = JSONObject(File(dir, "fish.json").readText()).getJSONObject("features")
        assertEquals(listOf("c001", "c004", "c005"), (0 until f.getJSONArray("fish:Buskin River").length()).map { f.getJSONArray("fish:Buskin River").getString(it) })
        assertEquals(3, f.getJSONArray("fish:Buskin Lake").length())
    }
}
