package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class FishingTest {
    private val spots = KGeo.parse(File("src/main/assets/kodiak/fishing.geojson").readText())
    private val regs = Fishing.parseRegs(File("src/main/assets/kodiak/fishing_regs.json").readText())

    @Test fun bundledSpots() {
        assertEquals(44, spots.size)
        assertTrue(spots.all { it.k == "fish" && it.point != null && KodiakArea.contains(it.point!![0], it.point!![1]) })
        assertTrue(spots.all { it.props.getString("type") in Fishing.TYPE_COLOR.keys })
        assertTrue(spots.all { it.props.getString("acc") in setOf("good", "fair") })
        assertEquals(spots.size, spots.map { it.props.getString("name") }.toSet().size)
        assertTrue(spots.all { regs.sets.containsKey(it.props.getString("regkey")) })
        assertEquals(17, spots.count { it.props.getString("type") == "lake" })
    }

    @Test fun regsFile() {
        assertEquals(2026, regs.year)
        assertTrue(regs.eoUrl.startsWith("https://www.adfg.alaska.gov/"))
        assertTrue(regs.sets.values.all { s -> s.rules.all { it.cite != null } })
    }

    @Test fun seasons() {
        val dog = regs.sets.getValue("dogsalmon").rules.first { it.species == "King salmon" }
        assertEquals(Fishing.Status.OPEN, Fishing.season(dog, LocalDate.of(2026, 7, 1), 2026).status)
        val c = Fishing.season(dog, LocalDate.of(2026, 10, 12), 2026)
        assertEquals(Fishing.Status.CLOSED, c.status); assertTrue(c.text.contains("rest of 2026"))
        val ling = regs.sets.getValue("salt_road").rules.first { it.species == "Lingcod" }
        assertEquals("Closed · opens Jul 1", Fishing.season(ling, LocalDate.of(2026, 3, 1), 2026).text)
        val tanner = regs.sets.getValue("salt_road").rules.first { it.species == "Tanner crab" }
        assertEquals(Fishing.Status.UNKNOWN, Fishing.season(tanner, LocalDate.of(2026, 3, 1), 2026).status)
        val karluk = regs.sets.getValue("karluk").rules.first()
        assertEquals(Fishing.Status.CLOSED, Fishing.season(karluk, LocalDate.of(2026, 6, 1), 2026).status)
        assertEquals(Fishing.Status.UNKNOWN, Fishing.season(ling, LocalDate.of(2027, 8, 1), 2026).status)
    }

    @Test fun regsCheck() {
        assertEquals(2027, Fishing.newestYearOnPage("a 2026sw_sfregs_kodiak_fresh.pdf b 2027sw_sfregs_kodiak_fresh.pdf"))
        assertTrue(Fishing.checkMessage(2026, 2027).contains("2027"))
        assertTrue(Fishing.checkMessage(2026, 2026).startsWith("Up to date"))
    }
}

class TidesTest {
    private val set = Tides.parse(File("src/main/assets/kodiak/tides.json").readText())
    @Test fun bundled() {
        assertTrue(set.stations.size >= 5)
        assertTrue(set.stations.any { it.id == "9457292" })
        assertTrue(set.stations.all { s -> Tides.lastDate(s)!! >= LocalDate.of(2027, 10, 12) })
        val s = set.stations.first { it.id == "9457292" }
        val d = Tides.day(s, LocalDate.of(2026, 10, 12)); assertTrue(d.size in 3..4)
        val e = s.events[100]; assertEquals(e.ft, Tides.heightAt(s, e.t.plusSeconds(1))!!, 0.05)
        assertTrue(Tides.fmt(e).matches(Regex("(High|Low) \\d{1,2}:\\d\\d [AP]M -?\\d+\\.\\d ft")))
    }
}

class ContrastTest {
    private fun lum(c: Long): Double { fun ch(v: Long) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255) }
    private fun ratio(a: Long, b: Long) = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)
    @Test fun textOnSheets() {
        val sheet = 0x121519L; val surface = 0x161A1FL
        // theme onSurface / onSurfaceVariant and every fish label colour vs the dark sheet: WCAG AA (4.5:1)
        listOf(0xECEFF1L, 0xB0BEC5L).forEach { assertTrue(ratio(it, sheet) >= 4.5); assertTrue(ratio(it, surface) >= 4.5) }
        Fishing.TYPE_COLOR.keys.forEach { t -> assertTrue(t, ratio(java.lang.Long.parseLong(Fishing.textColor(t).drop(1), 16), sheet) >= 4.5) }
        // season badges: black text on green / red / grey
        listOf(0x81C784L, 0xE57373L, 0xB0BEC5L).forEach { assertTrue(ratio(0x000000L, it) >= 4.5) }
    }
}
