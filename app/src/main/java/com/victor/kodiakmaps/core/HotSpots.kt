package com.victor.kodiakmaps.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.exp

/** "Hot spots": mentions of Kodiak places + species in ADF&G weekly reports and public Reddit posts, plus ADF&G weir counts. Never invents data. */
object HotSpots {
    const val HOT_DAYS = 10L       // a mention makes a spot "hot" for this long (decaying)
    const val LIST_DAYS = 45L      // older mentions still listed (dimmed) for context

    /** Spot name in fishing.geojson (or null = unplaced) to alias regexes. */
    val ALIASES: List<Triple<String, String?, Regex>> = listOf(
        t("Buoy 4", "Buoy 4", "buoy\\s*(#\\s*)?4\\b|buoy four"),
        t("Buoy 5", "Buoy 5", "buoy\\s*(#\\s*)?5\\b|buoy five"),
        t("Pasagshak", "Pasagshak River", "pasagshak|lake rose te[ae]d"),
        t("Buskin River", "Buskin River", "buskin(?!\\s+lake)"),
        t("Buskin Lake", "Buskin Lake", "buskin lake"),
        t("Olds River", "Olds River", "\\bolds\\b"),
        t("American River", "American River", "american river|\\bthe american\\b"),
        t("Roslyn", "Roslyn Creek", "roslyn"),
        t("Saltery", "Saltery Creek", "saltery"),
        t("Monashka", "Monashka Creek", "monashka"),
        t("Pillar Creek", "Pillar Creek", "\\bpillar\\b(?!\\s+mountain)"),
        t("Sargent Creek", "Sargent Creek", "sargent"),
        t("Russian Creek", "Russian Creek", "russian (creek|river)"),
        t("Salonie Creek", "Salonie Creek", "salonie"),
        t("Cape Chiniak", "Cape Chiniak (Cape Chiniak Light)", "cape chiniak"),
        t("Chiniak Bay", "Chiniak Bay", "chiniak bay"),
        t("Afognak", "Afognak River", "afognak"),
        t("Karluk", "Karluk River", "karluk"),
        t("Ayakulik", "Ayakulik River", "ayakulik"),
        t("Dog Salmon / Frazer", "Dog Salmon River/Frazer Lake drainage", "dog salmon|frazer"),
        t("Island Lake", "Island Lake", "island lake"),
        // places with no point in the fishing layer: listed as unplaced
        t("Mission Beach", null, "mission beach|\\bmission\\b(?=[^.]{0,40}(beach|snag))|and mission\\b"),
        t("Mill Bay", null, "mill bay"),
        t("Woody Island", null, "woody island"),
        t("Long Island", null, "long island"),
        t("Spruce Cape", null, "spruce cape"),
        t("Spruce Island", null, "spruce island"),
        t("Kalsin", null, "kalsin"),
        t("The Fingers", null, "\\bthe fingers\\b|\\bfingers\\b"),
        t("White Sands", null, "white sands"),
        t("Ugak Bay", null, "ugak"),
        t("Marmot Bay", null, "marmot"),
        t("Anton Larsen", null, "anton larsen"),
        t("Womens Bay", null, "women'?s bay"),
    )
    private fun t(n: String, s: String?, r: String) = Triple(n, s, Regex(r, RegexOption.IGNORE_CASE))
    val SPECIES = listOf("king" to "\\bkings?\\b|chinook", "silver" to "silvers?\\b|coho", "red" to "\\breds?\\b|sockeye", "pink" to "pinks?\\b|humpies|humpy",
        "chum" to "chums?\\b|dogs? salmon", "halibut" to "halibut", "lingcod" to "lingcod|\\blings?\\b", "rockfish" to "rockfish", "Dolly Varden" to "dolly|dollies",
        "rainbow/steelhead" to "rainbows?|steelhead", "char" to "\\bchar\\b", "cod" to "\\bcod\\b", "crab" to "crab", "shrimp" to "shrimp")
        .map { it.first to Regex(it.second, RegexOption.IGNORE_CASE) }
    private val FISHY = Regex("fish|catch|caught|bite|biting|run|hook|limit|troll|jig|snag|landed", RegexOption.IGNORE_CASE)

