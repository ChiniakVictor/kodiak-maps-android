package com.victor.kodiakmaps.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource

/**
 * Historic maps from kadiak.org, bundled under assets/kodiak/hist (offline). index.json lists every image;
 * items with "corners" (TL, TR, BR, BL as [lon, lat]) were georeferenced and can be laid over the map
 * (one at a time, semi-transparent, opacity slider). Shown as part of the Military history layer.
 */
object HistMaps {
    const val CREDIT = "Source: kadiak.org (Kodiak Historical Army/Navy site)"
    const val SRC = "kd-hist"; const val LAYER = "kd-hist-r"
    data class Item(val id: String, val title: String, val caption: String, val file: String, val w: Int, val h: Int, val group: String,
                    val srcUrl: String, val corners: List<DoubleArray>?, val verify: String?, val ovlFile: String?) {
        val overlay get() = corners != null
    }
    data class Index(val credit: String, val items: List<Item>, val structFile: String, val structTitle: String, val structCaption: String, val structUrl: String)

    @Volatile private var index: Index? = null
    /** Overlay currently on the map (item id) and its opacity. */
    val overlay = MutableStateFlow<String?>(null)
    val opacity = MutableStateFlow(0.65f)
    /** UI state: library dialog, full-screen image viewer (item id), structure list viewer. */
    val library = MutableStateFlow(false)
    val viewer = MutableStateFlow<String?>(null)
    val structures = MutableStateFlow(false)

    fun parse(json: String): Index {
        val o = JSONObject(json); val a = o.getJSONArray("items"); val s = o.getJSONObject("structure_list")
        val items = (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
            val c = j.optJSONArray("corners")?.let { ca -> (0 until ca.length()).map { k -> ca.getJSONArray(k).let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } } }
            Item(j.getString("id"), j.getString("title"), j.optString("caption"), j.getString("file"), j.optInt("w"), j.optInt("h"), j.optString("group"),
                j.optString("src_url"), c, j.optString("verify").ifBlank { null }, j.optString("ovl_file").ifBlank { null })
        }
        return Index(o.optString("credit", CREDIT), items, s.getString("file"), s.getString("title"), s.optString("caption"), s.optString("src_url"))
    }
    fun load(ctx: Context): Index? = index ?: runCatching {
        parse(ctx.assets.open("kodiak/hist/index.json").bufferedReader().use { it.readText() })
    }.getOrNull()?.also { index = it; loadPrefs(ctx) }
    fun item(ctx: Context, id: String?) = id?.let { i -> load(ctx)?.items?.firstOrNull { it.id == i } }

    private var prefsLoaded = false
    private fun loadPrefs(ctx: Context) {
        if (prefsLoaded) return; prefsLoaded = true
        val p = ctx.getSharedPreferences("hist_maps", Context.MODE_PRIVATE)
        opacity.value = p.getFloat("opacity", 0.65f); overlay.value = p.getString("overlay", null)
    }
    fun setOverlay(ctx: Context, id: String?) { overlay.value = id; ctx.getSharedPreferences("hist_maps", Context.MODE_PRIVATE).edit().putString("overlay", id).apply() }
    fun setOpacity(ctx: Context, v: Float) { opacity.value = v; ctx.getSharedPreferences("hist_maps", Context.MODE_PRIVATE).edit().putFloat("opacity", v).apply() }

    fun bitmap(ctx: Context, file: String, maxPx: Int = 4096): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open("kodiak/hist/$file").use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > maxPx) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s; if (!file.endsWith(".png")) inPreferredConfig = Bitmap.Config.RGB_565 }
        ctx.assets.open("kodiak/hist/$file").use { BitmapFactory.decodeStream(it, null, o2) }
    }.getOrNull()

    fun quad(c: List<DoubleArray>) = LatLngQuad(LatLng(c[0][1], c[0][0]), LatLng(c[1][1], c[1][0]), LatLng(c[2][1], c[2][0]), LatLng(c[3][1], c[3][0]))

    private var shown: String? = null
    /** Put [id] (or nothing) on the map under the POI layers. [visible] = Military layer on and inside the Kodiak area. */
    fun apply(st: Style, ctx: Context, id: String?, alpha: Float, visible: Boolean, below: String = "kd-poi-zone-fill") {
        val it = item(ctx, id)?.takeIf { it.overlay }
        if (it == null || shown != it.id || st.getSource(SRC) == null) {
            st.getLayer(LAYER)?.let { l -> st.removeLayer(l) }; st.getSource(SRC)?.let { s -> st.removeSource(s) }; shown = null
            if (it == null) return
            val bmp = bitmap(ctx, it.ovlFile ?: it.file, 2048) ?: return
            st.addSource(ImageSource(SRC, quad(it.corners!!), bmp))
            val layer = RasterLayer(LAYER, SRC)
            if (st.getLayer(below) != null) st.addLayerBelow(layer, below) else st.addLayer(layer)
            shown = it.id
        }
        st.getLayer(LAYER)?.setProperties(rasterOpacity(alpha.coerceIn(0.05f, 1f)), visibility(if (visible) Property.VISIBLE else Property.NONE))
    }
}

