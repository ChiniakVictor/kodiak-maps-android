package com.victor.kodiakmaps.core

import java.util.Locale
import kotlin.math.abs

object Coords {
    val FORMATS = listOf("Decimal degrees", "Deg° min'", "Deg° min' sec\"")
    fun format(lat: Double, lon: Double, fmt: Int): String = when (fmt) {
        1 -> "${dm(lat, "N", "S")}  ${dm(lon, "E", "W")}"
        2 -> "${dms(lat, "N", "S")}  ${dms(lon, "E", "W")}"
        else -> String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }
    private fun dm(v: Double, p: String, n: String): String {
        val a = abs(v); val d = a.toInt(); val m = (a - d) * 60
        return String.format(Locale.US, "%d°%06.3f'%s", d, m, if (v >= 0) p else n)
    }
    private fun dms(v: Double, p: String, n: String): String {
        val a = abs(v); var d = a.toInt(); var m = ((a - d) * 60).toInt(); var s = ((a - d) * 60 - m) * 60
        if (s >= 59.95) { s = 0.0; m++ }; if (m >= 60) { m = 0; d++ }
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", d, m, s, if (v >= 0) p else n)
    }
}
