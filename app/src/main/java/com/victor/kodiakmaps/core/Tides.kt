package com.victor.kodiakmaps.core

import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

/** NOAA CO-OPS high/low tide predictions (MLLW, ft), bundled for offline use. Times stored in UTC, shown in Alaska time. */
object Tides {
    val ZONE: ZoneId = ZoneId.of("America/Anchorage")
    const val CREDIT = "Tide predictions: NOAA CO-OPS (tidesandcurrents.noaa.gov). Predictions only; wind and pressure change real water levels. Not for navigation."
    class Ev(val t: Instant, val ft: Double, val high: Boolean)
    class Station(val id: String, val name: String, val lat: Double, val lon: Double, val events: List<Ev>)
    class Set(val stations: List<Station>, val fetched: String, val source: String)

    private val F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    fun parse(text: String): Set {
        val o = JSONObject(text); val a = o.getJSONArray("stations")
        return Set((0 until a.length()).map { i -> val s = a.getJSONObject(i); val e = s.getJSONArray("events")
            Station(s.getString("id"), s.getString("name"), s.getDouble("lat"), s.getDouble("lon"), (0 until e.length()).map { j -> val x = e.getJSONArray(j)
                Ev(LocalDateTime.parse(x.getString(0), F).toInstant(ZoneOffset.UTC), x.getDouble(1), x.getString(2) == "H") }.sortedBy { it.t })
        }, o.optString("fetched"), o.optString("source"))
    }
    /** NOAA datagetter JSON (time_zone=gmt, interval=hilo) → our compact event arrays. */
    fun fromNoaa(json: String): List<Ev> { val p = JSONObject(json).getJSONArray("predictions")
        return (0 until p.length()).map { val x = p.getJSONObject(it); Ev(LocalDateTime.parse(x.getString("t"), F).toInstant(ZoneOffset.UTC), x.getString("v").toDouble(), x.getString("type") == "H") } }

    fun nearest(set: Set, lat: Double, lon: Double) = set.stations.minByOrNull { Geo.distanceM(lat, lon, it.lat, it.lon) }
    fun next(s: Station, now: Instant, n: Int = 2) = s.events.filter { it.t.isAfter(now) }.take(n)
    fun day(s: Station, d: LocalDate): List<Ev> { val a = d.atStartOfDay(ZONE).toInstant(); val b = d.plusDays(1).atStartOfDay(ZONE).toInstant()
        return s.events.filter { !it.t.isBefore(a) && it.t.isBefore(b) } }
    /** Height at time t by cosine interpolation between the surrounding high/low (standard NOAA approximation); null outside the data. */
    fun heightAt(s: Station, t: Instant): Double? {
        val i = s.events.indexOfFirst { it.t.isAfter(t) }; if (i <= 0) return null
        val a = s.events[i - 1]; val b = s.events[i]
        val f = (t.epochSecond - a.t.epochSecond).toDouble() / (b.t.epochSecond - a.t.epochSecond)
        return a.ft + (b.ft - a.ft) * (1 - cos(PI * f)) / 2
    }
    fun lastDate(s: Station): LocalDate? = s.events.lastOrNull()?.t?.atZone(ZONE)?.toLocalDate()
    private val T = DateTimeFormatter.ofPattern("h:mm a")
    fun fmt(e: Ev) = "${if (e.high) "High" else "Low"} ${e.t.atZone(ZONE).format(T)} ${"%.1f".format(e.ft)} ft"
    fun fmtDay(e: Ev) = "${if (e.high) "H" else "L"} ${e.t.atZone(ZONE).format(T)} ${"%.1f".format(e.ft)}′"
}
