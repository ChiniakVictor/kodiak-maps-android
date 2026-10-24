package com.victor.kodiakmaps.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.kodiakmaps.core.HotSpots
import com.victor.kodiakmaps.data.HotData
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Hot spots list: scored places (last 10 days), weir counts with "fish moving", every mention with quote, source, date, link. */
@Composable
fun HotPanel(onSpot: (String) -> Unit) {
    val ctx = LocalContext.current; val cs = MaterialTheme.colorScheme
    val r by HotData.state.collectAsState(); val busy by HotData.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(ZoneId.of("America/Anchorage"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🔥 Hot spots", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        TextButton(enabled = !busy, onClick = { scope.launch { HotData.refresh(ctx) } }) { Text("Refresh") }
    }
    val res = r ?: run { Text(if (busy) "Checking ADF&G and Reddit…" else "No results yet. Tap Refresh when online.", color = cs.onSurfaceVariant); return }
    Text("Fetched ${res.fetched.atZone(ZoneId.of("America/Anchorage")).format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))} AKT. Only fetched when the app opens or you tap Refresh.", fontSize = 11.sp, color = cs.onSurfaceVariant)
    res.sourceStatus.forEach { (k, v) -> Text("$k: $v", fontSize = 11.sp, color = if (v.startsWith("failed")) cs.error else cs.onSurfaceVariant) }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    val hot = HotSpots.score(res.mentions, today)
    if (hot.isEmpty()) Text("No spots mentioned in the last ${HotSpots.HOT_DAYS} days.", fontSize = 13.sp, color = cs.onSurface)
    hot.forEach { h ->
        val placed = res.mentions.firstOrNull { it.place == h.spot }?.spot
        Row(Modifier.fillMaxWidth().clickable(enabled = placed != null) { placed?.let(onSpot) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔥".repeat(if (h.score > 1.5) 3 else if (h.score > 0.7) 2 else 1), fontSize = 13.sp)
            Text(" ${h.spot}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.weight(1f))
            Text((h.species.joinToString(", ").ifBlank { "—" }) + (if (placed == null) " · unplaced" else ""), fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
    val moving = res.counts.filter { it.moving }
    Text("Weir counts (ADF&G)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.padding(top = 8.dp))
    if (res.counts.isEmpty()) Text("No count data returned.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    (moving + res.counts.filter { !it.moving }.sortedByDescending { it.last }.take(6)).forEach { c ->
        Text((if (c.moving) "🐟 fish moving · " else "") + "${c.location} ${c.species}: last count ${c.lastCount} on ${c.last} (season ${"%,d".format(c.total)})",
            fontSize = 12.sp, color = if (c.moving) Color(0xFF81C784) else cs.onSurfaceVariant)
    }
    if (moving.isEmpty() && res.counts.isNotEmpty()) Text("No weir counted in the last 7 days, so none are marked as fish moving.", fontSize = 11.sp, color = cs.onSurfaceVariant)
    Text("Mentions", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.padding(top = 8.dp))
    if (res.mentions.isEmpty()) Text("None in the last ${HotSpots.LIST_DAYS} days.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    res.mentions.sortedByDescending { it.date }.forEach { m ->
        val age = ChronoUnit.DAYS.between(m.date, today); val old = age > HotSpots.HOT_DAYS
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant)
            .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.url))) } }.padding(8.dp)) {
            Text("${m.place}${if (m.spot == null) " (unplaced)" else ""}" + (if (m.species.isNotEmpty()) " · ${m.species.joinToString(", ")}" else ""),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (old) cs.onSurfaceVariant else cs.onSurface)
            Text("“${m.snippet}”", fontSize = 12.sp, color = if (old) cs.onSurfaceVariant else cs.onSurface)
            Text("${m.source} · ${m.date}${if (old) " · older than ${HotSpots.HOT_DAYS} days" else ""} · open link", fontSize = 11.sp, color = cs.primary)
        }
    }
    Text("Hot = mentioned in the last ${HotSpots.HOT_DAYS} days (newer counts more). Reports are second-hand; check current ADF&G regulations and emergency orders.", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}
