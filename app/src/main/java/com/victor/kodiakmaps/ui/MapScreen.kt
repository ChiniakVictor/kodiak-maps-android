package com.victor.kodiakmaps.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.kodiakmaps.core.*
import com.victor.kodiakmaps.data.KodiakData
import com.victor.kodiakmaps.data.PinEntity
import com.victor.kodiakmaps.data.PoiPrefs
import com.victor.kodiakmaps.data.Prefs
import com.victor.kodiakmaps.data.Settings
import com.victor.kodiakmaps.map.OfflineMaps
import com.victor.kodiakmaps.service.AppLog
import org.maplibre.android.geometry.LatLng
import kotlin.math.*

private val Glass = Color(0xD9161A1F)
private val GlassDark = Color(0xF0121519)

enum class Panel { NONE, PINS, TRACKS, MAPS, SETTINGS, TIDES, HOT }

/** Map-first screen: full-bleed map, floating controls, 3-state bottom sheet (map on top, sheet below, on every screen). */
@Composable
fun MapScreen(vm: AppViewModel, handle: MapHandle) {
    val ctx = LocalContext.current
    val phone by vm.phone.collectAsState()
    val heading by vm.heading.collectAsState()
    val settings by vm.settings.collectAsState()
    val poiState by PoiPrefs.state.collectAsState()
    val cam by handle.camera.collectAsState()
    val mbtiles by OfflineMaps.mbtiles.collectAsState()
    val pins by vm.pins.collectAsState()
    val selPinId by vm.selectedPin.collectAsState()
    val navPinId by vm.nav.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val hidden by vm.hiddenTracks.collectAsState()
    val recId by vm.recording.collectAsState()
    val recStats by vm.recStats.collectAsState()
    val lastKnown by vm.lastKnown.collectAsState()
    val armTick by handle.armTick.collectAsState()
    val kodiak by KodiakData.state.collectAsState()
    LaunchedEffect(Unit) { KodiakData.ensure(ctx); com.victor.kodiakmaps.data.TideData.ensure(ctx) }
    val tides by com.victor.kodiakmaps.data.TideData.state.collectAsState()
    var tideSel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { com.victor.kodiakmaps.data.HotData.onAppOpen(ctx) }   // app open only; no background work
    val hotRes by com.victor.kodiakmaps.data.HotData.state.collectAsState()
    val hotSpots = remember(hotRes) { hotRes?.let { r -> val today = java.time.LocalDate.now(java.time.ZoneId.of("America/Anchorage"))
        HotSpots.score(r.mentions, today).mapNotNull { h -> r.mentions.firstOrNull { it.place == h.spot }?.spot?.let { it to h.score } } +
            r.counts.filter { it.moving && it.spot != null }.map { it.spot!! to 0.5 } }.orEmpty().distinctBy { it.first } }
    var showDownload by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var mapReady by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(false) }
    var kSel by remember { mutableStateOf<KFeature?>(null) }
    var kTap by remember { mutableStateOf(doubleArrayOf(0.0, 0.0)) }
    var newPinAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var editPin by remember { mutableStateOf<PinEntity?>(null) }
    handle.onUserPan = { follow = false }
    val selPin = pins.firstOrNull { it.id == selPinId }
    val navPin = pins.firstOrNull { it.id == navPinId }
    LaunchedEffect(selPinId) { if (selPinId != null) { kSel = null; panel = Panel.NONE } }
    fun openSheet() { if (settings.sheetState == 0) Prefs.update(ctx) { it.copy(sheetState = 1) } }

    fun navigateTo(f: KFeature, tapLat: Double, tapLon: Double) {
        val ph = phone; val t = if (ph != null) f.nearestTo(ph.lat, ph.lon) else doubleArrayOf(tapLat, tapLon)
        val now = System.currentTimeMillis()
        val name = (if (f.k == "row") f.props.optString("route") + " ROW" else f.props.optString("label").ifBlank { f.title }).take(30)
        val pin = PinEntity(PinUtil.newId(), name, PinKind.OTHER.name, 0xFFFFB300.toInt(), "Nearest point of ${f.title} (Kodiak layer)", t[0], t[1], null,
            now, now, now + 24 * 3_600_000L)
        AppLog.log("Kodiak: navigate to ${f.title}")
        vm.savePin(pin); vm.startNav(pin.id); kSel = null; follow = true; ph?.let { handle.flyTo(it.lat, it.lon) }
    }
    val inKodiakMe = phone?.let { KodiakArea.contains(it.lat, it.lon) } == true
    val near = remember(phone?.lat, phone?.lon, kodiak, settings.easementCheck, inKodiakMe) {
        if (!settings.easementCheck || !inKodiakMe || kodiak == null) null else phone?.let { KGeo.nearestEasement(it.lat, it.lon, kodiak!!.features) }
    }
    val focus by DebugFocus.target.collectAsState()
    LaunchedEffect(focus, kodiak, mapReady) {
        val q = focus ?: return@LaunchedEffect; val k = kodiak ?: return@LaunchedEffect; if (!mapReady) return@LaunchedEffect
        val f = k.features.firstOrNull { it.props.optString("name").equals(q, true) || it.props.optString("ein").equals(q, true) || it.props.optString("label").equals(q, true) }
        DebugFocus.target.value = null
        if (f == null) { AppLog.log("Focus: '$q' not found"); return@LaunchedEffect }
        val c = f.point ?: f.lines.firstOrNull()?.let { it[it.size / 2] } ?: f.polys.firstOrNull()?.firstOrNull() ?: return@LaunchedEffect
        follow = false; handle.autoCenter.onUserGesture(); handle.flyTo(c[0], c[1], 14.0)
        vm.selectedPin.value = null; panel = Panel.NONE; kSel = f; kTap = doubleArrayOf(c[0], c[1])
        if (settings.sheetState < 2) Prefs.update(ctx) { it.copy(sheetState = 2) }
        AppLog.log("Focus: ${f.title}")
    }
    fun dropHere() { val p = phone; if (p == null) android.widget.Toast.makeText(ctx, "No GPS fix yet", android.widget.Toast.LENGTH_SHORT).show() else newPinAt = p.lat to p.lon }
    LaunchedEffect(follow, phone?.lat, phone?.lon) { if (follow) phone?.let { handle.follow(it.lat, it.lon) } }

    BoxWithConstraints(Modifier.fillMaxSize().then(if (settings.fullscreen) Modifier else Modifier.navigationBarsPadding())) {
        val density = LocalDensity.current
        val fullH = maxHeight
        val small = maxHeight < 620.dp   // Fold cover screen / landscape
        val sheetHeights = listOf(64.dp, if (small) 210.dp else 270.dp, fullH * 0.72f)
        val sheetTarget = sheetHeights[settings.sheetState.coerceIn(0, 2)]
        var dragDelta by remember { mutableStateOf(0.dp) }
        val sheetH by animateDpAsState(if (dragDelta != 0.dp) (sheetTarget + dragDelta).coerceIn(56.dp, fullH * 0.9f) else sheetTarget, label = "sheet")
        val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 30.dp
        fun updateInsets() = with(density) { handle.insets = intArrayOf(0, topPad.roundToPx(), 0, sheetTarget.coerceAtMost(fullH - topPad - 120.dp).roundToPx()) }
        LaunchedEffect(sheetTarget, mapReady) { updateInsets() }

        // Auto-centre on me at zoom 15 at every start / after > 5 min away; any real gesture disarms it.
        LaunchedEffect(mapReady, phone?.time, lastKnown?.time, armTick) {
            if (!mapReady || handle.map == null) return@LaunchedEffect
            updateInsets()
            val t = handle.autoCenter.decide(true, phone?.let { AutoCenter.Pos(it.lat, it.lon, it.time, true) },
                lastKnown?.let { AutoCenter.Pos(it.lat, it.lon, it.time, false) }, System.currentTimeMillis()) ?: return@LaunchedEffect
            handle.centredOnce = true; handle.flyTo(t.lat, t.lon, 15.0)
        }

        val trackLines = remember(tracks, hidden, settings.showTracks) {
            if (!settings.showTracks) emptyList() else tracks.filter { it.t.id !in hidden || it.t.id == recId }.map { t -> t.pts.map { it.lat to it.lon } }
        }
        TrailMap(Modifier.fillMaxSize(), handle, phone, settings.dimMap, settings.base, mbtiles, tracks = trackLines,
            showParcels = settings.layerParcels, showRoads = settings.layerRoads,
            pins = pins, selectedPin = selPinId ?: navPinId,
            navLine = navPin?.let { np -> phone?.let { ph -> LatLng(ph.lat, ph.lon) to LatLng(np.lat, np.lon) } },
            onPinTap = { id -> vm.selectedPin.value = id; openSheet() },
            onLongPress = { la, lo -> newPinAt = la to lo },
            kodiak = kodiak, showEsmt = settings.layerEasements, showRow = settings.layerRow, showTrails = settings.layerTrails, showLog = settings.layerLogRoads, showBike = settings.layerBike, showFish = settings.layerFish, tides = tides, showTides = settings.layerTides, hot = hotSpots, showHot = settings.layerHot,
            onTideTap = { id -> tideSel = id; kSel = null; vm.selectedPin.value = null; panel = Panel.TIDES; openSheet() },
            onKodiakTap = { f, la, lo -> vm.selectedPin.value = null; panel = Panel.NONE; kSel = f; kTap = doubleArrayOf(la, lo); openSheet() }) { mapReady = true }

        // ---------------- top overlays: location banner, coordinate + heading readout, easement chip, REC chip
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            LocationBanner(vm)
            Row(Modifier.padding(start = 8.dp, top = 4.dp, end = 60.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text((phone?.let { Coords.format(it.lat, it.lon, settings.coordFormat) + (it.accuracy?.let { a -> " ±" + Geo.formatDistance(a.toDouble(), settings.metric) } ?: "") }
                    ?: "No fix") + (heading?.let { "  ·  ${it.roundToInt()}° ${Geo.cardinal(it.toDouble())}" } ?: "") +
                    (phone?.alt?.let { "  ·  " + Geo.formatAltitude(it.roundToInt(), settings.metric) } ?: ""),
                    fontSize = 12.sp, color = Color(0xFFE0E0E0), maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Glass)
                        .clickable { Prefs.update(ctx) { it.copy(coordFormat = (it.coordFormat + 1) % 3) } }.padding(horizontal = 8.dp, vertical = 3.dp))
                if (recId != null) Text("● REC ${Geo.formatDistance(recStats.second, settings.metric)}", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE0C62828)).clickable { panel = Panel.TRACKS; openSheet() }.padding(horizontal = 8.dp, vertical = 3.dp))
            }
            easementCheckText(near, settings.metric)?.let { (t, on) ->
                Row(Modifier.padding(start = 8.dp, top = 4.dp, end = 60.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xE01B5E20) else Glass)
                    .clickable { kSel = near!!.f; openSheet() }.padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text((if (on) "🟢 " else "⚪ ") + t, fontSize = 12.sp, color = Color.White, maxLines = 1)
                }
            }
        }

        // ---------------- floating map buttons riding the sheet's top edge; overflow menu when there's no room
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val btnColumnH = 42.dp * 6 + 8.dp * 5
        val bottomOfButtons = sheetH + 8.dp
        fun followTap() {
            val p = phone
            if (p == null) android.widget.Toast.makeText(ctx, "No GPS fix yet", android.widget.Toast.LENGTH_SHORT).show()
            else if (!follow) { follow = true; handle.flyTo(p.lat, p.lon, cam.zoom.coerceAtLeast(14.0)) } else follow = false
        }
        if (fullH - bottomOfButtons - topInset - 40.dp >= btnColumnH) {
            Column(Modifier.align(Alignment.BottomEnd).padding(bottom = bottomOfButtons, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapIconButton(Modifier.rotate(-cam.bearing.toFloat()), "Compass: tap for north up", onClick = { handle.northUp() }) {
                    Text("▲", color = Color(0xFFFF5252), fontSize = 12.sp, modifier = Modifier.offset(y = (-7).dp))
                    Text("N", fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.offset(y = 5.dp))
                }
                MapIconButton(Modifier, desc = "Follow me", active = follow, target = "gps", onClick = { followTap() }) {
                    Text(if (follow) "◉" else "◎", fontSize = 20.sp, color = if (phone == null) Color(0xFF78909C) else Color(0xFF82B1FF))
                }
                MapIconButton(desc = "Drop pin at my location", onClick = { dropHere() }) { Text("📍", fontSize = 17.sp) }
                MapIconButton(active = recId != null, desc = "Record track", onClick = { panel = Panel.TRACKS; openSheet() }) { Text("⏺", fontSize = 18.sp, color = Color(0xFFFF5252)) }
                MapIconButton(desc = "Save area offline", onClick = { showDownload = true }) { Text("⬇", fontSize = 18.sp) }
                MapIconButton(desc = "Layers", target = "layers", onClick = { showLayers = true }) { Text("◫", fontSize = 18.sp) }
            }
        } else {
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = bottomOfButtons.coerceAtMost(fullH - topInset - 50.dp), end = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapIconButton(desc = "Follow me", active = follow, target = "gps", onClick = { followTap() }) { Text(if (follow) "◉" else "◎", fontSize = 20.sp, color = Color(0xFF82B1FF)) }
                    MapIconButton(desc = "Map controls", active = menu, target = "layers", onClick = { menu = true }) { Text("⋯", fontSize = 22.sp, fontWeight = FontWeight.Black) }
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("North up") }, { menu = false; handle.northUp() })
                    DropdownMenuItem({ Text("📍 Drop pin at my location") }, { menu = false; dropHere() })
                    DropdownMenuItem({ Text("⏺ Tracks / record") }, { menu = false; panel = Panel.TRACKS; openSheet() })
                    DropdownMenuItem({ Text("Save area offline") }, { menu = false; showDownload = true })
                    DropdownMenuItem({ Text("Layers…") }, { menu = false; showLayers = true })
                    DropdownMenuItem({ Text("Tutorial") }, { menu = false; Onboarding.startTour() })
                    DropdownMenuItem({ Text("Privacy") }, { menu = false; Onboarding.openPrivacy() })
                }
            }
        }

        // ---------------- layer side rail (left edge): one toggle per layer, collapsible, scrolls on short screens
        LayerRail(settings, kodiak?.logJson != null, onHot = { kSel = null; vm.selectedPin.value = null; panel = Panel.HOT; openSheet() }, onTides = { kSel = null; vm.selectedPin.value = null; panel = Panel.TIDES; openSheet() }, Modifier.align(Alignment.TopStart).tourTarget("rail")
            .padding(top = topPad + if (near != null) 52.dp else 28.dp, bottom = sheetH + 30.dp), onMore = { showLayers = true })

        if (poiState.mil) HistOverlayCard(Modifier.align(Alignment.TopCenter).padding(top = topPad + if (near != null) 52.dp else 26.dp, start = 64.dp, end = 56.dp))
        UpdateButton(Modifier.align(Alignment.BottomCenter).padding(bottom = sheetH + 22.dp))

        // ---------------- scale bar + attribution (bottom-left, above the sheet)
        ScaleBar(cam.metersPerDp, settings.metric, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = sheetH + 8.dp))
        Text(if (settings.base == "otm") "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)" else "USGS The National Map",
            Modifier.align(Alignment.BottomStart).padding(start = 110.dp, bottom = sheetH + 2.dp), fontSize = 9.sp, color = Color(0x99FFFFFF))

        // ---------------- bottom sheet
        Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 840.dp).fillMaxWidth().height(sheetH).tourTarget("sheet")
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(GlassDark)) {
            val drag = rememberDraggableState { px -> dragDelta -= with(density) { px.toDp() } }
            Box(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = {
                val h = sheetTarget + dragDelta
                val nearest = sheetHeights.indices.minBy { abs((sheetHeights[it] - h).value) }
                Prefs.update(ctx) { s -> s.copy(sheetState = nearest) }; dragDelta = 0.dp
            }).clickable { Prefs.update(ctx) { s -> s.copy(sheetState = if (s.sheetState == 0) 1 else 0) } }) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF607D8B)))
                if (settings.sheetState == 0 && dragDelta == 0.dp || sheetH < 90.dp)
                    Box(Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 6.dp)) {
                        when {
                            navPin != null -> NavPanel(navPin, phone, heading, settings.metric, settings.paceMps.toDouble(), compact = true) { vm.stopNav() }
                            kSel != null -> KodiakStrip(kSel!!, phone, settings.metric)
                            selPin != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${pinEmoji(selPin)} ${selPin.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(phone?.let { Geo.formatDistance(Geo.distanceM(it.lat, it.lon, selPin.lat, selPin.lon), settings.metric) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Black)
                            }
                            else -> HomeStrip(phone, heading, settings.metric, pins.size, recId != null)
                        }
                    }
                else Spacer(Modifier.fillMaxWidth().height(16.dp))
            }
            if (sheetH >= 90.dp) Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                when {
                    navPin != null && panel == Panel.NONE -> NavPanel(navPin, phone, heading, settings.metric, settings.paceMps.toDouble(), compact = false) { vm.stopNav() }
                    kSel != null && panel == Panel.NONE -> KodiakDetails(kSel!!, phone, settings.metric, onNavigate = { navigateTo(kSel!!, kTap[0], kTap[1]) }, onClose = { kSel = null })
                    selPin != null && panel == Panel.NONE -> PinDetails(selPin, phone, heading, settings.metric, settings.coordFormat,
                        onNavigate = { vm.startNav(selPin.id); vm.selectedPin.value = null; follow = true; phone?.let { handle.flyTo(it.lat, it.lon) } },
                        onEdit = { editPin = selPin }, onDelete = { vm.deletePin(selPin) }, onClose = { vm.selectedPin.value = null })
                    else -> {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(Panel.PINS to "📍 Pins", Panel.TRACKS to "⏺ Tracks", Panel.MAPS to "⬇ Maps", Panel.TIDES to "≈ Tides", Panel.HOT to "🔥 Hot", Panel.SETTINGS to "⚙").forEach { (p, l) ->
                                FilterChip(panel == p || (panel == Panel.NONE && p == Panel.PINS), { panel = p }, { Text(l, fontSize = 13.sp) })
                            }
                        }
                        when (panel) {
                            Panel.TRACKS -> TracksPanel(vm)
                            Panel.MAPS -> MapsPanel(settings.base)
                            Panel.SETTINGS -> SettingsPanel()
                            Panel.HOT -> HotPanel { name -> kodiak?.features?.firstOrNull { it.k == "fish" && it.props.optString("name") == name }?.let { f ->
                                f.point?.let { handle.flyTo(it[0], it[1], 13.0) }; panel = Panel.NONE; kSel = f; kTap = f.point ?: doubleArrayOf(0.0, 0.0) } }
                            Panel.TIDES -> TidesPanel(tideSel, { tideSel = it }) { la, lo -> handle.flyTo(la, lo, 12.0) }
                            else -> PinsPanel(vm, phone, settings.metric) { p -> vm.selectedPin.value = p.id; handle.flyTo(p.lat, p.lon, 15.0) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    if (showDownload) DownloadDialog(handle, settings.base) { showDownload = false }
    if (showLayers) LayersDialog { showLayers = false }
    HistDialogs { h -> PoiPrefs.update(ctx) { it.copy(mil = true) }; h.corners?.let { c -> handle.fit(c.map { LatLng(it[1], it[0]) }) } }
    newPinAt?.let { (la, lo) -> PinDialog(la, lo, phone?.alt?.takeIf { newPinAt == phone?.let { p -> p.lat to p.lon } }?.roundToInt(), null,
        onSave = { vm.savePin(it); newPinAt = null; vm.selectedPin.value = it.id; openSheet() }, onDismiss = { newPinAt = null }) }
    editPin?.let { e -> PinDialog(e.lat, e.lon, e.alt, e, onSave = { vm.savePin(it); editPin = null }, onDismiss = { editPin = null }) }
}

@Composable
private fun HomeStrip(phone: PhoneFix?, heading: Float?, metric: Boolean, nPins: Int, rec: Boolean) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🐻 Kodiak Maps", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text((if (rec) "● REC  " else "") + "$nPins pins  ·  " + (heading?.let { "${it.roundToInt()}°" } ?: "—") + "  ·  " +
            (phone?.accuracy?.let { "±" + Geo.formatDistance(it.toDouble(), metric) } ?: "no fix"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MapIconButton(modifier: Modifier = Modifier, desc: String, active: Boolean = false, target: String? = null, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box((if (target != null) Modifier.tourTarget(target) else Modifier).size(42.dp).clip(CircleShape).background(if (active) Color(0xF02E7D32) else Glass)
        .combinedClickable(onClickLabel = desc, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(modifier, contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun ScaleBar(metersPerDp: Double, metric: Boolean, modifier: Modifier) {
    if (metersPerDp <= 0) return
    val maxM = metersPerDp * 90.0
    val (len, label) = if (metric) {
        val v = nice(maxM); (v / metersPerDp) to (if (v >= 1000) "${fmt(v / 1000)} km" else "${v.roundToInt()} m")
    } else {
        val ft = maxM * 3.28084
        if (ft < 2640) { val v = nice(ft); (v / 3.28084 / metersPerDp) to "${v.roundToInt()} ft" }
        else { val v = nice(maxM / 1609.344); (v * 1609.344 / metersPerDp) to "${fmt(v)} mi" }
    }
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Box(Modifier.width(len.dp).height(6.dp).border(1.5.dp, Color.White).background(Color(0x66000000)))
    }
}
private fun nice(x: Double): Double { val e = 10.0.pow(floor(log10(x))); val f = x / e; return e * when { f >= 5 -> 5.0; f >= 2 -> 2.0; else -> 1.0 } }
private fun fmt(v: Double) = if (v == floor(v)) v.toInt().toString() else "%.1f".format(v)

@Composable
private fun DownloadDialog(handle: MapHandle, base: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val bounds = remember { handle.visibleBounds() }
    val z = handle.zoom().roundToInt()
    var name by remember { mutableStateOf("Area ${java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date())}") }
    var minZ by remember { mutableFloatStateOf((z - 3).coerceIn(6, 14).toFloat()) }
    var maxZ by remember { mutableFloatStateOf(15f.coerceAtLeast(minZ)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val est = bounds?.let { OfflineMaps.estimate(it, minZ.roundToInt(), maxZ.roundToInt()) } ?: 0
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Save this map area for offline") }, text = {
        Column {
            if (bounds == null) Text("Map not ready")
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
            Text("Zoom levels ${minZ.roundToInt()} – ${maxZ.roundToInt()} (16 = most detail)", Modifier.padding(top = 10.dp))
            Text("From"); Slider(minZ, { minZ = it.coerceAtMost(maxZ) }, valueRange = 4f..16f, steps = 11)
            Text("To"); Slider(maxZ, { maxZ = it.coerceAtLeast(minZ) }, valueRange = 4f..16f, steps = 11)
            Text("≈ $est tiles, about ${(est * 18 / 1024).coerceAtLeast(1)} MB", fontWeight = FontWeight.Bold,
                color = if (est > OfflineMaps.MAX_TILES_PER_REGION) Color(0xFFFF5252) else Color.Unspecified)
            Text("Downloads the ${OfflineMaps.baseLabel(base)} map on Wi-Fi only, limit ${OfflineMaps.MAX_TILES_PER_REGION} tiles per area. The Kodiak layers are always offline.", fontSize = 14.sp)
            msg?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 6.dp)) }
        }
    }, confirmButton = {
        TextButton(enabled = bounds != null, onClick = {
            OfflineMaps.download(ctx, base, name, bounds!!, minZ.roundToInt(), maxZ.roundToInt(), density) { err -> if (err == null) onDismiss() else msg = err }
        }) { Text("Download") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

private class RailItem(val label: String, val icon: String, val on: Boolean, val color: Color, val desc: String, val act: (() -> Unit)? = null, val toggle: (Settings) -> Settings)

/** Slim semi-transparent vertical rail of layer toggles: filled = on, outline = off; long-press for a description/legend. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRail(st: com.victor.kodiakmaps.data.Settings, hasLog: Boolean, onHot: () -> Unit, onTides: () -> Unit, modifier: Modifier, onMore: () -> Unit) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<RailItem?>(null) }
    LaunchedEffect(Unit) { PoiPrefs.load(ctx) }
    val poi by PoiPrefs.state.collectAsState()
    val histOn = HistMaps.overlay.collectAsState().value != null
    fun poiItem(label: String, icon: String, on: Boolean, color: Color, desc: String, f: (PoiPrefs.S) -> PoiPrefs.S) =
        RailItem(label, icon, on, color, desc, act = { PoiPrefs.update(ctx, f) }) { it }
    val items = listOfNotNull(
        RailItem("Easemt", "⛺", st.layerEasements, Color(0xFFFF9100), "17(b) easements (BLM): orange 25-ft trail, red 50-ft trail, purple road, blue P = 1- or 2-acre site (circle drawn to its acreage), grey 17b = not mapped by BLM (approximate). Tap one for EIN, uses, patent text and position accuracy.") { it.copy(layerEasements = !it.layerEasements) },
        RailItem("ROW", "▬", st.layerRow, Color(0xFFFFC107), "State road right-of-way band (Chiniak Hwy, Rezanof Dr). Widths are derived/estimated; check DOT&PF plans.") { it.copy(layerRow = !it.layerRow) },
        RailItem("Trails", "🥾", st.layerTrails, Color(0xFF00E5FF), "Kodiak Island Borough trails (cyan): solid double track, dashed single track, dotted route.") { it.copy(layerTrails = !it.layerTrails) },
        RailItem("Bike", "🚵", st.layerBike, Color(0xFFE040FB), "Town bike trails (your Town Bike Trail Map): lines in the colours from your Google map with a white outline; green dot = trailhead, orange dot = trail feature / work spot (jumps, mud, bridges, cleanup), grey dot = unnamed marker. Names show from zoom 14. Tap any line or dot for its name and notes.") { it.copy(layerBike = !it.layerBike) },
        if (hasLog) RailItem("Logging", "🪵", st.layerLogRoads, Color(0xFFFFEB3B), "Chiniak logging roads from your kodiakking.com Google Earth map, with gates and pins.") { it.copy(layerLogRoads = !it.layerLogRoads) } else null,
        RailItem("Property", "▦", st.layerParcels, Color(0xFFF48FB1), "Borough parcels (pink lines from zoom 13, owner labels very close) and land ownership. Tap for owner, acres, legal.") { it.copy(layerParcels = !it.layerParcels) },
        RailItem("Roads", "🛣", st.layerRoads, Color(0xFFFFFFFF), "Road names (Kodiak Island Borough roads + DOT&PF), labelled from zoom 13.") { it.copy(layerRoads = !it.layerRoads) },
        RailItem("Fishing", "🐟", st.layerFish, Color(0xFF66BB6A), "ADF&G fishing spots: navy (white fish) = offshore grounds incl. Buoy 4 & Buoy 5; light blue = shore salt water, ramps & harbors; green = rivers/creeks; yellow = stocked lakes. Tap for species, run timing, access and the current season/limits.") { it.copy(layerFish = !it.layerFish) },
        RailItem("Hot", "🔥", st.layerHot, Color(0xFFFF7043), "Hot spots: flame on fishing spots mentioned in the last 10 days by ADF&G weekly reports or public Reddit posts, or with rising ADF&G weir counts. Use 🔥 List below for quotes, sources and dates. Only checked when the app opens or you tap Refresh.") { it.copy(layerHot = !it.layerHot) },
        RailItem("Tides", "≈", st.layerTides, Color(0xFF80DEEA), "NOAA tide stations on the map (tap one for its tide table). Use the ≈ Table button below for today's highs/lows, the tide curve and the next 7 days.") { it.copy(layerTides = !it.layerTides) },
        poiItem("Food", "🍴", poi.food, Color(0xFFFF7043), "Food: orange = ready to eat (restaurants, coffee, food trucks), green = groceries / uncooked (stores, seafood, farmers market). Tap a dot for address, phone, hours, website and sources. Base-only places are marked restricted.") { it.copy(food = !it.food) },
        poiItem("Travel", "✈", poi.travel, Color(0xFF29B6F6), "Travel: blue = airports, teal = floatplane bases, indigo = AMHS ferry terminals, grey = harbors, yellow = air charter / air taxi offices. Tap for airlines, phone, website and schedule links (schedules are not stored — always check the operator).") { it.copy(travel = !it.travel) },
        poiItem("Schools", "🏫", poi.schools, Color(0xFFE53935), "KIBSD schools with shaded attendance areas, one colour per school. Tap a school for grades, phone, principal and history; tap a shaded area for its boundary source. Town areas are approximate (derived).") { it.copy(schools = !it.schools) },
        poiItem("Military", "★", poi.mil, Color(0xFF8D6E63), "Military history: WWII Fort J.H. Smith, Miller Field, Chiniak tracking station, plus field-GPS points for Batteries 1–6 (Chiniak, Buskin, Long Island, Fort Abercrombie), searchlights and more. Each battery shows as one star; zoom in to 14+ to see every surveyed point. Cards show how sure the location is and kadiak.org historic maps (some can be laid over the map with an opacity slider: use 🗺 Old maps).") { it.copy(mil = !it.mil) },
        if (poi.mil) RailItem("Old maps", "🗺", histOn, Color(0xFFBCAAA4), "Historic maps from kadiak.org (Kodiak Historical Army/Navy site): view any map full screen, lay a fitted map over this one with an opacity slider, and read the 1965 Navy structure list. Bundled, works offline.", act = { HistMaps.library.value = true }) { it } else null,
        poiItem("Library", "📚", poi.community, Color(0xFFAB47BC), "Community: Chiniak Public Library (hours, phone).") { it.copy(community = !it.community) },
        RailItem(if (st.base == "otm") "OpenTopo" else "USGS", "🗺", true, Color(0xFF82B1FF), "Base map: tap to switch between USGS topo and OpenTopoMap.") { it.copy(base = if (it.base == "otm") "usgs" else "otm") },
    )
    Box(modifier) {
        if (!st.railOpen) {
            Box(Modifier.padding(top = 4.dp).size(width = 30.dp, height = 56.dp).clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)).background(Color(0xCC161A1F))
                .clickable(onClickLabel = "Show layer bar") { Prefs.update(ctx) { it.copy(railOpen = true) } }, contentAlignment = Alignment.Center) {
                Text("›", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        } else {
            var dx by remember { mutableStateOf(0f) }
            Column(Modifier.width(60.dp).clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)).background(Color(0x99161A1F))
                .draggable(rememberDraggableState { dx += it }, Orientation.Horizontal, onDragStopped = { if (dx < -40f) Prefs.update(ctx) { it.copy(railOpen = false) }; dx = 0f })
                .verticalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(34.dp).clickable(onClickLabel = "Hide layer bar") { Prefs.update(ctx) { it.copy(railOpen = false) } }, contentAlignment = Alignment.Center) {
                    Text("‹", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                items.forEach { it ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(onClickLabel = it.label, onLongClick = { info = it }) { it.act?.invoke() ?: Prefs.update(ctx, it.toggle) }
                        .padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(if (it.on) it.color.copy(alpha = 0.85f) else Color.Transparent)
                            .border(2.dp, if (it.on) Color.White else it.color.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                            Text(it.icon, fontSize = 16.sp, color = if (it.on) Color.Black else it.color)
                        }
                        Text(it.label, fontSize = 10.sp, maxLines = 1, color = if (it.on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (it.on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClickLabel = "Hot spots list", onClick = onHot), contentAlignment = Alignment.Center) {
                    Text("🔥 List", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClickLabel = "Tide table", onClick = onTides), contentAlignment = Alignment.Center) {
                    Text("≈ Table", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClickLabel = "All layer options", onClick = onMore), contentAlignment = Alignment.Center) {
                    Text("◫ More", fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
    info?.let { i -> AlertDialog(onDismissRequest = { info = null }, confirmButton = { TextButton({ info = null }) { Text("OK") } },
        title = { Text("${i.label} — ${if (i.on) "on" else "off"}") }, text = { Text(i.desc) }) }
}
