package com.victor.kodiakmaps.service

/** Tiny in-memory + logcat log (no network, no files). */
object AppLog {
    private val lines = ArrayDeque<String>()
    fun log(s: String) {
        android.util.Log.i("KodiakMaps", s)
        synchronized(lines) { lines.addLast(s); while (lines.size > 200) lines.removeFirst() }
    }
    fun recent(): List<String> = synchronized(lines) { lines.toList() }
}
