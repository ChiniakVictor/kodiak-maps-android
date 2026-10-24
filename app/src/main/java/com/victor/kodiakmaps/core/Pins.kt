package com.victor.kodiakmaps.core

import java.util.Locale

enum class PinKind(val label: String, val emoji: String) {
    MEETUP("Spot", "📍"), CAMP("Camp", "⛺"), HAZARD("Hazard", "⚠"), GAME("Game/Animal", "🦌"),
    WATER("Water", "💧"), VEHICLE("Vehicle", "🚙"), CACHE("Cache", "📦"), OTHER("Other", "📌");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER }
}

val PIN_COLORS = listOf(0xFFFF5252, 0xFFFFB300, 0xFF69F0AE, 0xFF40C4FF, 0xFFE040FB, 0xFFFFFFFF, 0xFFFF6E40, 0xFF8D6E63).map { it.toInt() }

object PinUtil {
    const val NAME_MAX = 30; const val NOTE_MAX = 200
    fun newId(): String = "%08x".format(kotlin.random.Random.nextInt() and 0x7fffffff or 0x10000)
    fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n)

    /** RFC 5870-style geo: URI with a label (opens in most map apps). */
    fun geoUri(lat: Double, lon: Double, name: String): String {
        val ll = "%.6f,%.6f".format(Locale.US, lat, lon)
        return "geo:$ll?q=$ll(" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") + ")"
    }

    /** Plain-text share: name, coordinates, geo: link and a Google Maps link. */
    fun shareText(name: String, lat: Double, lon: Double, note: String = ""): String {
        val ll = "%.6f, %.6f".format(Locale.US, lat, lon)
        return buildString {
            append(name).append('\n').append(ll).append('\n')
            if (note.isNotBlank()) append(note).append('\n')
            append(geoUri(lat, lon, name)).append('\n')
            append("https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, lat, lon))
        }
    }
}
