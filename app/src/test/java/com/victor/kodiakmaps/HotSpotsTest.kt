package com.victor.kodiakmaps

import com.victor.kodiakmaps.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class HotSpotsTest {
    private fun r(n: String) = File("src/test/resources/hot/$n").readText()
    private val fishNames = KGeo.parse(File("src/main/assets/kodiak/fishing.geojson").readText()).map { it.props.getString("name") }.toSet()

    @Test fun aliasesPointAtRealSpots() { HotSpots.ALIASES.mapNotNull { it.second }.forEach { assertTrue(it, it in fishNames) } }

    @Test fun adfgArchiveAndReport() {
        val links = HotSpots.adfgArchiveLinks(r("adfg_archive.html"))
        assertEquals(LocalDate.of(2026, 9, 21), links.first().first)
        assertTrue(links.first().second.contains("RecordID=7027"))
        val m = HotSpots.extract(HotSpots.adfgReportText(r("adfg_7027.html")), "ADF&G", LocalDate.of(2026, 9, 21), links.first().second)
        val places = m.map { it.place }.toSet()
        listOf("Olds River", "Pasagshak", "Buskin River", "Buoy 4", "The Fingers", "Monashka", "Kalsin", "Mill Bay", "Long Island").forEach { assertTrue(it, it in places) }
        assertTrue(m.first { it.place == "Olds River" }.species.contains("silver"))
        assertTrue(m.first { it.place == "Buoy 4" }.spot == "Buoy 4")
        assertNull(m.first { it.place == "The Fingers" }.spot)                      // unplaced
        // scoring: hot within 10 days, not after
        assertTrue(HotSpots.score(m, LocalDate.of(2026, 9, 25)).first().score > 0)
        assertTrue(HotSpots.score(m, LocalDate.of(2026, 10, 12)).isEmpty())
        val hot = HotSpots.score(m, LocalDate.of(2026, 9, 21)).map { it.spot }
        assertTrue("Olds River" in hot && "Buoy 4" in hot)
    }

    @Test fun reddit() {
        val e = HotSpots.redditEntries(r("reddit_kodiak.rss"))
        assertTrue(e.size >= 5)
        assertTrue(e.all { it.third.startsWith("https://www.reddit.com/") })
        assertTrue(e.any { it.second.contains("Heitman Lake") })
    }

    @Test fun counts() {
        val c = HotSpots.counts(r("fc_28_430.json"), "Buskin River", "Buskin River", LocalDate.of(2026, 10, 12))!!
        assertEquals(LocalDate.of(2026, 9, 27), c.last); assertEquals(111, c.lastCount); assertFalse(c.moving)    // season over
        val again = HotSpots.counts(r("fc_28_430.json"), "Buskin River", "Buskin River", LocalDate.of(2026, 9, 28))!!
        assertEquals(again.recentAvg > again.priorAvg * 1.2 && again.recentAvg >= 10, again.moving)
    }

    @Test fun cacheRoundTrip() {
        val m = HotSpots.extract("Silvers are hot at Pasagshak this week.", "x", LocalDate.of(2026, 10, 10), "https://e")
        val res = HotSpots.Result(java.time.Instant.parse("2026-10-13T05:00:00Z"), m, emptyList(), mapOf("x" to "ok"))
        val back = HotSpots.fromJson(HotSpots.toJson(res))
        assertEquals("Pasagshak", back.mentions.single().place); assertEquals(listOf("silver"), back.mentions.single().species)
    }
}
