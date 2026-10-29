package com.victor.kodiakmaps.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.victor.kodiakmaps.core.Easements
import com.victor.kodiakmaps.core.EsmtKind
import com.victor.kodiakmaps.core.Geo
import com.victor.kodiakmaps.core.KFeature
import com.victor.kodiakmaps.core.KGeo
import com.victor.kodiakmaps.core.TrailsInfo
import com.victor.kodiakmaps.data.KodiakData
import com.victor.kodiakmaps.data.Prefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Nearest point on the feature to (lat, lon): used for distance and as the navigation target. */
fun KFeature.nearestTo(lat: Double, lon: Double): DoubleArray {
    if (lines.isNotEmpty()) return lines.map { KGeo.toLine(lat, lon, it) }.minBy { it.first }.second
    if (polys.isNotEmpty()) {
        if (polys.any { KGeo.inPoly(lat, lon, it) }) return doubleArrayOf(lat, lon)
        return polys.map { KGeo.toLine(lat, lon, it) }.minBy { it.first }.second
    }
    return point ?: doubleArrayOf(lat, lon)
}

fun kodiakColor(f: KFeature): Color = when {
    f.k.startsWith("poi_") -> poiColor(f)
    f.k == "parcel" || f.k == "own" -> Color(0xFFF48FB1)
    f.k == "road" -> Color(0xFFFFFFFF)
    f.k == "fish" -> Color(android.graphics.Color.parseColor(com.victor.kodiakmaps.core.Fishing.textColor(f.props.optString("type"))))
    f.k == "row" -> Color(0xFFFFC107)
    f.k == "trail" -> Color(android.graphics.Color.parseColor(TrailsInfo.COLOR))
    f.k.startsWith("log") -> Color(runCatching { android.graphics.Color.parseColor(f.props.optString("color")) }.getOrDefault(0xFFFFEB3B.toInt()))
    else -> Color(android.graphics.Color.parseColor(EsmtKind.of(f.props.optString("kind")).color))
}

private fun ft(v: Int, metric: Boolean) = if (metric) "${(v * 0.3048).roundToInt()} m" else "$v ft"