    class Mention(val place: String, val spot: String?, val species: List<String>, val snippet: String, val source: String, val date: LocalDate, val url: String)
    class Moving(val location: String, val spot: String?, val species: String, val last: LocalDate, val lastCount: Int, val recentAvg: Double, val priorAvg: Double, val total: Int, val moving: Boolean)
    class Hot(val spot: String, val score: Double, val species: List<String>, val mentions: Int)
    class Result(val fetched: Instant, val mentions: List<Mention>, val counts: List<Moving>, val sourceStatus: Map<String, String>)

    fun species(s: String) = SPECIES.filter { it.second.containsMatchIn(s) }.map { it.first }
    /** Every alias in [text] → a mention per (place, sentence); sentences without a species or fishing word are ignored. */
    fun extract(text: String, source: String, date: LocalDate, url: String): List<Mention> {
        val out = ArrayList<Mention>()
        sentences(text).forEach { sen ->
            val sp = species(sen); if (sp.isEmpty() && !FISHY.containsMatchIn(sen)) return@forEach
            ALIASES.filter { it.third.containsMatchIn(sen) }.distinctBy { it.first }.forEach { (n, spot, _) ->
                out += Mention(n, spot, sp, sen.take(260), source, date, url) }
        }
        return out
    }
    fun sentences(t: String) = t.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?])\\s+(?=[A-Z\"'])")).map { it.trim() }.filter { it.length > 8 }

    fun stripHtml(h: String): String = h.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ").replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h\\d>|</li>"), "\n")
        .replace(Regex("<[^>]+>"), " ").let { unescape(it) }.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
    fun unescape(s: String) = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }.replace("&amp;", "&")

    // ---- ADF&G weekly report
    private val MDY = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)
    /** Archive page → (date, absolute url) newest first. */
    fun adfgArchiveLinks(html: String): List<Pair<LocalDate, String>> = Regex("href=\"(/sf/FishingReports/index\\.cfm\\?ADFG=R2\\.archive&(?:amp;)?Area_key=7&(?:amp;)?RecordID=\\d+&(?:amp;)?Year=\\d+)\"[^>]*>\\s*([A-Z][a-z]{2} \\d{2}, \\d{4})")
        .findAll(html).mapNotNull { m -> runCatching { LocalDate.parse(m.groupValues[2], MDY) to "https://www.adfg.alaska.gov" + m.groupValues[1].replace("&amp;", "&") }.getOrNull() }
        .distinct().sortedByDescending { it.first }.toList()
    /** Report body text (from "Archived Sport Fishing Report" to "Special Species Information"/end). */
    fun adfgReportText(html: String): String {
        val t = stripHtml(html); val a = t.indexOf("Sport Fishing Report", t.indexOf("All Emergency Orders").coerceAtLeast(0)).let { if (it < 0) 0 else it }
        val b = t.indexOf("Helpful links", a).let { if (it < 0) t.length else it }
        return t.substring(a, b)
    }

    // ---- Reddit public Atom search feeds
    fun redditEntries(xml: String): List<Triple<Instant, String, String>> = Regex("(?s)<entry>(.*?)</entry>").findAll(xml).mapNotNull { e ->
        val b = e.groupValues[1]
        val up = Regex("<updated>([^<]+)</updated>").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
        val title = unescape(Regex("(?s)<title>(.*?)</title>").find(b)?.groupValues?.get(1) ?: "")
        val body = Regex("(?s)<content[^>]*>(.*?)</content>").find(b)?.groupValues?.get(1)?.let { stripHtml(unescape(it)) }?.replace(Regex("submitted by\\s*/u/\\S+.*"), "") ?: ""
        val link = Regex("<link href=\"([^\"]+)\"").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
        Triple(OffsetDateTime.parse(up).toInstant(), "$title. $body", link)
    }.toList()

    // ---- ADF&G fish counts (export.JSON)
    private val CD = DateTimeFormatter.ofPattern("MMMM, dd yyyy HH:mm:ss", Locale.US)
    val WEIRS = listOf(Triple(28, "Buskin River", "Buskin River"), Triple(60, "Pasagshak", "Pasagshak River"), Triple(29, "Saltery Creek", "Saltery Creek"),
        Triple(24, "Karluk River", "Karluk River"), Triple(25, "Ayakulik River", "Ayakulik River"), Triple(27, "Dog Salmon River", "Dog Salmon River/Frazer Lake drainage"))
    val COUNT_SPECIES = listOf(420 to "Sockeye", 430 to "Coho", 410 to "Chinook", 440 to "Pink")
    /** Rising if the last 3 counted days average more than the 3 before, with the last count within 7 days of [today]. */
    fun counts(json: String, location: String, spot: String?, today: LocalDate): Moving? {
        val d = JSONObject(json).getJSONArray("DATA")
        val rows = (0 until d.length()).mapNotNull { i -> val r = d.getJSONArray(i); if (r.isNull(2)) null else
            runCatching { LocalDate.parse(r.getString(1), CD) to r.getInt(2) }.getOrNull() }.sortedBy { it.first }
        if (rows.isEmpty()) return null
        val sp = d.getJSONArray(0).optString(6)
        val last3 = rows.takeLast(3).map { it.second }; val prev3 = rows.dropLast(3).takeLast(3).map { it.second }
        val ra = last3.average(); val pa = if (prev3.isEmpty()) 0.0 else prev3.average()
        val last = rows.last()
        val fresh = !last.first.isBefore(today.minusDays(7))
        return Moving(location, spot, sp, last.first, last.second, ra, pa, rows.sumOf { it.second }, fresh && ra > pa * 1.2 && ra >= 10)
    }

    fun score(m: List<Mention>, now: LocalDate): List<Hot> = m.filter { it.spot != null || true }.groupBy { it.place }.mapNotNull { (p, ms) ->
        val s = ms.sumOf { val age = java.time.temporal.ChronoUnit.DAYS.between(it.date, now); if (age in 0..HOT_DAYS) exp(-age / 5.0) else 0.0 }
        if (s <= 0) null else Hot(p, s, ms.flatMap { it.species }.distinct(), ms.size)
    }.sortedByDescending { it.score }

    // ---- cache
    fun toJson(r: Result): String = JSONObject().put("fetched", r.fetched.toString())
        .put("mentions", JSONArray().apply { r.mentions.forEach { put(JSONObject().put("place", it.place).put("spot", it.spot ?: JSONObject.NULL).put("species", JSONArray(it.species)).put("snippet", it.snippet).put("source", it.source).put("date", it.date.toString()).put("url", it.url)) } })
        .put("counts", JSONArray().apply { r.counts.forEach { put(JSONObject().put("loc", it.location).put("spot", it.spot ?: JSONObject.NULL).put("sp", it.species).put("last", it.last.toString()).put("lastCount", it.lastCount).put("ra", it.recentAvg).put("pa", it.priorAvg).put("total", it.total).put("moving", it.moving)) } })
        .put("status", JSONObject(r.sourceStatus as Map<*, *>)).toString()
    fun fromJson(s: String): Result { val o = JSONObject(s)
        val m = o.getJSONArray("mentions"); val c = o.getJSONArray("counts"); val st = o.getJSONObject("status")
        return Result(Instant.parse(o.getString("fetched")),
            (0 until m.length()).map { val x = m.getJSONObject(it); Mention(x.getString("place"), if (x.isNull("spot")) null else x.getString("spot"),
                x.getJSONArray("species").let { a -> (0 until a.length()).map { a.getString(it) } }, x.getString("snippet"), x.getString("source"), LocalDate.parse(x.getString("date")), x.getString("url")) },
            (0 until c.length()).map { val x = c.getJSONObject(it); Moving(x.getString("loc"), if (x.isNull("spot")) null else x.getString("spot"), x.getString("sp"), LocalDate.parse(x.getString("last")),
                x.getInt("lastCount"), x.getDouble("ra"), x.getDouble("pa"), x.getInt("total"), x.getBoolean("moving")) },
            st.keys().asSequence().associateWith { st.getString(it) })
    }
}
