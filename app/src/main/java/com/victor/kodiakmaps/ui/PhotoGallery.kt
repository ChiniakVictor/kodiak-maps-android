package com.victor.kodiakmaps.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v1.5.4: historic photos for Military-layer features, bundled offline in assets/kodiak/photos/.
 * aftrack.json = the Chiniak Air Force Tracking Station photos from kadiak.org (https://www.kadiak.org/af_track/af.html).
 * v1.5.6: chiniak_*.json = every photo on kadiak.org Chiniak Maps (chinmap.html) and its location sub-pages (tour, Navy DF, USCG 1945,
 *   Round Top radar, Bald Hill, Isador Island, Cape Greville, SuperDARN), one manifest per page; chiniak_chinmap_maps.json (maps) is last.
 * v1.5.5: cmdbunker.json = the WWII Plotting Bunker at Chiniak photos (https://www.kadiak.org/chiniak/command.html).
 * Each photo keeps its caption and credit as given on its page; its source page is linked under it. A feature listed in
 * several manifests gets all of their photos, in manifest order.
 */
object PoiPhotos {
    /** heading = gallery title ("Historic photos" unless the manifest sets one). */
    data class Source(val title: String, val url: String, val note: String, val label: String, val heading: String = "Historic photos")
    /** v1.5.11: a photo may carry its own credit link (label/url) and licence (license/licenseUrl); otherwise the manifest source is used. */
    data class Photo(val file: String, val caption: String, val credit: String, val w: Int, val h: Int, val source: Source,
                     val label: String = "", val url: String = "", val license: String = "", val licenseUrl: String = "") {
        val linkLabel: String get() = label.ifBlank { source.label }
        val linkUrl: String get() = url.ifBlank { source.url }
    }
    data class Set(val photos: List<Photo>, val source: Source)

    private val MANIFESTS = listOf("kodiak/photos/aftrack.json", "kodiak/photos/cmdbunker.json",
        "kodiak/photos/chiniak_chinmap.json", "kodiak/photos/chiniak_tour.json", "kodiak/photos/chiniak_df.json", "kodiak/photos/chiniak_cg.json", "kodiak/photos/chiniak_scr296.json", "kodiak/photos/chiniak_bald.json", "kodiak/photos/chiniak_isador.json", "kodiak/photos/chiniak_greville.json", "kodiak/photos/chiniak_darn.json", "kodiak/photos/chiniak_chinmap_maps.json",
        // v1.5.9: kadiak.org (Joe Stevens) photos for the best-placed sites around Kodiak
        "kodiak/photos/kad_abercrombie.json", "kodiak/photos/kad_museum.json", "kodiak/photos/kad_gibson.json", "kodiak/photos/kad_deer.json", "kodiak/photos/kad_deer_scr582.json", "kodiak/photos/kad_deer_bd74.json", "kodiak/photos/kad_castle.json", "kodiak/photos/kad_castle_scr296.json", "kodiak/photos/kad_puffin.json", "kodiak/photos/kad_midway.json", "kodiak/photos/kad_acs.json", "kodiak/photos/kad_whazzit.json", "kodiak/photos/kad_woody_range.json", "kodiak/photos/kad_woody.json", "kodiak/photos/kad_crash.json", "kodiak/photos/kad_buskin.json", "kodiak/photos/kad_spruce.json", "kodiak/photos/kad_pillar.json", "kodiak/photos/kad_pillar_mw.json", "kodiak/photos/kad_airport.json", "kodiak/photos/kad_navy.json", "kodiak/photos/kad_uscg.json", "kodiak/photos/kad_entrance.json",
        // v1.5.11: photos for other layers' pins (fishing spots "fish:<name>", harbors/travel and the library by POI id, trails "trail:<id>").
        // Public-domain (USFWS, FAA, USCG, NOAA, NREL...), CC BY / CC BY-SA (author, licence and Wikimedia Commons link shown) and Victor's own photos.
        "kodiak/photos/fish.json", "kodiak/photos/travel.json", "kodiak/photos/trails.json", "kodiak/photos/comm.json")
    @Volatile private var byFeature: Map<String, Set>? = null

    fun forFeature(ctx: Context, id: String): Set? {
        val m = byFeature ?: load(ctx).also { byFeature = it }
        return m[id]
    }

    private fun load(ctx: Context): Map<String, Set> = buildMap<String, Set> {
        MANIFESTS.forEach { path ->
            runCatching {
                val j = JSONObject(ctx.assets.open(path).bufferedReader().use { it.readText() })
                val s = j.getJSONObject("source")
                val src = Source(s.optString("title"), s.optString("url"), s.optString("note"), s.optString("label").ifBlank { "kadiak.org" }, s.optString("heading").ifBlank { "Historic photos" })
                val ph = j.getJSONObject("photos")
                val fs = j.getJSONObject("features")
                fs.keys().forEach { fid ->
                    val ids = fs.getJSONArray(fid)
                    val list = (0 until ids.length()).mapNotNull { i ->
                        ph.optJSONObject(ids.getString(i))?.let { p ->
                            Photo("kodiak/photos/" + p.getString("file"), p.optString("caption"), p.optString("credit"), p.optInt("w", 4), p.optInt("h", 3), src,
                                p.optString("label"), p.optString("url"), p.optString("license"), p.optString("licenseUrl"))
                        }
                    }
                    if (list.isNotEmpty()) put(fid, get(fid)?.let { Set(it.photos + list, it.source) } ?: Set(list, src))
                }
            }
        }
    }

    private val cache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun bitmap(ctx: Context, file: String): Bitmap? = cache.get(file) ?: withContext(Dispatchers.IO) {
        runCatching { ctx.assets.open(file).use { BitmapFactory.decodeStream(it) } }.getOrNull()?.also { cache.put(file, it) }
    }
}

@Composable
private fun AssetPhoto(file: String, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, file) { value = PoiPhotos.bitmap(ctx, file) }
    Box(modifier, contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun PhotoCaption(set: PoiPhotos.Set, page: Int, dark: Boolean) {
    val ctx = LocalContext.current
    val p = set.photos[page]
    val fg = if (dark) Color(0xFFECEFF1) else MaterialTheme.colorScheme.onSurface
    val dim = if (dark) Color(0xFFB0BEC5) else MaterialTheme.colorScheme.onSurfaceVariant
    Text("${page + 1} / ${set.photos.size}", fontSize = 11.sp, color = dim, fontWeight = FontWeight.Bold)
    Text(p.caption, fontSize = 13.sp, color = fg, fontStyle = if (p.caption.startsWith("[")) FontStyle.Italic else FontStyle.Normal)
    Text(p.credit, fontSize = 11.sp, color = dim)
    val link = buildAnnotatedString {
        append("Photo credit: ")
        withStyle(SpanStyle(color = Color(0xFF64B5F6), textDecoration = TextDecoration.Underline)) { append(p.linkLabel) }
    }
    Text(link, fontSize = 12.sp, color = fg, modifier = Modifier.padding(top = 2.dp).clickable {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.linkUrl))) }
    })
    if (p.license.isNotBlank()) {
        val lic = buildAnnotatedString {
            append("License: ")
            if (p.licenseUrl.isNotBlank()) withStyle(SpanStyle(color = Color(0xFF64B5F6), textDecoration = TextDecoration.Underline)) { append(p.license) } else append(p.license)
        }
        Text(lic, fontSize = 11.sp, color = dim, modifier = if (p.licenseUrl.isBlank()) Modifier else Modifier.clickable {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.licenseUrl))) }
        })
    }
}

