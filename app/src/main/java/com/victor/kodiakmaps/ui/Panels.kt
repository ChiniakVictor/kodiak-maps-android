package com.victor.kodiakmaps.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.kodiakmaps.BuildConfig
import com.victor.kodiakmaps.core.Coords
import com.victor.kodiakmaps.core.Geo
import com.victor.kodiakmaps.data.PinEntity
import com.victor.kodiakmaps.data.Prefs
import com.victor.kodiakmaps.map.OfflineMaps
import com.victor.kodiakmaps.service.TrackService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val grey = KMColors.onSurfaceVariant

@Composable
fun PinsPanel(vm: AppViewModel, phone: PhoneFix?, metric: Boolean, onOpen: (PinEntity) -> Unit) {
    val pins by vm.pins.collectAsState()
    if (pins.isEmpty()) { Text("No pins yet. Long-press the map to drop one, or 📍 to pin where you are.", fontSize = 14.sp, color = grey); return }
    pins.sortedBy { p -> phone?.let { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) } ?: 0.0 }.forEach { p ->
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable { onOpen(p) }, verticalAlignment = Alignment.CenterVertically) {
            Text(pinEmoji(p), fontSize = 20.sp); Spacer(Modifier.width(8.dp))
            Text(p.name, Modifier.weight(1f), color = Color(p.color), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(phone?.let { Geo.formatDistance(Geo.distanceM(it.lat, it.lon, p.lat, p.lon), metric) + " " + Geo.cardinal(Geo.bearingDeg(it.lat, it.lon, p.lat, p.lon)) } ?: "—", fontSize = 14.sp)
        }
    }
}

private val df = SimpleDateFormat("MMM d HH:mm", Locale.US)

@Composable
fun TracksPanel(vm: AppViewModel) {
    val ctx = LocalContext.current
    val tracks by vm.tracks.collectAsState()
    val rec by vm.recording.collectAsState()
    val stats by vm.recStats.collectAsState()
    val hidden by vm.hiddenTracks.collectAsState()
    val s by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var exportIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri: Uri? ->
        if (uri != null) scope.launch { msg = vm.exportGpx(ctx, uri, exportIds, withPins = true) }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { TrackService.start(ctx) }
    if (rec == null) {
        Button({
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else TrackService.start(ctx)
        }, Modifier.fillMaxWidth()) { Text("⏺ Start recording") }
        Text("Recording keeps GPS on in the background (with a notification) until you press Stop. Nothing else in Kodiak Maps runs in the background.", fontSize = 12.sp, color = grey)
    } else {
        Button({ TrackService.stop(ctx) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White)) {
            Text("⏹ Stop recording · ${stats.first} pts · ${Geo.formatDistance(stats.second, s.metric)}")
        }
    }
    msg?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
    if (tracks.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Saved tracks", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton({ exportIds = tracks.map { it.t.id }; saver.launch("kodiak-maps-all.gpx") }) { Text("Export all GPX") }
    }
    tracks.forEach { t ->
        var confirm by remember { mutableStateOf(false) }
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(t.t.id !in hidden, { v -> vm.hiddenTracks.value = if (v) hidden - t.t.id else hidden + t.t.id })
                Column(Modifier.weight(1f)) {
                    Text(t.t.name + if (t.t.id == rec) " ●" else "", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${Geo.formatDistance(t.lengthM, s.metric)} · ${t.pts.size} pts · ${df.format(Date(t.t.started))}" +
                        (t.t.ended?.let { " – " + df.format(Date(it)) } ?: ""), fontSize = 12.sp, color = grey)
                }
                TextButton({ exportIds = listOf(t.t.id); saver.launch(t.t.name.replace(Regex("[^A-Za-z0-9]+"), "-") + ".gpx") }) { Text("GPX") }
                if (t.t.id != rec) TextButton({ confirm = true }) { Text("✕", color = Color(0xFFFF8A80)) }
            }
        }
        if (confirm) AlertDialog({ confirm = false }, title = { Text("Delete ${t.t.name}?") }, text = { Text("This can't be undone. Export a GPX first if you want to keep it.") },
            confirmButton = { TextButton({ confirm = false; vm.deleteTrack(t.t.id) }) { Text("Delete") } }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
    }
}

