package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.KFeature
import com.victor.kodiakmaps.core.KGeo
import com.victor.kodiakmaps.ui.PoiMap
import com.victor.kodiakmaps.ui.UpdateCheck
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.5.2: what each Military-layer structure was (mil_info.py), and darker old-map building styling. */
class StructureInfoTest {
    private val text = File("src/main/assets/kodiak/poi.geojson").readText()
    private val mil = KGeo.parse(text).filter { it.k == "poi_mil" }
    private fun id(f: KFeature) = f.props.optString("id")
    private fun rowList(f: KFeature) = f.props.optString("rows").split('\u001f').map { it.substringBefore('\u001e') to it.substringAfter('\u001e') }
    private fun rows(f: KFeature) = rowList(f).toMap()
    private val traced = mil.filter { id(it).startsWith("milh_") }
    private val gps = mil.filter { listOf("milp_", "milq_", "chm_", "kd_").any { p -> id(it).startsWith(p) } }   // v1.5.11: mil1-4 now carry "detail" but are the original markers, not field-GPS cards
    private val groups = mil.filter { id(it).startsWith("milg_") }

    private fun checkCard(f: KFeature) {
        val r = rowList(f)
        assertEquals(f.title, "Summary", r.first().first)
        assertTrue(f.title, r.first().second.isNotBlank())
        val m = r.toMap(); val what = m["What it was"]
        if (what != null) {
            val src = r.firstOrNull { it.first == "Sources" }?.second ?: ""   // group cards keep their own survey "Sources" row further down
            if (Regex("\\[\\d+]").containsMatchIn(what)) assertTrue(f.title, src.isNotBlank())
            val n = if (src.isBlank()) 0 else src.lines().size
            if (src.isNotBlank()) src.lines().forEachIndexed { i, l -> assertTrue("${f.title}: $l", l.startsWith("[${i + 1}] ") && Regex(" — https?://\\S+$").containsMatchIn(l)) }
            what.lines().forEach { l ->
                assertTrue("${f.title}: $l", l.startsWith("• "))
                assertTrue("${f.title}: $l", Regex(" — (documented|likely, from building shape and position|likely, from the label and site|no record found)( (\\[\\d+])+)?$").containsMatchIn(l))
                if (l.contains("— documented")) assertTrue("documented fact needs a source: ${f.title}: $l", Regex("\\[\\d+]$").containsMatchIn(l))
                Regex("\\[(\\d+)]").findAll(l).forEach { assertTrue(f.title, it.groupValues[1].toInt() in 1..n) }
            }
        }
    }

    @Test fun everyTracedStructureHasSummaryFirst() {
        assertEquals(64 + Hist1965Test.COUNTS.values.sum(), traced.size); traced.forEach(::checkCard)   // v1.5.3: + 1965 Navy sheets
        // the 1.5.1 rows are all still there after the new ones
        traced.forEach { f -> val r = rows(f); assertTrue(f.title, r.containsKey("On the map") && r.containsKey("Source map") && r.containsKey("Approx. accuracy")) }
    }

    @Test fun everyFieldGpsCardHasSummaryUnlessNothingIsKnown() {
        val withInfo = gps.filter { rows(it).containsKey("Summary") }
        withInfo.forEach(::checkCard)
        assertEquals(108, withInfo.size)   // v1.5.11: + 33 later chm_/kd_ cards
        // non-structure single points with no record get no info block
        assertEquals(setOf("Cliff Pt Pipe", "Cliff Pt Other", "P2V-7 crash site", "Abercrombie end of Spruce Island submarine cable"), (gps - withInfo.toSet()).map { it.title }.toSet())
        groups.forEach(::checkCard)
    }