/** Thumbnails of the historic maps attached to a Military card + "show on map" for georeferenced ones. */
@Composable
fun HistAttachments(imgs: List<String>, ovl: List<String>) {
    val ctx = LocalContext.current
    val idx = remember { HistMaps.load(ctx) } ?: return
    val cur by HistMaps.overlay.collectAsState()
    val items = imgs.mapNotNull { i -> idx.items.firstOrNull { it.id == i } }
    if (items.isNotEmpty()) {
        Text("Historic maps", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { HistThumb(it, Modifier.width(120.dp)) { HistMaps.viewer.value = it.id } }
        }
    }
    ovl.mapNotNull { i -> idx.items.firstOrNull { it.id == i && it.overlay } }.forEach { o ->
        val on = cur == o.id
        OutlinedButton({ HistMaps.setOverlay(ctx, if (on) null else o.id) }, Modifier.padding(vertical = 1.dp)) {
            Text(if (on) "✕ Hide ${o.title}" else "🗺 Lay over map: ${o.title}", fontSize = 13.sp, maxLines = 1)
        }
    }
    TextButton({ HistMaps.library.value = true }) { Text("All historic maps & 1965 structure list ›", fontSize = 13.sp) }
    Text(HistMaps.CREDIT, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HistThumb(it: HistMaps.Item, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, it.file) { value = withContext(Dispatchers.IO) { HistMaps.bitmap(ctx, it.file, 320) } }
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).clickable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().height(80.dp).background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
            bmp?.let { b -> Image(b.asImageBitmap(), it.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("…")
            if (it.overlay) Text("GEO", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFD54F)).padding(horizontal = 3.dp))
        }
        Text(it.title, fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp)
    }
}

/** Small floating card while an overlay is on: name, opacity slider, close. */
@Composable
fun HistOverlayCard(modifier: Modifier) {
    val ctx = LocalContext.current
    val id by HistMaps.overlay.collectAsState()
    val a by HistMaps.opacity.collectAsState()
    val it = HistMaps.item(ctx, id) ?: return
    Column(modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE0161A1F)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🗺 ${it.title}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton({ HistMaps.viewer.value = it.id }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("ⓘ") }
            TextButton({ HistMaps.setOverlay(ctx, null) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", fontSize = 11.sp, color = Color(0xFFB0BEC5))
            Slider(a, { v -> HistMaps.setOpacity(ctx, v) }, valueRange = 0.1f..1f, modifier = Modifier.weight(1f).height(30.dp).padding(horizontal = 6.dp))
            Text("${(a * 100).toInt()}%", fontSize = 11.sp, color = Color.White)
        }
        Text(HistMaps.CREDIT, fontSize = 9.sp, color = Color(0xFFB0BEC5))
    }
}

/** Library, full-screen zoomable viewer and the structure-list reader. Host once at screen level. */
@Composable
fun HistDialogs(onShowOnMap: (HistMaps.Item) -> Unit) {
    val ctx = LocalContext.current
    val lib by HistMaps.library.collectAsState()
    val view by HistMaps.viewer.collectAsState()
    val structs by HistMaps.structures.collectAsState()
    if (lib) HistLibrary(onShowOnMap)
    HistMaps.item(ctx, view)?.let { HistViewer(it, onShowOnMap) }
    if (structs) StructureList()
}