@Composable
private fun PagerDots(state: PagerState, n: Int, onPick: (Int) -> Unit) {
    if (n < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ onPick((state.currentPage - 1).coerceAtLeast(0)) }, enabled = state.currentPage > 0) { Text("‹ Prev") }
        Spacer(Modifier.weight(1f))
        TextButton({ onPick((state.currentPage + 1).coerceAtMost(n - 1)) }, enabled = state.currentPage < n - 1) { Text("Next ›") }
    }
}

/** Swipeable gallery shown at the top of a POI / fishing spot / trail detail sheet when the feature has bundled photos. */
@Composable
fun PoiPhotoGallery(featureId: String) {
    val ctx = LocalContext.current
    val set = remember(featureId) { PoiPhotos.forFeature(ctx, featureId) } ?: return
    val n = set.photos.size
    val state = rememberPagerState { n }
    val scope = rememberCoroutineScope()
    var full by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("📷 ${set.source.heading} ($n)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8D6E63))
        HorizontalPager(state, Modifier.fillMaxWidth().height(200.dp).padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF15191C)),
            pageSpacing = 8.dp, beyondViewportPageCount = 1) { i ->
            AssetPhoto(set.photos[i].file, Modifier.fillMaxSize().clickable { full = true })
        }
        PagerDots(state, n) { scope.launch { state.animateScrollToPage(it) } }
        PhotoCaption(set, state.currentPage, dark = false)
    }
    if (full) {
        Dialog({ full = false }, DialogProperties(usePlatformDefaultWidth = false)) {
            val fs = rememberPagerState(initialPage = state.currentPage) { n }
            Column(Modifier.fillMaxSize().background(Color(0xF0000000)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(set.source.heading, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton({ full = false }) { Text("✕", color = Color.White) }
                }
                HorizontalPager(fs, Modifier.fillMaxWidth().weight(1f), pageSpacing = 12.dp) { i -> AssetPhoto(set.photos[i].file, Modifier.fillMaxSize()) }
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    PagerDots(fs, n) { scope.launch { fs.animateScrollToPage(it) } }
                    PhotoCaption(set, fs.currentPage, dark = true)
                }
            }
        }
    }
}
