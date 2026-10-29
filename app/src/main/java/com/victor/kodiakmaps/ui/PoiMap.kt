package com.victor.kodiakmaps.ui

import android.content.Context
import com.victor.kodiakmaps.core.KFeature
import com.victor.kodiakmaps.core.KGeo
import com.victor.kodiakmaps.data.PoiPrefs
import com.victor.kodiakmaps.service.AppLog
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONObject

/** Food / Travel / Military history / Schools (+ attendance areas) / Community points from assets/kodiak/poi.geojson. All default OFF. */
object PoiMap {
    const val SRC = "kd-poi"
    val TAP = arrayOf("kd-poi-icon", "kd-poi-pt", "kd-poi-dpt", "kd-poi-hpt", "kd-poi-hedge", "kd-poi-hline", "kd-poi-hfill", "kd-poi-zone-fill", "kd-poi-zone-line")
    private val ALL = listOf("kd-poi-zone-fill", "kd-poi-zone-line", "kd-poi-hfill", "kd-poi-hedge", "kd-poi-hline", "kd-poi-pt", "kd-poi-icon", "kd-poi-dpt", "kd-poi-hpt", "kd-poi-label")
    /** Features traced from the kadiak.org old maps (Military layer, v1.5.1) carry shape = pt | ln | poly; lines and polygons draw from this zoom. */
    const val HIST_MIN_ZOOM = 11f
    private val isPt: Expression get() = any(Expression.not(has("shape")), eq(get("shape"), literal("pt")))
    /** Dashed lines: roads, runway, flight-clearance lines and unfilled boundary polygons. Filled polygons get the solid [EDGE] outline instead. */
    private val isLn: Expression get() = any(eq(get("shape"), literal("ln")), all(eq(get("shape"), literal("poly")), neq(get("fill"), literal(1))))
    private val isPoly: Expression get() = all(eq(get("shape"), literal("poly")), eq(get("fill"), literal(1)))
    /** Single structures traced from the old maps (gun circles, numbered buildings): a darker dot, from [HIST_PT_MIN_ZOOM]. */
    private val isHistPt: Expression get() = all(has("detail"), has("src_map"))
    // v1.5.2 (Victor: "make all the shaded buildings a little darker, I can barely see them") — darker fill, higher opacity, solid dark outline
    const val FILL = "#4E342E"; const val FILL_OPACITY_BLDG = 0.65f; const val FILL_OPACITY_AREA = 0.50f
    const val EDGE = "#1F120D"; const val EDGE_W_Z11 = 1.2f; const val EDGE_W_Z14 = 1.8f; const val EDGE_W_Z16 = 2.4f
    const val LINE = "#6D4C41"; const val LINE_BOUNDARY = "#5D4037"; const val LINE_RUNWAY = "#8D6E63"
    const val HIST_PT = "#4E342E"; const val HIST_PT_MIN_ZOOM = 13f
    /** Detail points (field-GPS survey points, property detail=1) show full size and tappable from this zoom; their group summary shows at every zoom. */
    const val DETAIL_MIN_ZOOM = 14f
    /** v1.5.10 (Victor: "make the pins visible from a farther out zoom, I want people to know where to zoom in at"):
     *  old-map points and field-GPS points now draw from these zooms as small "breadcrumb" dots (no stroke, no labels),
     *  growing to full size at [HIST_PT_MIN_ZOOM] / [DETAIL_MIN_ZOOM]. Below the full-size zoom they are not opened by a tap
     *  (tap priority is exactly as before); a tap on one zooms the map in on it instead ([farDotAt]). */
    const val HIST_PT_FAR_MIN_ZOOM = 9f
    const val DETAIL_FAR_MIN_ZOOM = 10f
    /** Zoom a breadcrumb tap goes to (at least), so the full points and their labels are on screen. */
    const val FAR_TAP_ZOOM_TO = 14.5
    val ICON = mapOf("poi_food" to "🍴", "poi_travel" to "✈", "poi_mil" to "★", "poi_school" to "🏫", "poi_comm" to "📚")
    @Volatile var json: String? = null; private set
    @Volatile var labels: List<Pair<String, Int>> = emptyList(); private set