@Composable
private fun HistLibrary(onShowOnMap: (HistMaps.Item) -> Unit) {
    val ctx = LocalContext.current
    val idx = remember { HistMaps.load(ctx) }
    val cur by HistMaps.overlay.collectAsState()
    Dialog({ HistMaps.library.value = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f), shape = RoundedCornerShape(16.dp), color = Color(0xFF161A1F)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Historic maps", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton({ HistMaps.library.value = false }) { Text("✕") }
                }
                Text(HistMaps.CREDIT + ". GEO = can be laid over the map (fitted to the Borough coastline). Bundled — works offline.", fontSize = 12.sp, color = Color(0xFFB0BEC5))
                if (idx == null) { Text("Not available"); return@Column }
                LazyColumn(Modifier.weight(1f).padding(top = 6.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).clickable { HistMaps.structures.value = true }.padding(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("📋 ${idx.structTitle}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(idx.structCaption, fontSize = 12.sp, color = Color(0xFFB0BEC5))
                            }
                        }
                    }
                    idx.items.groupBy { it.group }.forEach { (g, list) ->
                        item { Text(g, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFBCAAA4), modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                        items(list, key = { it.id }) { it ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                HistThumb(it, Modifier.width(110.dp)) { HistMaps.viewer.value = it.id }
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(it.caption, fontSize = 12.sp, maxLines = 4, color = Color(0xFFCFD8DC))
                                    Row {
                                        TextButton({ HistMaps.viewer.value = it.id }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("View", fontSize = 13.sp) }
                                        if (it.overlay) TextButton({
                                            if (cur == it.id) HistMaps.setOverlay(ctx, null) else { HistMaps.setOverlay(ctx, it.id); HistMaps.library.value = false; onShowOnMap(it) }
                                        }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (cur == it.id) "Hide on map" else "Show on map", fontSize = 13.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistViewer(it: HistMaps.Item, onShowOnMap: (HistMaps.Item) -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, it.file) { value = withContext(Dispatchers.IO) { HistMaps.bitmap(ctx, it.file, 2600) } }
    var scale by remember(it.id) { mutableFloatStateOf(1f) }
    var off by remember(it.id) { mutableStateOf(Offset.Zero) }
    var info by remember(it.id) { mutableStateOf(true) }
    Dialog({ HistMaps.viewer.value = null }, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bmp?.let { b ->
                Image(b.asImageBitmap(), it.title, Modifier.fillMaxSize()
                    .pointerInput(it.id) { detectTapGestures(onDoubleTap = { scale = if (scale > 1.5f) 1f else 3f; if (scale == 1f) off = Offset.Zero }, onTap = { info = !info }) }
                    .pointerInput(it.id) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 10f); off = if (scale == 1f) Offset.Zero else off + pan } }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y), contentScale = ContentScale.Fit)
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(6.dp)) {
                TextButton({ HistMaps.viewer.value = null }, colors = ButtonDefaults.textButtonColors(containerColor = Color(0xAA000000))) { Text("✕ Close", color = Color.White) }
            }
            if (info) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).navigationBarsPadding().padding(10.dp)) {
                Text(it.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(it.caption, fontSize = 12.sp, color = Color(0xFFCFD8DC))
                it.verify?.let { v -> Text("On-map fit: $v", fontSize = 11.sp, color = Color(0xFFFFE082), modifier = Modifier.padding(top = 2.dp)) }
                Text("${HistMaps.CREDIT} · ${it.srcUrl}", fontSize = 11.sp, color = Color(0xFFB0BEC5), modifier = Modifier.padding(top = 2.dp))
                Row {
                    Text("Pinch or double-tap to zoom · tap to hide text", fontSize = 11.sp, color = Color(0xFF90A4AE), modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                    if (it.overlay) TextButton({ HistMaps.setOverlay(ctx, it.id); HistMaps.viewer.value = null; HistMaps.library.value = false; onShowOnMap(it) }) { Text("Show on map") }
                }
            }
        }
    }
}

@Composable
private fun StructureList() {
    val ctx = LocalContext.current
    val idx = remember { HistMaps.load(ctx) }
    val lines by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) { runCatching { ctx.assets.open("kodiak/hist/${idx!!.structFile}").bufferedReader().readLines() }.getOrDefault(emptyList()) }
    }
    var q by remember { mutableStateOf("") }
    val shown = remember(lines, q) { if (q.isBlank()) lines else lines.filter { it.contains(q.trim(), ignoreCase = true) } }
    Dialog({ HistMaps.structures.value = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.94f), shape = RoundedCornerShape(16.dp), color = Color(0xFF101418)) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(idx?.structTitle ?: "Structure list", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton({ HistMaps.structures.value = false }) { Text("✕") }
                }
                Text("Building no. · map sheet · grid square · use · size. Open the matching Map sheet (e.g. 9 = Map 9A–9D, 23 = Map 23A–23D) in Historic maps. ${HistMaps.CREDIT}",
                    fontSize = 11.sp, color = Color(0xFFB0BEC5))
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), singleLine = true, label = { Text("Search (e.g. hangar, 23, quonset)") })
                Text("${shown.size} of ${lines.size} lines", fontSize = 11.sp, color = Color(0xFF90A4AE))
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    LazyColumn(Modifier.width(760.dp)) {
                        items(shown.size) { i -> Text(shown[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}
