package com.victor.kodiakmaps.data

import android.content.Context
import com.victor.kodiakmaps.core.HotSpots
import com.victor.kodiakmaps.service.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Fetches hot-spot sources only on app open and on manual refresh (no background work). Caches the last result for offline viewing. */
object HotData {
    const val UA = "android:com.victor.kodiakmaps:v1.2 (personal, non-commercial; Kodiak Maps)"
    private val _s = MutableStateFlow<HotSpots.Result?>(null); val state: StateFlow<HotSpots.Result?> get() = _s
    val busy = MutableStateFlow(false)
    private var fetchedThisLaunch = false
    private fun file(ctx: Context) = File(ctx.filesDir, "kodiak/hot.json")
    private fun get(u: String): String { val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA); c.connectTimeout = 15_000; c.readTimeout = 30_000
        try { if (c.responseCode != 200) error("HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() } }
    val REDDIT = listOf(
        "r/Kodiak" to "https://www.reddit.com/r/Kodiak/search.rss?q=fishing+OR+salmon+OR+halibut+OR+silvers+OR+kings+OR+fish&restrict_sr=1&sort=new&t=month",
        "r/alaska" to "https://www.reddit.com/r/alaska/search.rss?q=kodiak+fishing&restrict_sr=1&sort=new&t=month",
        "r/Fishing" to "https://www.reddit.com/r/Fishing/search.rss?q=kodiak&restrict_sr=1&sort=new&t=month")

    suspend fun loadCache(ctx: Context) = withContext(Dispatchers.IO) {
        if (_s.value == null) runCatching { file(ctx).takeIf { it.exists() }?.readText()?.let { _s.value = HotSpots.fromJson(it) } }
    }
    suspend fun onAppOpen(ctx: Context) { loadCache(ctx); if (!fetchedThisLaunch) { fetchedThisLaunch = true; refresh(ctx) } }

    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        if (busy.value) return@withContext; busy.value = true
        try {
            val today = LocalDate.now(ZoneId.of("America/Anchorage")); val since = today.minusDays(HotSpots.LIST_DAYS)
            val mentions = ArrayList<HotSpots.Mention>(); val status = LinkedHashMap<String, String>()
            runCatching {
                val links = HotSpots.adfgArchiveLinks(get("https://www.adfg.alaska.gov/sf/FishingReports/index.cfm?ADFG=R2.archive&Area_key=7&Year=${today.year}")).take(2)
                var n = 0
                links.forEach { (d, u) -> val m = HotSpots.extract(HotSpots.adfgReportText(get(u)), "ADF&G Kodiak fishing report", d, u); mentions += m; n += m.size }
                status["ADF&G weekly report"] = if (links.isEmpty()) "no reports listed for ${today.year}" else "latest ${links.first().first} · $n mentions"
            }.onFailure { status["ADF&G weekly report"] = "failed: ${it.message}" }
            for ((name, u) in REDDIT) {
                runCatching {
                    val es = HotSpots.redditEntries(get(u)).filter { !it.first.atZone(ZoneId.of("America/Anchorage")).toLocalDate().isBefore(since) }
                    var n = 0
                    es.forEach { (t, text, link) -> val m = HotSpots.extract(text, "Reddit $name", t.atZone(ZoneId.of("America/Anchorage")).toLocalDate(), link); mentions += m; n += m.size }
                    status["Reddit $name"] = "${es.size} posts in ${HotSpots.LIST_DAYS} days · $n mentions"
                }.onFailure { status["Reddit $name"] = "failed: ${it.message}" }
                delay(7_000)   // Reddit rate-limits unauthenticated feeds (HTTP 429 at 2 s); be polite to Reddit's public endpoints
            }
            val counts = ArrayList<HotSpots.Moving>(); var fail = 0
            for ((id, loc, spot) in HotSpots.WEIRS) for ((sid, _) in HotSpots.COUNT_SPECIES) runCatching {
                HotSpots.counts(get("https://www.adfg.alaska.gov/sf/FishCounts/index.cfm?ADFG=export.JSON&countLocationID=$id&year=${today.year}&speciesID=$sid"), loc, spot, today)?.let { counts += it }
            }.onFailure { fail++ }
            status["ADF&G fish counts"] = "${counts.size} weir series" + (counts.maxByOrNull { it.last }?.let { ", latest count ${it.last}" } ?: "") + (if (fail > 0) " · $fail failed" else "") + " · ${counts.count { it.moving }} moving"
            val r = HotSpots.Result(Instant.now(), mentions.filter { !it.date.isBefore(since) }.distinctBy { Triple(it.place, it.snippet, it.url) }, counts, status)
            // keep the previous cache if every source failed
            if (status.values.all { it.startsWith("failed") } && _s.value != null) { AppLog.log("Hot spots: all sources failed, keeping cache"); _s.value = HotSpots.Result(_s.value!!.fetched, _s.value!!.mentions, _s.value!!.counts, status) }
            else { _s.value = r; file(ctx).parentFile?.mkdirs(); file(ctx).writeText(HotSpots.toJson(r)) }
            AppLog.log("Hot spots: $status")
        } finally { busy.value = false }
    }
}
