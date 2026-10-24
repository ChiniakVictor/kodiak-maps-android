package com.victor.kodiakmaps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.kodiakmaps.core.Tides
import com.victor.kodiakmaps.data.TideData
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Tides panel: station picker, today's highs/lows + curve with a "now" marker, next 7 days, NOAA credit, refresh. */
@Composable
fun TidesPanel(selected: String?, onSelect: (String) -> Unit, onShow: (Double, Double) -> Unit) {
    val ctx = LocalContext.current
    val set by TideData.state.collectAsState()
    LaunchedEffect(Unit) { TideData.ensure(ctx) }
    val cs = MaterialTheme.colorScheme
    val s = set ?: run { Text("Loading tides…"); return }
    val st = s.stations.firstOrNull { it.id == selected } ?: s.stations.first()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    Text("Tides · ${st.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        s.stations.forEach { x -> FilterChip(x.id == st.id, { onSelect(x.id) }, { Text(x.name.substringBefore(",").substringBefore(" (").take(14), fontSize = 11.sp) }) }
    }
    val now = Instant.now(); val today = LocalDate.now(Tides.ZONE)
    val todays = Tides.day(st, today)
    if (todays.isEmpty()) Text("No predictions for today in the bundled data — tap Refresh tides when online.", color = cs.error)
    else Text(todays.joinToString("   ") { Tides.fmtDay(it) }, fontSize = 14.sp, color = cs.onSurface)
    Tides.next(st, now, 1).firstOrNull()?.let { Text("Next: ${Tides.fmt(it)}", fontSize = 13.sp, color = cs.primary, fontWeight = FontWeight.Bold) }
    // curve for today (local midnight to midnight)
    val a = today.atStartOfDay(Tides.ZONE).toInstant(); val pts = (0..96).map { i -> a.plusSeconds(i * 900L).let { t -> Tides.heightAt(st, t) } }
    val vals = pts.filterNotNull()
    if (vals.size > 10) {
        val lo = minOf(vals.min(), 0.0) - 0.5; val hi = vals.max() + 0.5
        val line = cs.primary; val grid = cs.onSurfaceVariant.copy(alpha = 0.4f); val nowC = cs.secondary
        Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 6.dp)) {
            fun x(i: Int) = size.width * i / 96f
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
            for (h in listOf(6, 12, 18)) drawLine(grid, Offset(x(h * 4), 0f), Offset(x(h * 4), size.height))
            val p = Path(); var started = false
            pts.forEachIndexed { i, v -> if (v != null) { if (!started) { p.moveTo(x(i), y(v)); started = true } else p.lineTo(x(i), y(v)) } }
            drawPath(p, line, style = Stroke(width = 4f))
            val ni = ((now.epochSecond - a.epochSecond) / 900.0).toFloat()
            if (ni in 0f..96f) { val nx = size.width * ni / 96f; drawLine(nowC, Offset(nx, 0f), Offset(nx, size.height), strokeWidth = 3f)
                Tides.heightAt(st, now)?.let { drawCircle(nowC, 8f, Offset(nx, y(it))) } }
        }
        Text("0 = MLLW · lines at 6 am, noon, 6 pm · orange = now" + (Tides.heightAt(st, now)?.let { " (≈${"%.1f".format(it)} ft)" } ?: ""), fontSize = 11.sp, color = cs.onSurfaceVariant)
    }
    val dfmt = DateTimeFormatter.ofPattern("EEE MMM d")
    (1..7).forEach { d -> val day = today.plusDays(d.toLong()); val ev = Tides.day(st, day)
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(day.format(dfmt), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(86.dp))
            Text(if (ev.isEmpty()) "—" else ev.joinToString("  ") { Tides.fmtDay(it) }, fontSize = 12.sp, color = cs.onSurface)
        }
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = { busy = true; scope.launch { msg = TideData.refresh(ctx); busy = false } }) { Text("Refresh tides") }
        OutlinedButton({ onShow(st.lat, st.lon) }) { Text("Show on map") }
    }
    msg?.let { Text(it, fontSize = 12.sp, color = cs.onSurface) }
    Text("Times in Alaska time. Data through ${Tides.lastDate(st) ?: "?"} (fetched ${s.fetched}). NOAA station ${st.id}.", fontSize = 11.sp, color = cs.onSurfaceVariant)
    Text(Tides.CREDIT, fontSize = 11.sp, color = cs.onSurfaceVariant)
}
