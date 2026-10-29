package com.victor.kodiakmaps

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.5.14: modern (post-1990) colour military photos that show people are removed from every manifest and the bundle.
 *  Pre-1990 looking photos (black-and-white, sepia, old prints) are kept. */
class NoPeopleColorPhotosTest {
    private val dir = File("src/main/assets/kodiak/photos")
    private val removed = mapOf(
        "chiniak_bald.json" to listOf("b1"),
        "chiniak_cg.json" to listOf("cg2", "cg7", "cg8"),
        "chiniak_chinmap.json" to listOf("cm_H", "cm_L", "cm_M3"),
        "chiniak_df.json" to listOf("dfar2_72"),
        "chiniak_isador.json" to listOf("isador1", "isador3", "isador5"),
        "chiniak_tour.json" to listOf("t03", "t10", "t12", "t13", "t18"),
        "cmdbunker.json" to listOf("cmd1", "cmd6"),
        "kad_airport.json" to listOf("k_adq_456j", "k_adq_auw5"),
        "kad_buskin.json" to listOf("k_greely_jul27_09"),
        "kad_castle.json" to listOf("k_long_is_aug03_01", "k_long_is_li11"),
        "kad_castle_scr296.json" to listOf("k_long_is_aug03_19"),
        "kad_crash.json" to listOf("k_crash_may08_17", "k_crash_may08_23"),
        "kad_deer.json" to listOf("k_long_is_deer11_1200x800"),
        "kad_gibson.json" to listOf("k_gibson_08", "k_gibson_17"),
        "kad_museum.json" to listOf("k_museum_05jan2", "k_museum_1941amb1", "k_museum_entrance_jul2001"),
        "kad_puffin.json" to listOf("k_puffin_aug23_11", "k_puffin_puffin16"),
        "kad_spruce.json" to listOf("k_spruce_cape_7", "k_spruce_cape_9"),
        "kad_whazzit.json" to listOf("k_whazzit_cl_pt_4"))
    private val files = listOf(
        "chiniak/chiniak_72.webp",
        "chiniak/chiniak_bald1.webp",
        "chiniak/chiniak_coupler.webp",
        "chiniak/chiniak_isador1.webp",
        "chiniak/chiniak_isador3.webp",
        "chiniak/chiniak_isador5.webp",
        "chiniak/chiniak_jun17_07.webp",
        "chiniak/chiniak_lloyd.webp",
        "chiniak/chiniak_lloyd_df.webp",
        "chiniak/chiniak_mar21_15.webp",
        "chiniak/chiniak_mar21_22.webp",
        "chiniak/chiniak_mar21_24.webp",
        "chiniak/chiniak_mar21_25.webp",
        "chiniak/chiniak_mar21_30.webp",
        "cmdbunker/cmd1.webp",
        "cmdbunker/cmd6.webp",
        "kadiak/airport/adq_456j.webp",
        "kadiak/airport/adq_auw5.webp",
        "kadiak/buskin/greely_jul27_09.webp",
        "kadiak/castle/long_is_aug03_01.webp",
        "kadiak/castle/long_is_li11.webp",
        "kadiak/castle_scr296/long_is_aug03_19.webp",
        "kadiak/crash/crash_may08_17.webp",
        "kadiak/crash/crash_may08_23.webp",
        "kadiak/deer/long_is_deer11_1200x800.webp",
        "kadiak/gibson/gibson_08.webp",
        "kadiak/gibson/gibson_17.webp",
        "kadiak/museum/museum_05jan2.webp",
        "kadiak/museum/museum_1941amb1.webp",
        "kadiak/museum/museum_entrance_jul2001.webp",
        "kadiak/puffin/puffin_aug23_11.webp",
        "kadiak/puffin/puffin_puffin16.webp",
        "kadiak/spruce/spruce_cape_7.webp",
        "kadiak/spruce/spruce_cape_9.webp",
        "kadiak/whazzit/whazzit_cl_pt_4.webp")

    @Test fun removedPhotosAreGoneFromListsAndBundle() {
        assertEquals(35, files.size)
        removed.forEach { (m, pids) ->
            val j = JSONObject(File(dir, m).readText()); val ph = j.getJSONObject("photos"); val fs = j.getJSONObject("features")
            pids.forEach { pid -> assertFalse("$m $pid", ph.has(pid))
                fs.keys().forEach { key -> val a = fs.getJSONArray(key); for (i in 0 until a.length()) assertNotEquals("$m $key", pid, a.getString(i)) } }
        }
        files.forEach { assertFalse(it, File(dir, it).exists()) }
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { m -> val t = m.readText(); files.forEach { assertFalse("${m.name} $it", t.contains(it)) } }
    }

    @Test fun oldPhotosWithPeopleAreKept() {
        listOf("aftrack/first_team.webp", "chiniak/chiniak_lloyd_2.webp", "chiniak/chiniak_lloyd_4.webp", "chiniak/dangel_light3.webp",
            "chiniak/dangel_bald7.webp", "kadiak/abercrombie/abercrombie_burton2.webp", "kadiak/pillar/wacs_r_ak2.webp", "kadiak/deer_scr582/long_is_dan.webp")
            .forEach { assertTrue(it, File(dir, it).length() > 1000) }
    }

    @Test fun everyManifestReferenceStillResolves() {
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { m ->
            val j = JSONObject(m.readText()); if (!j.has("photos")) return@forEach
            val ph = j.getJSONObject("photos"); val fs = j.getJSONObject("features")
            ph.keys().forEach { k -> assertTrue("${m.name} $k", File(dir, ph.getJSONObject(k).getString("file")).exists()) }
            fs.keys().forEach { key -> val a = fs.getJSONArray(key); assertTrue("${m.name} $key empty", a.length() > 0)
                for (i in 0 until a.length()) assertTrue("${m.name} $key", ph.has(a.getString(i))) }
        }
    }
}