    fun load(ctx: Context): String? {
        json?.let { return it }
        return runCatching { ctx.assets.open("kodiak/poi.geojson").bufferedReader().use { it.readText() } }.getOrNull()?.also { j ->
            labels = runCatching {
                val fs = JSONObject(j).getJSONArray("features")
                (0 until fs.length()).map { fs.getJSONObject(it).getJSONObject("properties") }.filter { it.optString("k") != "poi_zone" }
                    .map { it.optString("label") to android.graphics.Color.parseColor(it.optString("color", "#FFFFFF")) }.distinctBy { it.first }
            }.getOrDefault(emptyList())
            json = j
        }
    }

    fun setup(st: org.maplibre.android.maps.Style) {
        if (st.getSource(SRC) != null) return
        st.addSource(GeoJsonSource(SRC))
        val hidden = visibility(Property.NONE)
        val col = toColor(coalesce(get("color"), literal("#FFFFFF")))
        st.addLayer(FillLayer("kd-poi-zone-fill", SRC).withFilter(all(eq(get("k"), literal("poi_zone")), eq(get("fill"), literal(1)))).withProperties(hidden,
            fillColor(col), fillOpacity(0.16f)))
        st.addLayer(LineLayer("kd-poi-zone-line", SRC).withFilter(eq(get("k"), literal("poi_zone"))).withProperties(hidden,
            lineColor(col), lineOpacity(0.9f), lineWidth(interpolate(linear(), zoom(), stop(8, 1.2f), stop(14, 2.6f)))))
        // old-map features: building footprints / areas (filled) and roads, runway, boundaries, outlines (lines)
        st.addLayer(FillLayer("kd-poi-hfill", SRC).withFilter(isPoly).withProperties(hidden,
            fillColor(FILL), fillOpacity(match(get("typ"), literal(FILL_OPACITY_BLDG), stop("area", FILL_OPACITY_AREA))), fillOutlineColor(EDGE)).also { it.minZoom = HIST_MIN_ZOOM })
        st.addLayer(LineLayer("kd-poi-hedge", SRC).withFilter(isPoly).withProperties(hidden,
            lineColor(EDGE), lineOpacity(1f), lineJoin(Property.LINE_JOIN_MITER),
            lineWidth(interpolate(linear(), zoom(), stop(11, EDGE_W_Z11), stop(14, EDGE_W_Z14), stop(16, EDGE_W_Z16)))).also { it.minZoom = HIST_MIN_ZOOM })
        st.addLayer(LineLayer("kd-poi-hline", SRC).withFilter(isLn).withProperties(hidden,
            lineColor(match(get("typ"), literal(LINE), stop("boundary", LINE_BOUNDARY), stop("runway", LINE_RUNWAY))),
            lineOpacity(match(get("typ"), literal(0.95f), stop("runway", 0.8f))), lineDasharray(arrayOf(3f, 2f)),
            lineWidth(interpolate(linear(), zoom(), stop(11, 1.6f), stop(15, match(get("typ"), literal(2.8f), stop("runway", 7f), stop("road", 2.4f)))))).also { it.minZoom = HIST_MIN_ZOOM })
        st.addLayer(CircleLayer("kd-poi-pt", SRC).withFilter(neq(get("k"), literal("poi_zone"))).withProperties(hidden,
            circleColor(col), circleRadius(interpolate(linear(), zoom(), stop(8, 6f), stop(14, 11f), stop(17, 14f))),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
        st.addLayer(CircleLayer("kd-poi-dpt", SRC).withFilter(all(neq(get("k"), literal("poi_zone")), has("detail"), Expression.not(has("src_map")))).withProperties(hidden,
            circleColor(col), circleRadius(interpolate(linear(), zoom(), stop(DETAIL_FAR_MIN_ZOOM, 2f), stop(13, 3f), stop(14, 4.5f), stop(17, 8f))),
            circleOpacity(interpolate(linear(), zoom(), stop(DETAIL_FAR_MIN_ZOOM, 0.75f), stop(DETAIL_MIN_ZOOM, 1f))),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(interpolate(linear(), zoom(), stop(12, 0f), stop(DETAIL_MIN_ZOOM, 1.5f)))).also { it.minZoom = DETAIL_FAR_MIN_ZOOM })
        st.addLayer(CircleLayer("kd-poi-hpt", SRC).withFilter(all(eq(get("k"), literal("poi_mil")), isHistPt)).withProperties(hidden,
            circleColor(HIST_PT), circleRadius(interpolate(linear(), zoom(), stop(HIST_PT_FAR_MIN_ZOOM, 2f), stop(12, 3f), stop(13, 4f), stop(17, 8f))),
            circleOpacity(interpolate(linear(), zoom(), stop(HIST_PT_FAR_MIN_ZOOM, 0.8f), stop(HIST_PT_MIN_ZOOM, 1f))),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(interpolate(linear(), zoom(), stop(11, 0.6f), stop(HIST_PT_MIN_ZOOM, 1.5f)))).also { it.minZoom = HIST_PT_FAR_MIN_ZOOM })
        st.addLayer(SymbolLayer("kd-poi-icon", SRC).withFilter(neq(get("k"), literal("poi_zone"))).withProperties(hidden,
            iconImage(concat(literal("poi-ic-"), get("k"))), iconSize(interpolate(linear(), zoom(), stop(8, 0.45f), stop(14, 0.7f))), iconAllowOverlap(true), iconIgnorePlacement(true)))
        st.addLayer(SymbolLayer("kd-poi-label", SRC).withFilter(all(has("label"), neq(get("k"), literal("poi_zone")))).withProperties(hidden,
            iconImage(concat(literal("pl-"), get("label"))), iconSize(0.62f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 22f)),
            iconAllowOverlap(false), iconOptional(true)).also { it.minZoom = 14f })
    }

    /** Glyph-free icons: [iconBitmap] makes a small emoji bitmap, [labelBitmap] a name pill. */
    fun emojiBitmap(e: String): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER; color = 0xFF101214.toInt() }
        android.graphics.Canvas(b).drawText(e, 24f, 24f - (p.descent() + p.ascent()) / 2, p)
        return b
    }

    fun setData(st: org.maplibre.android.maps.Style, labelBitmap: (String, Int) -> android.graphics.Bitmap, iconBitmap: (String) -> android.graphics.Bitmap = ::emojiBitmap) {
        val j = json ?: return
        ICON.forEach { (k, e) -> if (st.getImage("poi-ic-$k") == null) st.addImage("poi-ic-$k", iconBitmap(e)) }
        labels.forEach { (l, c) -> if (st.getImage("pl-$l") == null) st.addImage("pl-$l", labelBitmap(l.take(34), c)) }
        st.getSourceAs<GeoJsonSource>(SRC)?.setGeoJson(j)
    }

    fun apply(st: org.maplibre.android.maps.Style, inArea: Boolean, s: PoiPrefs.S) {
        val kinds = buildList {
            if (s.food) add("poi_food"); if (s.travel) add("poi_travel"); if (s.mil) add("poi_mil")
            if (s.schools) add("poi_school"); if (s.community) add("poi_comm")
        }
        val ptFilter: Expression = if (kinds.isEmpty()) eq(get("k"), literal("__none__")) else any(*kinds.map { eq(get("k"), literal(it)) }.toTypedArray())
        val mainFilter = all(ptFilter, isPt, Expression.not(has("detail")))
        listOf("kd-poi-pt", "kd-poi-icon").forEach { (st.getLayer(it) as? CircleLayer)?.setFilter(mainFilter); (st.getLayer(it) as? SymbolLayer)?.setFilter(mainFilter) }
        (st.getLayer("kd-poi-dpt") as? CircleLayer)?.setFilter(all(ptFilter, isPt, has("detail"), Expression.not(has("src_map"))))
        (st.getLayer("kd-poi-label") as? SymbolLayer)?.setFilter(all(has("label"), ptFilter, isPt))
        val milOn = eq(get("k"), literal(if (s.mil) "poi_mil" else "__none__"))
        (st.getLayer("kd-poi-hline") as? LineLayer)?.setFilter(all(milOn, isLn))
        (st.getLayer("kd-poi-hfill") as? FillLayer)?.setFilter(all(milOn, isPoly))
        (st.getLayer("kd-poi-hedge") as? LineLayer)?.setFilter(all(milOn, isPoly))
        (st.getLayer("kd-poi-hpt") as? CircleLayer)?.setFilter(all(milOn, isPt, isHistPt))
        val hv = if (inArea && s.mil) Property.VISIBLE else Property.NONE
        listOf("kd-poi-hline", "kd-poi-hfill", "kd-poi-hedge", "kd-poi-hpt").forEach { st.getLayer(it)?.setProperties(visibility(hv)) }
        val pv = if (inArea && kinds.isNotEmpty()) Property.VISIBLE else Property.NONE
        val zv = if (inArea && s.schools) Property.VISIBLE else Property.NONE
        listOf("kd-poi-pt", "kd-poi-icon", "kd-poi-dpt", "kd-poi-label").forEach { st.getLayer(it)?.setProperties(visibility(pv)) }
        listOf("kd-poi-zone-fill", "kd-poi-zone-line").forEach { st.getLayer(it)?.setProperties(visibility(zv)) }
    }

    /** Tapped POI (points win over attendance areas), as a KFeature for the detail sheet. */
    fun pickPoint(m: MapLibreMap, box: android.graphics.RectF): KFeature? {
        // v1.5.7: rank explicitly instead of trusting query order: points beat lines beat building polygons,
        // and among points the one nearest the tap wins (the plotting-room dot sits on a garrison building).
        // v1.5.10: the small dots now draw farther out, but only count for a tap from their old zooms (unchanged priority).
        val z = m.cameraPosition.zoom
        val layers = buildList {
            add("kd-poi-icon"); add("kd-poi-pt"); if (z >= DETAIL_MIN_ZOOM) add("kd-poi-dpt"); if (z >= HIST_PT_MIN_ZOOM) add("kd-poi-hpt")
            add("kd-poi-hedge"); add("kd-poi-hline"); add("kd-poi-hfill")
        }.toTypedArray()
        val fs = runCatching { m.queryRenderedFeatures(box, *layers) }.getOrNull()
            ?.filter { it.getStringProperty("id") != null } ?: return null
        val cx = box.centerX(); val cy = box.centerY()
        fun rank(f: org.maplibre.geojson.Feature): Int = when (f.geometry()) { is org.maplibre.geojson.Point -> 0; is org.maplibre.geojson.LineString, is org.maplibre.geojson.MultiLineString -> 1; else -> 2 }
        fun dist(f: org.maplibre.geojson.Feature): Float = (f.geometry() as? org.maplibre.geojson.Point)?.let {
            val q = m.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(it.latitude(), it.longitude())); (q.x - cx) * (q.x - cx) + (q.y - cy) * (q.y - cy) } ?: 0f
        val best = fs.minWithOrNull(compareBy<org.maplibre.geojson.Feature>({ rank(it) }, { dist(it) }))
        AppLog.log("POI tap: " + fs.joinToString { (it.getStringProperty("id") ?: "?") + "/" + (it.geometry()?.type() ?: "?") })
        if (best == null || rank(best) != 0) histPointNear(m, cx, cy, maxOf(box.width(), box.height()) / 2f)?.let { AppLog.log("POI tap: nearest old-map point $it"); return byId(it) }
        return best?.let { byId(it.getStringProperty("id")) }
    }

    /** v1.5.10: a tap on a far-zoom breadcrumb dot (old-map or field-GPS point shown smaller than its full-size zoom):
     *  the dot's position, so the caller can zoom in there. Only asked after [pickPoint] found nothing. */
    fun farDotAt(m: MapLibreMap, box: android.graphics.RectF): org.maplibre.android.geometry.LatLng? {
        val z = m.cameraPosition.zoom
        val layers = buildList { if (z < HIST_PT_MIN_ZOOM) add("kd-poi-hpt"); if (z < DETAIL_MIN_ZOOM) add("kd-poi-dpt") }
        if (layers.isEmpty()) return null
        val cx = box.centerX(); val cy = box.centerY()
        return runCatching { m.queryRenderedFeatures(box, *layers.toTypedArray()) }.getOrNull().orEmpty()
            .mapNotNull { (it.geometry() as? org.maplibre.geojson.Point)?.let { p -> org.maplibre.android.geometry.LatLng(p.latitude(), p.longitude()) } }
            .minByOrNull { val q = m.projection.toScreenLocation(it); (q.x - cx) * (q.x - cx) + (q.y - cy) * (q.y - cy) }
    }

    /** Fallback when the rendered query misses a small old-map dot drawn over a building: nearest visible poi_mil point from the bundled JSON. */
    private var histPts: List<Triple<String, Double, Double>>? = null
    private fun histPointNear(m: MapLibreMap, cx: Float, cy: Float, r: Float): String? {
        val layer = m.style?.getLayer("kd-poi-hpt") ?: return null
        if (layer.visibility.value != Property.VISIBLE || m.cameraPosition.zoom < HIST_PT_MIN_ZOOM) return null
        val pts = histPts ?: runCatching {
            val fs = JSONObject(json ?: return null).getJSONArray("features")
            (0 until fs.length()).map { fs.getJSONObject(it) }.filter { f ->
                val p = f.getJSONObject("properties"); val g = f.optJSONObject("geometry")
                p.optString("k") == "poi_mil" && p.has("detail") && p.has("src_map") && g?.optString("type") == "Point" && p.optString("id").isNotEmpty()
            }.map { f -> val c = f.getJSONObject("geometry").getJSONArray("coordinates"); Triple(f.getJSONObject("properties").getString("id"), c.getDouble(1), c.getDouble(0)) }
        }.getOrNull()?.also { histPts = it } ?: return null
        var bestId: String? = null; var bestD = r * r
        for ((id, lat, lon) in pts) {
            val q = m.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(lat, lon))
            val d = (q.x - cx) * (q.x - cx) + (q.y - cy) * (q.y - cy)
            if (d <= bestD) { bestD = d; bestId = id }
        }
        return bestId
    }
    /** Attendance area under the tap (checked last, so other layers win). */
    fun pickZone(m: MapLibreMap, pt: android.graphics.PointF): KFeature? =
        byId(runCatching { m.queryRenderedFeatures(pt, "kd-poi-zone-fill") }.getOrNull()?.firstOrNull()?.getStringProperty("id"))

    private fun byId(id: String?): KFeature? {
        id ?: return null
        // re-read from the bundled JSON so polygons are whole (rendered ones are tile-clipped)
        val j = json ?: return null
        return runCatching {
            val fs = JSONObject(j).getJSONArray("features")
            (0 until fs.length()).map { fs.getJSONObject(it) }.firstOrNull { it.getJSONObject("properties").optString("id") == id }
                ?.let { KGeo.parse("{\"type\":\"FeatureCollection\",\"features\":[$it]}").firstOrNull() }
        }.getOrNull()
    }
}