@Composable
fun KodiakStrip(f: KFeature, phone: PhoneFix?, metric: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val c = kodiakColor(f)
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(c)); Spacer(Modifier.width(8.dp))
        Text(f.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        Text(phone?.let { p -> val n = f.nearestTo(p.lat, p.lon); Geo.formatDistance(Geo.distanceM(p.lat, p.lon, n[0], n[1]), metric) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

/** Photo-manifest key for a Kodiak-layer feature: fishing spots by name ("fish:<name>"), trails by id ("trail:<id>"). */
fun kodiakPhotoKey(f: KFeature): String? = when (f.k) {
    "fish" -> f.props.optString("name").takeIf { it.isNotBlank() }?.let { "fish:$it" }
    "trail" -> f.props.optString("id").takeIf { it.isNotBlank() }?.let { "trail:$it" }
    else -> null
}

@Composable
fun KodiakDetails(f: KFeature, phone: PhoneFix?, metric: Boolean, onNavigate: () -> Unit, onClose: () -> Unit) {
    if (f.k.startsWith("poi_")) { PoiDetails(f, phone, metric, onNavigate, onClose); return }
    val ctx = LocalContext.current
    val p = f.props
    fun s(k: String) = p.optString(k, "").takeIf { it.isNotBlank() && it != "null" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(f.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClose) { Text("✕") }
    }
    // v1.5.11: photo gallery at the top of fishing-spot and trail popups (same gallery + credit link as the military layer).
    // Only added above; every detail line below is unchanged. Pins without bundled photos show nothing here.
    kodiakPhotoKey(f)?.let { PoiPhotoGallery(it) }
    phone?.let { ph ->
        val n = f.nearestTo(ph.lat, ph.lon); val d = Geo.distanceM(ph.lat, ph.lon, n[0], n[1])
        val b = Geo.bearingDeg(ph.lat, ph.lon, n[0], n[1])
        Text("${Geo.formatDistance(d, metric)} ${Geo.cardinal(b)} (nearest point)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    @Composable fun line(label: String, v: String?) { if (v != null) Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp)); Text(v, fontSize = 13.sp)
    } }
    if (f.k == "fish") {
        FishDetails(f, ::s) { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }
    } else if (f.k == "parcel") {
        line("Owner", s("owner"))
        line("Legal", s("legal"))
        line("Acres", s("acres"))
        line("Address", s("addr"))
        line("Parcel", listOfNotNull(s("geo_id"), s("id")?.let { "prop $it" }).joinToString(" · ").ifBlank { null })
        line("Zoning / use", listOfNotNull(s("zone"), s("use")).joinToString(" · ").ifBlank { null })
        line("2025 value", s("value")?.let { "$$it (certified roll)" })
        line("Source", "Kodiak Island Borough assessing parcels (KIB_Parcels)")
    } else if (f.k == "own") {
        line("Owner", s("owner"))
        line("Type", s("type"))
        line("Unit", s("units"))
        line("Label", s("label"))
        line("Source", "Kodiak Island Borough land ownership (KIB_Ownership, generalized)")
    } else if (f.k == "road") {
        line("Name", s("name"))
        line("Class", s("type"))
        line("Owner", s("owner"))
        line("Maintained", s("maint"))
        line("Paved", s("paved"))
        line("Width", s("width_ft")?.let { "$it ft (surface)" })
        line("Source", s("src"))
    } else if (f.k == "trail") {
        line("Name", s("name"))
        line("Type", TrailsInfo.classLabel(s("cls")))
        line("Length", s("miles")?.toDoubleOrNull()?.let { Geo.formatDistance(it * 1609.344, metric) + " (all sections)" })
        line("Source", "${TrailsInfo.SOURCE} trails GIS")
    } else if (f.k.startsWith("bike")) {
        line("Name", s("name"))
        line("Notes", s("desc"))
        line("Type", if (f.k == "bikeline") "Bike trail" else when (s("kind")) { "head" -> "Trailhead"; "work" -> "Trail feature / work spot"; else -> "Marker" })
        line("Length", s("len_m")?.toDoubleOrNull()?.let { Geo.formatDistance(it, metric) })
        line("Folder", s("folder"))
        line("Source", s("src"))
    } else if (f.k.startsWith("log")) {
        line("Name", s("name"))
        line("Folder", s("folder"))
        line("Notes", s("desc"))
        line("Source", s("src"))
    } else if (f.k == "row") {
        line("Route", s("route"))
        line("ROW width", p.optInt("width_ft").let { w -> "${ft(w, metric)} (≈${ft(p.optInt("left_ft"), metric)} / ${ft(p.optInt("right_ft"), metric)} each side) — derived" })
        line("Mileposts", s("mp")?.let { "$it (from route start)" })
        line("Owner", s("owner"))
        line("Width basis", s("basis_text"))
        line("Source", s("source"))
        Text("Approximate band. Exact ROW limits are on DOT&PF right-of-way plans.", fontSize = 12.sp, color = Color(0xFFFFCC80), modifier = Modifier.padding(top = 4.dp))
    } else {
        line("EIN", s("ein"))
        line("Type", listOfNotNull(s("cat"), s("status")).joinToString(" · ") + (s("width_ft")?.let { " · ${ft(it.toInt(), metric)} wide" } ?: ""))
        line("BLM type", s("type"))
        line("Allowed uses", s("uses"))
        line("Restrictions", s("restrict"))
        line("Name", s("name"))
        line("Landowner", s("owner"))
        line("Accesses", s("accessed"))
        line("Admin", s("agency"))
        line("Reserved", s("reserved"))
        line("Conveyance", s("doc_no"))
        line("Size", s("acres")?.toDoubleOrNull()?.let { a -> "${if (a % 1.0 == 0.0) a.toInt() else a} acre${if (a == 1.0) "" else "s"} (circle radius ${s("r_m") ?: "?"} m)" })
        line("Patent text", s("patent"))
        line("Length", s("miles")?.toDoubleOrNull()?.let { Geo.formatDistance(it * 1609.344, metric) })
        line("Location", s("approx"))
        s("acc")?.let { a -> Text("Position accuracy: $a", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = when (s("accq")) { "poor" -> Color(0xFFFF8A80); "fair" -> Color(0xFFFFE082); else -> Color(0xFFA5D6A7) }, modifier = Modifier.padding(top = 4.dp)) }
        Text(Easements.NOTE, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    val disclaimer = when { f.k == "fish" -> com.victor.kodiakmaps.core.Fishing.CAUTION
        f.k == "trail" -> TrailsInfo.NO_WARRANTY
        f.k == "parcel" || f.k == "own" -> "Borough assessing/GIS data for reference only — not a survey. Boundaries can be off by several metres or more; check recorded plats."
        f.k == "road" -> "Road names and lines from borough/DOT&PF GIS; not a legal access determination."; f.k.startsWith("log") -> "Personal map; road conditions and gates change. Verify before relying on it."; else -> Easements.DISCLAIMER }
    Text(disclaimer, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFCC80), modifier = Modifier.padding(vertical = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onNavigate, Modifier.weight(1f)) { Text("Navigate") }
        s("doc")?.let { url -> OutlinedButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, Modifier.weight(1f)) { Text("Legal description") } }
    }
    Spacer(Modifier.height(10.dp))
}

/** "Am I on an easement?" chip text, or null when not applicable. */
fun easementCheckText(near: KGeo.Near?, metric: Boolean): Pair<String, Boolean>? {
    near ?: return null
    val name = near.f.props.optString("label").ifBlank { EsmtKind.of(near.f.props.optString("kind")).label }
    val e = near.edgeM
    return if (near.on) "On easement $name · ${Geo.formatDistance(near.centreM, metric)} from centre, ${Geo.formatDistance(-e!!, metric)} inside edge" to true
    else "Off easement · nearest $name ${Geo.formatDistance(near.centreM, metric)}" + (e?.let { " (edge ${Geo.formatDistance(it, metric)})" } ?: "") to false
}

@Composable
fun LayersDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s by Prefs.settings.collectAsState()
    val data by KodiakData.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Map layers") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Kodiak Island (shown only when the map is over the Kodiak archipelago)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable fun sw(label: String, sub: String, v: Boolean, f: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { f(!v) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(label, fontSize = 15.sp); Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(v, f)
            }
            sw("17(b) easements", "BLM ANCSA public easements: trails, roads, sites", s.layerEasements) { v -> Prefs.update(ctx) { it.copy(layerEasements = v) } }
            sw("State road right-of-way", "Chiniak Hwy & Rezanof Dr (DOT&PF), approximate band", s.layerRow) { v -> Prefs.update(ctx) { it.copy(layerRow = v) } }
            sw("Kodiak Trails", "Kodiak Island Borough trail network: ${data?.trailsSummary ?: "…"}", s.layerTrails) { v -> Prefs.update(ctx) { it.copy(layerTrails = v) } }
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val tc = Color(android.graphics.Color.parseColor(TrailsInfo.COLOR))
                listOf("━━ double track", "╍╍ single track", "··· route").forEach { Text(it, fontSize = 11.sp, color = tc) }
            }
            sw("Town bike trails", "Your Town Bike Trail Map: trails in their map colours, trailheads and work spots with notes", s.layerBike) { v -> Prefs.update(ctx) { it.copy(layerBike = v) } }
            if (data?.logJson != null) sw("Chiniak logging roads", "From the kodiakking.com Google Earth map", s.layerLogRoads) { v -> Prefs.update(ctx) { it.copy(layerLogRoads = v) } }
            sw("Property lines", "Borough parcels with owner on tap (lines from zoom 13, owner labels very close) + land ownership", s.layerParcels) { v -> Prefs.update(ctx) { it.copy(layerParcels = v) } }
            sw("Road names", "Kodiak Island Borough roads + DOT&PF centerlines, labelled", s.layerRoads) { v -> Prefs.update(ctx) { it.copy(layerRoads = v) } }
            sw("Fishing spots", "ADF&G GoFishAK spots, ramps & harbors with seasons/limits (blue salt, green fresh, yellow stocked lake)", s.layerFish) { v -> Prefs.update(ctx) { it.copy(layerFish = v) } }
            PoiLayerSwitches()
            sw("Recorded tracks", "Show my saved tracks on the map", s.showTracks) { v -> Prefs.update(ctx) { it.copy(showTracks = v) } }
            sw("Am I on an easement?", "Shows distance to the nearest easement centre and edge", s.easementCheck) { v -> Prefs.update(ctx) { it.copy(easementCheck = v) } }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(EsmtKind.TRAIL25, EsmtKind.TRAIL50, EsmtKind.ROAD, EsmtKind.SITE).forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(android.graphics.Color.parseColor(k.color)))); Text(" " + k.label.substringBefore(" ("), fontSize = 11.sp)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(data?.let { "${it.counts}\nEasements: ${it.esmtSource} data, ${it.esmtDate}" } ?: "Loading…", fontSize = 12.sp)
            Text("Sources: BLM Alaska 17(b) easements (gis.blm.gov), Alaska DOT&PF Road Centerlines, Kodiak Island Borough parcels (ROW width), Kodiak Island Borough trails (KIB_Trails, no warranty), KIB parcels/ownership/roads (assessing GIS, not a survey). Logging roads: Victor (kodiakking.com). Easement positions were checked against the borough coastline and roads; each easement shows a position-accuracy note.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Button(enabled = !busy, onClick = { busy = true; msg = null; scope.launch { msg = KodiakData.update(ctx); busy = false } }) { Text("Update easement data") }
                if (busy) CircularProgressIndicator(Modifier.padding(start = 10.dp).size(20.dp), strokeWidth = 2.dp)
            }
            msg?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Button(enabled = !busy, onClick = { busy = true; msg = null; scope.launch { msg = KodiakData.checkRegs(); busy = false } }) { Text("Update regulations") }
            }
            Text("Fishing regs summary: ${data?.regs?.let { "${it.year} ADF&G booklet, updated ${it.lastUpdated}" } ?: "not loaded"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Easements.DISCLAIMER, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFCC80), modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Done") } })
}

