package com.victor.kodiakmaps.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fishing Spots layer (ADF&G GoFishAK points) + yearly regulation summary (assets/kodiak/fishing_regs.json). */
object Fishing {
    const val SOURCE = "ADF&G GoFishAK (Sport Fishing Opportunities and Access)"
    const val CAUTION = "Check current ADF&G regulations and emergency orders before fishing. This summary is not the official regulation."
    val TYPE_COLOR = mapOf("salt" to "#29B6F6", "fresh" to "#66BB6A", "lake" to "#FFCA28", "ocean" to "#283593")
    val TYPE_LABEL = mapOf("salt" to "Salt water", "fresh" to "Fresh water (river/creek)", "lake" to "Stocked lake", "ocean" to "Offshore ground (boat)")
    fun color(type: String?) = TYPE_COLOR[type] ?: "#B0BEC5"
    /** Text/label colour readable on the dark UI (navy is too dark for text). */
    fun textColor(type: String?) = if (type == "ocean") "#9FA8DA" else color(type)

    class Rule(val species: String, val open: List<Pair<MonthDay, MonthDay>>?, val limit: String?, val cite: String?)
    class RegSet(val key: String, val title: String, val book: String, val rules: List<Rule>, val gear: List<String>, val closures: List<String>, val notes: List<String>)
    class Regs(val year: Int, val lastUpdated: String, val checkUrl: String, val freshPdf: String, val saltPdf: String, val eoUrl: String, val eoNote: String,
               val general: List<String>, val sets: Map<String, RegSet>)

    private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    private val MD = DateTimeFormatter.ofPattern("MM-dd")
    fun parseRegs(text: String): Regs {
        val o = JSONObject(text); val s = o.getJSONObject("sets")
        val sets = s.keys().asSequence().associateWith { k ->
            val j = s.getJSONObject(k); val ra = j.getJSONArray("rules")
            RegSet(k, j.getString("title"), j.optString("book"), (0 until ra.length()).map { i ->
                val r = ra.getJSONObject(i)
                val open = if (r.isNull("open") || !r.has("open")) null else r.getJSONArray("open").let { a -> (0 until a.length()).map { n ->
                    val p = a.getJSONArray(n); MonthDay.parse("--" + p.getString(0)) to MonthDay.parse("--" + p.getString(1)) } }
                Rule(r.getString("sp"), open, if (r.isNull("limit")) null else r.optString("limit"), r.optString("cite").ifBlank { null })
            }, strs(j.optJSONArray("gear")), strs(j.optJSONArray("closures")), strs(j.optJSONArray("notes")))
        }
        return Regs(o.getInt("year"), o.getString("lastUpdated"), o.getString("checkUrl"), o.getString("freshPdf"), o.getString("saltPdf"),
            o.getString("eoUrl"), o.getString("eoNote"), strs(o.optJSONArray("general")), sets)
    }

    enum class Status { OPEN, CLOSED, UNKNOWN }
    class Season(val status: Status, val text: String)
    private val SHOW = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private fun inRange(d: MonthDay, a: MonthDay, b: MonthDay) = if (!a.isAfter(b)) !d.isBefore(a) && !d.isAfter(b) else !d.isBefore(a) || !d.isAfter(b)

    /** Season indicator for one rule on [today]. open == null → unclear ("see regs"); empty → closed all year. */
    fun season(r: Rule, today: LocalDate, regYear: Int): Season {
        val ranges = r.open ?: return Season(Status.UNKNOWN, "See regs")
        if (today.year != regYear) return Season(Status.UNKNOWN, "See $regYear+ regs (summary is for $regYear)")
        if (ranges.isEmpty()) return Season(Status.CLOSED, "Closed in $regYear")
        val d = MonthDay.from(today)
        ranges.firstOrNull { inRange(d, it.first, it.second) }?.let { (a, b) ->
            return Season(Status.OPEN, if (a == MonthDay.of(1, 1) && b == MonthDay.of(12, 31)) "Open (year-round)" else "Open until ${b.atYear(today.year).format(SHOW)}")
        }
        val next = ranges.map { it.first.atYear(today.year).let { s -> if (s.isBefore(today)) s.plusYears(1) else s } }.minOrNull()!!
        return Season(Status.CLOSED, if (next.year > regYear) "Closed for the rest of $regYear" else "Closed · opens ${next.format(SHOW)}")
    }

    /** Newest Kodiak fresh-water summary year mentioned on the ADF&G Southwest regulations page, or null. */
    fun newestYearOnPage(html: String): Int? = Regex("(20\\d\\d)sw_sfregs_kodiak").findAll(html).map { it.groupValues[1].toInt() }.maxOrNull()
    fun checkMessage(bundled: Int, onPage: Int?): String = when {
        onPage == null -> "Couldn't find the Kodiak regulation booklet on the ADF&G page. Check it yourself: regulations may have changed."
        onPage > bundled -> "ADF&G has published $onPage regulations; this app has $bundled. Use the $onPage booklet until the app is updated."
        else -> "Up to date: ADF&G page still lists the $bundled Kodiak booklet. Emergency orders can still change rules."
    }

    fun title(p: JSONObject) = p.optString("name").ifBlank { "Fishing spot" }
}