@Composable
fun MapsPanel(base: String) {
    val ctx = LocalContext.current
    val regions by OfflineMaps.regions.collectAsState()
    LaunchedEffect(Unit) { OfflineMaps.refresh(ctx) }
    Text("Base map", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(base == "usgs", { Prefs.update(ctx) { it.copy(base = "usgs") } }, { Text("USGS topo") })
        FilterChip(base == "otm", { Prefs.update(ctx) { it.copy(base = "otm") } }, { Text("OpenTopoMap") })
    }
    Text("To save an area: pan/zoom to it and tap ⬇. Saved areas are per base map; Wi-Fi only.", fontSize = 13.sp, color = grey)
    if (!OfflineMaps.onUnmeteredNetwork(ctx)) Text("Not on Wi-Fi now — downloads wait until you are.", fontSize = 13.sp, color = Color(0xFFFFD740))
    if (regions.isEmpty()) Text("No saved areas yet.", fontSize = 13.sp, color = grey)
    regions.forEach { r ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(r.name, fontWeight = FontWeight.Bold)
            Text("Zoom ${r.minZoom}–${r.maxZoom} · ${r.completed}/${r.required} tiles · ${r.bytes / 1_048_576} MB · " +
                when { r.complete -> "ready offline ✓"; r.active -> "downloading…"; else -> "paused" }, fontSize = 12.sp, color = grey)
            if (r.required > 0 && !r.complete) LinearProgressIndicator(progress = { (r.completed.toFloat() / r.required).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            r.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            Row {
                if (!r.complete) TextButton({ if (r.active) OfflineMaps.pause(r.id) else OfflineMaps.resume(ctx, r.id) }) { Text(if (r.active) "Pause" else "Resume") }
                TextButton({ OfflineMaps.delete(r.id) }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun SettingsPanel() {
    val ctx = LocalContext.current
    val s by Prefs.settings.collectAsState()
    @Composable fun sw(t: String, sub: String, v: Boolean, f: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { f(!v) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(t, fontSize = 15.sp); Text(sub, fontSize = 12.sp, color = grey) }
        Switch(v, f)
    }
    sw("Metric units", "km and m instead of miles and feet", s.metric) { v -> Prefs.update(ctx) { it.copy(metric = v) } }
    sw("Dim map", "Darker map for night use", s.dimMap) { v -> Prefs.update(ctx) { it.copy(dimMap = v) } }
    sw("Keep screen on", "While Kodiak Maps is open", s.keepScreenOn) { v -> Prefs.update(ctx) { it.copy(keepScreenOn = v) } }
    sw("Fullscreen map", "Map under the navigation bar", s.fullscreen) { v -> Prefs.update(ctx) { it.copy(fullscreen = v) } }
    Text("Coordinates: ${Coords.FORMATS[s.coordFormat]} (tap the readout at the top to switch)", fontSize = 13.sp)
    Text("Walking pace for ETA: ${Geo.formatDistance(s.paceMps * 3600.0, s.metric)}/h", fontSize = 13.sp)
    Slider(s.paceMps, { v -> Prefs.update(ctx) { it.copy(paceMps = v) } }, valueRange = 0.5f..3f)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ Onboarding.startTour() }) { Text("Tutorial") }
        OutlinedButton({ Onboarding.openPrivacy() }) { Text("Privacy") }
    }
    UpdateCheckRow()
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Kodiak Maps ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
    Text("Offline topo maps and Kodiak Island layers. Made for Victor. Runs only while open, except while you record a track.", fontSize = 13.sp)
    Text("Map data: USGS The National Map USGSTopo basemap (public domain, courtesy USGS). OpenTopoMap: © OpenStreetMap contributors, SRTM | map style © OpenTopoMap (CC-BY-SA). " +
        "BLM Alaska ANCSA 17(b) easements; Alaska DOT&PF road centerlines; Kodiak Island Borough trails, parcels, ownership, roads and coastline (no warranty). " +
        "Chiniak logging roads: Victor (kodiakking.com). State ROW widths and easement widths/uses are derived (labelled in each detail).", fontSize = 12.sp, color = grey)
    Text("Software: MapLibre Native (BSD-2), AndroidX/Compose/Room (Apache-2.0), Kotlin coroutines, OkHttp (Apache-2.0), Google Play services location.", fontSize = 12.sp, color = grey)
}