@Composable
private fun FishDetails(f: KFeature, s: (String) -> String?, open: (String) -> Unit) {
    val regs = KodiakData.state.collectAsState().value?.regs
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun line(label: String, v: String?) { if (v != null) Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = grey, modifier = Modifier.width(92.dp)); Text(v, fontSize = 13.sp)
    } }
    line("Type", listOfNotNull(com.victor.kodiakmaps.core.Fishing.TYPE_LABEL[s("type")], s("cat"), s("stocked")?.takeIf { it == "Yes" }?.let { "stocked/enhanced" }).joinToString(" · "))
    line("Charted as", s("charted"))
    line("Species", s("species"))
    line("Run timing", s("timing"))
    line("About", s("desc"))
    line("Getting there", s("getting"))
    line("Access", listOfNotNull(s("access"), s("accessType")?.let { "($it)" }).joinToString(" ").ifBlank { null })
    line("Parking", s("parking"))
    line("Season", s("season"))
    line("Tackle", s("tackle"))
    line("Coordinates", s("coordSrc"))
    s("acc")?.let { a -> Text("Position check: $a" + (s("accM")?.let { " (${it} m from mapped water/shore, USGS NHD + KIB coastline)" } ?: ""), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = when (a) { "poor" -> Color(0xFFFF8A80); "fair" -> Color(0xFFFFE082); else -> Color(0xFFA5D6A7) }, modifier = Modifier.padding(top = 2.dp)) }
    if (s("type") == "salt" || s("type") == "ocean") {
        val tides = com.victor.kodiakmaps.data.TideData.state.collectAsState().value
        val pt = f.point
        val st = if (tides != null && pt != null) com.victor.kodiakmaps.core.Tides.nearest(tides, pt[0], pt[1]) else null
        if (st != null) {
            val nx = com.victor.kodiakmaps.core.Tides.next(st, java.time.Instant.now())
            line("Tides", if (nx.isEmpty()) "No predictions bundled for now — refresh tides" else nx.joinToString("\n") { com.victor.kodiakmaps.core.Tides.fmt(it) } + "\nNOAA ${st.name} (${st.id})")
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    val set = regs?.sets?.get(s("regkey") ?: "")
    if (regs == null || set == null) Text("Regulations: see regs (ADF&G booklet).", fontSize = 13.sp)
    else {
        Text("${regs.year} regulations · ${set.title}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("${set.book} · summary last updated ${regs.lastUpdated}", fontSize = 11.sp, color = grey)
        val today = java.time.LocalDate.now()
        set.rules.forEach { r ->
            val se = com.victor.kodiakmaps.core.Fishing.season(r, today, regs.year)
            Column(Modifier.padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.species, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(se.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(when (se.status) { com.victor.kodiakmaps.core.Fishing.Status.OPEN -> Color(0xFF81C784); com.victor.kodiakmaps.core.Fishing.Status.CLOSED -> Color(0xFFE57373); else -> MaterialTheme.colorScheme.onSurfaceVariant })
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text(r.limit ?: "Limits: see regs", fontSize = 12.sp)
                r.cite?.let { Text(it, fontSize = 10.sp, color = grey) }
            }
        }
        (set.closures.map { "⛔ $it" } + set.gear.map { "🎣 $it" } + set.notes.map { "• $it" }).forEach { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
        s("eo")?.let { Text("⚠ $it", fontSize = 12.sp, color = Color(0xFFFFCC80), modifier = Modifier.padding(top = 4.dp)) }
        Text("Kodiak general rules", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        regs.general.forEach { Text("• $it", fontSize = 11.sp, color = Color(0xFFCFD8DC)) }
        Text(regs.eoNote, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFCC80), modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton({ open(regs.eoUrl) }, Modifier.weight(1f)) { Text("Emergency orders", fontSize = 12.sp) }
            OutlinedButton({ open(if (s("type") == "salt") regs.saltPdf else regs.freshPdf) }, Modifier.weight(1f)) { Text("${regs.year} regs PDF", fontSize = 12.sp) }
        }
        s("more")?.let { u -> TextButton({ open(u) }) { Text("ADF&G area info", fontSize = 12.sp) } }
    }
}