    @Test fun knownFacts() {
        fun s(t: String) = rows((traced + gps).first { it.title == t })["Summary"]!!
        assertTrue(s("Composite Bldg.").contains("mess hall"))
        assertTrue(s("Structure on Isador Island (unlabelled)").contains("Battle Allowance"))
        assertEquals("D-20: recreation building", s("D20"))
        assertTrue(s("D5?").contains("identification unknown"))
        assertTrue(s("D18").startsWith("Not in the 1965 structure list"))
        assertTrue(s("BAT4 P&S").contains("Plotting and Spotting"))
        assertTrue(s("SCR582").contains("SCR-582"))
        assertTrue(s("Gibson Cove BES").contains("B1/3"))
        assertTrue(s("Mansfield BES").contains("B2/3"))
        // the B1/3 field label vs annex conflict is spelled out on the card
        assertTrue(rows(gps.first { it.title == "BAT3 B1/3" })["What it was"]!!.contains("Gibson Cove"))
        // roads are roads
        listOf("Main Access Road", "P.O.L. Rd.", "Pumphouse Rd.").forEach { assertTrue(it, s(it).startsWith("Road as drawn")) }
        // annex description added to the original battery markers
        listOf("mil3", "mil4").forEach { i -> assertTrue(i, rows(mil.first { id(it) == i }).containsKey("1944 Harbor Defense annex")) }
    }

    @Test fun glossaryInMeta() {
        val g = JSONObject(text).getJSONObject("meta").getJSONArray("mil_glossary")
        val items = (0 until g.length()).map { g.getJSONObject(it) }
        assertTrue(items.size >= 25)
        items.forEach { assertTrue(it.toString(), it.getString("status").split(" ")[0] in setOf("confirmed", "likely", "unconfirmed")) }
        items.filter { it.getString("status") == "confirmed" }.forEach { assertTrue(it.toString(), it.getJSONArray("sources").length() > 0) }
        assertEquals("unconfirmed", items.first { it.getString("abbr") == "EBC" }.getString("status"))
        assertEquals("confirmed", items.first { it.getString("abbr") == "P&S" }.getString("status"))
    }

    @Test fun noLandownerNamesInTheNewStructureInfo() {
        val owners = Regex("(?i)\\b(LLC|Inc\\.|Trust|Estate of|Koniag|Leisnoi|Natives of)\\b")
        val newRows = setOf("Summary", "What it was", "Terms", "Sources", "1944 Harbor Defense annex")
        mil.forEach { f -> rowList(f).filter { it.first in newRows }.forEach { assertFalse(f.title, owners.containsMatchIn(it.second)) } }
    }

    private fun lum(hex: String): Double {
        val c = Integer.parseInt(hex.removePrefix("#"), 16)
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c shr 16 and 255) + 0.7152 * ch(c shr 8 and 255) + 0.0722 * ch(c and 255)
    }

    @Test fun buildingsAreDarkerWithAnOutline() {
        assertTrue(lum(PoiMap.FILL) < lum("#8D6E63") * 0.5)        // 1.5.1 fill was #8D6E63 at 0.45
        assertTrue(PoiMap.FILL_OPACITY_BLDG > 0.45f && PoiMap.FILL_OPACITY_BLDG < 0.8f)   // darker, but the base map still shows through
        assertTrue(lum(PoiMap.EDGE) < lum(PoiMap.FILL))
        assertTrue(PoiMap.EDGE_W_Z14 >= 1.5f && PoiMap.EDGE_W_Z16 > PoiMap.EDGE_W_Z14)
        assertTrue(lum(PoiMap.LINE_BOUNDARY) < lum("#D7CCC8") * 0.3)
        assertTrue(PoiMap.HIST_PT_MIN_ZOOM < PoiMap.DETAIL_MIN_ZOOM)
        assertTrue(PoiMap.TAP.contains("kd-poi-hedge") && PoiMap.TAP.contains("kd-poi-hpt"))
    }

    @Test fun update152IsNewerThan151() {
        val html = """<a href="https://github.com/ChiniakVictor/sovereign-apps/releases/download/kodiak-maps-android-v1.5.2/kodiak-maps-1.5.2.apk">x</a>"""
        assertEquals("1.5.2", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.5.2", "1.5.1") > 0)
    }

    @Test fun update153IsNewerThan152() {
        val html = """<a href="https://github.com/ChiniakVictor/sovereign-apps/releases/download/kodiak-maps-android-v1.5.3/kodiak-maps-1.5.3.apk">x</a>"""
        assertEquals("1.5.3", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.5.3", "1.5.2") > 0)
    }
}
