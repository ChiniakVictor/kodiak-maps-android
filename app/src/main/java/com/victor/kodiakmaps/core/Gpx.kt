package com.victor.kodiakmaps.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** GPX 1.1 writer for recorded tracks and pins (pure, unit-tested). */
object Gpx {
    data class Pt(val lat: Double, val lon: Double, val ele: Double?, val time: Long)
    data class Wpt(val lat: Double, val lon: Double, val name: String, val desc: String, val time: Long)
    data class Trk(val name: String, val segments: List<List<Pt>>)

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun iso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun f(v: Double) = String.format(Locale.US, "%.7f", v)

    fun write(tracks: List<Trk>, wpts: List<Wpt> = emptyList(), now: Long = System.currentTimeMillis()): String {
        val t = iso(); val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Kodiak Maps\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>Kodiak Maps export</name><time>${t.format(Date(now))}</time></metadata>\n")
        for (w in wpts) sb.append("  <wpt lat=\"${f(w.lat)}\" lon=\"${f(w.lon)}\"><time>${t.format(Date(w.time))}</time><name>${esc(w.name)}</name>" +
            (if (w.desc.isNotBlank()) "<desc>${esc(w.desc)}</desc>" else "") + "</wpt>\n")
        for (tr in tracks) {
            sb.append("  <trk><name>${esc(tr.name)}</name>\n")
            for (seg in tr.segments) {
                if (seg.isEmpty()) continue
                sb.append("    <trkseg>\n")
                for (p in seg) sb.append("      <trkpt lat=\"${f(p.lat)}\" lon=\"${f(p.lon)}\">" + (p.ele?.let { "<ele>${String.format(Locale.US, "%.1f", it)}</ele>" } ?: "") +
                    "<time>${t.format(Date(p.time))}</time></trkpt>\n")
                sb.append("    </trkseg>\n")
            }
            sb.append("  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Splits a recording where there's a gap > [gapMs] (e.g. GPS lost) so lines don't jump across. */
    fun segments(pts: List<Pt>, gapMs: Long = 10 * 60_000L): List<List<Pt>> {
        val out = mutableListOf<MutableList<Pt>>(); var cur = mutableListOf<Pt>()
        for (p in pts) { val q = cur.lastOrNull(); if (q != null && p.time - q.time > gapMs) { out += cur; cur = mutableListOf() }; cur += p }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun lengthM(pts: List<Pt>): Double = pts.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a.lat, a.lon, b.lat, b.lon) }

    /** Keep a new fix only if it moved enough (or enough time passed) and is accurate enough. */
    fun accept(last: Pt?, p: Pt, accM: Float?, minMoveM: Double = 5.0, maxAccM: Float = 50f, keepAliveMs: Long = 60_000L): Boolean {
        if (accM != null && accM > maxAccM) return false
        last ?: return true
        val d = Geo.distanceM(last.lat, last.lon, p.lat, p.lon)
        return d >= maxOf(minMoveM, (accM ?: 0f) * 0.5) || p.time - last.time >= keepAliveMs
    }
}
