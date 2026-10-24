package com.victor.kodiakmaps.data

import android.content.Context
import com.victor.kodiakmaps.core.Tides
import com.victor.kodiakmaps.service.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object TideData {
    private val _s = MutableStateFlow<Tides.Set?>(null)
    val state: StateFlow<Tides.Set?> get() = _s
    private fun file(ctx: Context) = File(ctx.filesDir, "kodiak/tides.json")
    suspend fun ensure(ctx: Context) = withContext(Dispatchers.IO) {
        if (_s.value != null) return@withContext
        runCatching { _s.value = Tides.parse(file(ctx).takeIf { it.exists() }?.readText() ?: ctx.assets.open("kodiak/tides.json").bufferedReader().use { it.readText() }) }
            .onFailure { AppLog.log("Tides load failed: ${it.message}") }
    }
    /** Re-downloads ~13 months of high/low predictions for the bundled stations. */
    suspend fun refresh(ctx: Context): String = withContext(Dispatchers.IO) {
        val cur = _s.value ?: return@withContext "Tides not loaded"
        try {
            val d = DateTimeFormatter.BASIC_ISO_DATE; val start = LocalDate.now().minusDays(1)
            val out = JSONArray()
            for (s in cur.stations) {
                val ev = JSONArray()
                for ((a, b) in listOf(start to start.plusDays(364), start.plusDays(365) to start.plusDays(400))) {
                    val u = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?product=predictions&application=KodiakMaps&begin_date=${a.format(d)}&end_date=${b.format(d)}&datum=MLLW&station=${s.id}&time_zone=gmt&units=english&interval=hilo&format=json"
                    Tides.fromNoaa(URL(u).readText()).forEach { e -> ev.put(JSONArray().put(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneOffset.UTC).format(e.t)).put(e.ft).put(if (e.high) "H" else "L")) }
                }
                out.put(JSONObject().put("id", s.id).put("name", s.name).put("lat", s.lat).put("lon", s.lon).put("events", ev))
            }
            val text = JSONObject().put("source", cur.source).put("fetched", LocalDate.now().toString()).put("stations", out).toString()
            file(ctx).parentFile?.mkdirs(); file(ctx).writeText(text); _s.value = Tides.parse(text)
            "Tides updated for ${out.length()} stations through ${start.plusDays(400)}".also { AppLog.log(it) }
        } catch (e: Exception) { "Tide refresh failed: ${e.message ?: "offline"}".also { AppLog.log(it) } }
    }
}
