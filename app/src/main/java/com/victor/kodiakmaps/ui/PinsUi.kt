package com.victor.kodiakmaps.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.kodiakmaps.core.*
import com.victor.kodiakmaps.data.PinEntity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

fun pinEmoji(p: PinEntity) = PinKind.of(p.kind).emoji

/** Share a pin as text with a geo: link (any app: SMS, email, other map apps). */
fun sharePin(ctx: android.content.Context, name: String, lat: Double, lon: Double, note: String = "") {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, PinUtil.shareText(name, lat, lon, note))
    runCatching { ctx.startActivity(Intent.createChooser(i, "Share location")) }
}
fun openGeo(ctx: android.content.Context, name: String, lat: Double, lon: Double) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PinUtil.geoUri(lat, lon, name)))) }
        .onFailure { android.widget.Toast.makeText(ctx, "No other map app installed", android.widget.Toast.LENGTH_SHORT).show() }
}

/** Create (existing == null) or edit a pin. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinDialog(lat: Double, lon: Double, alt: Int?, existing: PinEntity?, onSave: (PinEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(PinKind.of(existing?.kind ?: PinKind.MEETUP.name)) }
    var color by remember { mutableStateOf(existing?.color ?: PIN_COLORS[0]) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    AlertDialog(onDismiss, title = { Text(if (existing == null) "Drop pin" else "Edit pin") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("%.5f, %.5f".format(Locale.US, lat, lon), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = PinUtil.clip(it, PinUtil.NAME_MAX) }, label = { Text("Name") }, placeholder = { Text(kind.label) }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PinKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text("${k.emoji} ${k.label}", fontSize = 12.sp) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PIN_COLORS.forEach { c ->
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                        .border(if (c == color) 3.dp else 1.dp, if (c == color) Color.White else Color(0xFF455A64), CircleShape).clickable { color = c })
                }
            }
            OutlinedTextField(note, { note = PinUtil.clip(it, PinUtil.NOTE_MAX) }, label = { Text("Note (optional)") }, maxLines = 3)
            Text("Pins stay on this phone only.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton({
            val now = System.currentTimeMillis()
            onSave(existing?.copy(name = name.ifBlank { kind.label }, kind = kind.name, color = color, note = note, updated = now, expires = 0)
                ?: PinEntity(PinUtil.newId(), name.ifBlank { kind.label }, kind.name, color, note, lat, lon, alt, now, now))
        }) { Text(if (existing == null) "Drop pin" else "Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

private val whenFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

/** Sheet content for a tapped pin. */
@Composable
fun PinDetails(p: PinEntity, phone: PhoneFix?, heading: Float?, metric: Boolean, coordFmt: Int, onNavigate: () -> Unit, onEdit: () -> Unit,
               onDelete: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = phone?.let { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) }
    val b = phone?.let { Geo.bearingDeg(it.lat, it.lon, p.lat, p.lon) }
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pinEmoji(p), fontSize = 26.sp); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(p.color))
            Text("${PinKind.of(p.kind).label} · ${whenFmt.format(Date(p.created))}" + (if (p.expires > 0) " · until ${whenFmt.format(Date(p.expires))}" else ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClose) { Text("✕") }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d?.let { Geo.formatDistance(it, metric) } ?: "—", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(b?.let { "${Geo.cardinal(it)}  ${it.roundToInt()}°" } ?: "Waiting for my GPS", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(Coords.format(p.lat, p.lon, coordFmt) + (Geo.formatAltitude(p.alt, metric)?.let { " · elev $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (b != null) BearingArrow((b - (heading ?: 0f)).toFloat(), Color(p.color), 56.dp)
    }
    if (p.note.isNotBlank()) Text(p.note, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Button(onNavigate, Modifier.weight(1f)) { Text("🧭 Navigate") }
        OutlinedButton({ sharePin(ctx, p.name, p.lat, p.lon, p.note) }, Modifier.weight(1f)) { Text("Share") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onEdit) { Text("Edit") }
        TextButton({ openGeo(ctx, p.name, p.lat, p.lon) }) { Text("Open in…") }
        TextButton({ confirm = true }) { Text("Delete", color = Color(0xFFFF8A80)) }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Delete this pin?") }, text = { Text(p.name) },
        confirmButton = { TextButton({ confirm = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

/** Navigation mode: compass arrow to the pin, distance, bearing and ETA at the configured pace. */
@Composable
fun NavPanel(p: PinEntity, phone: PhoneFix?, heading: Float?, metric: Boolean, paceMps: Double, compact: Boolean, onStop: () -> Unit) {
    val d = phone?.let { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) }
    val b = phone?.let { Geo.bearingDeg(it.lat, it.lon, p.lat, p.lon) }
    val eta = d?.let { (it / paceMps.coerceAtLeast(0.1)).toLong() }
    val arrived = d != null && d <= 20
    fun etaText() = eta?.let { s -> if (s < 3600) "${(s / 60).coerceAtLeast(1)} min" else "%d h %02d min".format(s / 3600, (s % 3600) / 60) } ?: "—"
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b != null) BearingArrow((b - (heading ?: 0f)).toFloat(), Color(0xFFFFD740), 30.dp)
            Spacer(Modifier.width(8.dp))
            Text("${pinEmoji(p)} ${p.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (arrived) "Arrived" else d?.let { Geo.formatDistance(it, metric) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(6.dp)); Text(b?.let { "${it.roundToInt()}°" } ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onStop) { Text("Stop") }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🧭 Navigating to ${pinEmoji(p)} ${p.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Button(onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E1B1B), contentColor = Color.White)) { Text("Stop") }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (b != null) BearingArrow((b - (heading ?: 0f)).toFloat(), Color(0xFFFFD740), 110.dp) else Box(Modifier.size(110.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(if (arrived) "You're there" else d?.let { Geo.formatDistance(it, metric) } ?: "No GPS fix", fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text(b?.let { "${Geo.cardinal(it)}  ${it.roundToInt()}° true" } ?: "", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("ETA ${etaText()} at ${Geo.formatDistance(paceMps * 3600, metric)}/h", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (heading == null) Text("Compass not available: arrow points relative to north", fontSize = 11.sp, color = Color(0xFFFFB74D))
        }
    }
}
