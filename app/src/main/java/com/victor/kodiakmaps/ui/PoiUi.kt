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
import com.victor.kodiakmaps.core.Geo
import com.victor.kodiakmaps.core.KFeature
import com.victor.kodiakmaps.data.PoiPrefs

fun poiColor(f: KFeature): Color = Color(runCatching { android.graphics.Color.parseColor(f.props.optString("color")) }.getOrDefault(0xFFFFFFFF.toInt()))

/** "Label\u001eValue\u001fLabel\u001eValue" -> pairs. */
fun poiRows(f: KFeature): List<Pair<String, String>> = f.props.optString("rows").split('\u001f').mapNotNull { r ->
    val i = r.indexOf('\u001e'); if (i < 0) null else r.substring(0, i) to r.substring(i + 1)
}.filter { it.second.isNotBlank() }

@Composable
fun PoiDetails(f: KFeature, phone: PhoneFix?, metric: Boolean, onNavigate: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = f.props
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(poiColor(f))); Spacer(Modifier.width(8.dp))
        Text(f.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClose) { Text("✕") }
    }
    p.optString("cat_label").takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = poiColor(f), fontWeight = FontWeight.Bold) }
    phone?.let { ph ->
        val n = f.nearestTo(ph.lat, ph.lon); val d = Geo.distanceM(ph.lat, ph.lon, n[0], n[1])
        Text("${Geo.formatDistance(d, metric)} ${Geo.cardinal(Geo.bearingDeg(ph.lat, ph.lon, n[0], n[1]))}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        p.optString("phone").takeIf { it.isNotBlank() }?.let { ph ->
            Button({ runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + ph.filter { it.isDigit() || it == '+' }))) } }) { Text("📞 Call") }
        }
        p.optString("web").takeIf { it.startsWith("http") }?.let { w ->
            OutlinedButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(w))) } }) { Text("🌐 Website") }
        }
        if (f.point != null) OutlinedButton(onNavigate) { Text("➤ Go") }
    }
    poiRows(f).forEach { (l, v) ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(l, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
            Text(v, fontSize = 13.sp)
        }
    }
    p.optString("note").takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = Color(0xFFFFB74D), modifier = Modifier.padding(top = 6.dp)) }
    if (f.k == "poi_mil") HistAttachments(csv(p.optString("imgs")), csv(p.optString("ovl")))
}

private fun csv(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }

/** Switches for the Layers dialog. All OFF until the user turns them on. */
@Composable
fun PoiLayerSwitches() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { PoiPrefs.load(ctx) }
    val s by PoiPrefs.state.collectAsState()
    @Composable fun sw(t: String, sub: String, c: Color, v: Boolean, f: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().clickable { f(!v) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(c)); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) { Text(t, fontSize = 15.sp); Text(sub, fontSize = 12.sp, color = Color(0xFF90A4AE)) }
            Switch(v, f)
        }
    Text("Places", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    sw("Food", "Restaurants, coffee, groceries", Color(0xFFFF7043), s.food) { v -> PoiPrefs.update(ctx) { it.copy(food = v) } }
    if (s.food) FoodLegend()
    sw("Travel", "Airports, ferry, floatplanes, harbors, air taxis", Color(0xFF29B6F6), s.travel) { v -> PoiPrefs.update(ctx) { it.copy(travel = v) } }
    sw("Schools", "KIBSD schools and attendance areas", Color(0xFFE53935), s.schools) { v -> PoiPrefs.update(ctx) { it.copy(schools = v) } }
    sw("Military history", "WWII and Cold War sites: Chiniak, Long Island, Fort Abercrombie, Buskin + field-GPS points and kadiak.org historic maps", Color(0xFF8D6E63), s.mil) { v -> PoiPrefs.update(ctx) { it.copy(mil = v) } }
    if (s.mil) TextButton({ HistMaps.library.value = true }, Modifier.padding(start = 14.dp)) { Text("🗺 Historic maps (kadiak.org) & 1965 structure list ›", fontSize = 13.sp) }
    sw("Community", "Chiniak Public Library", Color(0xFFAB47BC), s.community) { v -> PoiPrefs.update(ctx) { it.copy(community = v) } }
}

@Composable
fun FoodLegend() {
    Row(Modifier.padding(start = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFFF7043))); Text("  Ready to eat", fontSize = 12.sp)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF66BB6A))); Text("  Groceries / uncooked", fontSize = 12.sp)
    }
}
