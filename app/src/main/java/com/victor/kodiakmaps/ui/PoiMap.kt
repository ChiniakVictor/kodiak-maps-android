package com.victor.kodiakmaps.ui

import android.content.Context
import com.victor.kodiakmaps.core.KFeature
import com.victor.kodiakmaps.core.KGeo
import com.victor.kodiakmaps.data.PoiPrefs
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
    val TAP = arrayOf("kd-poi-icon", "kd-poi-pt", "kd-poi-dpt", "kd-poi-zone-fill", "kd-poi-zone-line")
    private val ALL = listOf("kd-poi-zone-fill", "kd-poi-zone-line", "kd-poi-pt", "kd-poi-icon", "kd-poi-dpt", "kd-poi-label")
    /** Detail points (field-GPS survey points, property detail=1) only show from this zoom so the map is not cluttered; their group summary shows at every zoom. */
    const val DETAIL_MIN_ZOOM = 14f
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
        st.addLayer(CircleLayer("kd-poi-pt", SRC).withFilter(neq(get("k"), literal("poi_zone"))).withProperties(hidden,
            circleColor(col), circleRadius(interpolate(linear(), zoom(), stop(8, 6f), stop(14, 11f), stop(17, 14f))),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
        st.addLayer(CircleLayer("kd-poi-dpt", SRC).withFilter(all(neq(get("k"), literal("poi_zone")), has("detail"))).withProperties(hidden,
            circleColor(col), circleRadius(interpolate(linear(), zoom(), stop(14, 4.5f), stop(17, 8f))),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.5f)).also { it.minZoom = DETAIL_MIN_ZOOM })
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
        val mainFilter = all(ptFilter, Expression.not(has("detail")))
        listOf("kd-poi-pt", "kd-poi-icon").forEach { (st.getLayer(it) as? CircleLayer)?.setFilter(mainFilter); (st.getLayer(it) as? SymbolLayer)?.setFilter(mainFilter) }
        (st.getLayer("kd-poi-dpt") as? CircleLayer)?.setFilter(all(ptFilter, has("detail")))
        (st.getLayer("kd-poi-label") as? SymbolLayer)?.setFilter(all(has("label"), ptFilter))
        val pv = if (inArea && kinds.isNotEmpty()) Property.VISIBLE else Property.NONE
        val zv = if (inArea && s.schools) Property.VISIBLE else Property.NONE
        listOf("kd-poi-pt", "kd-poi-icon", "kd-poi-dpt", "kd-poi-label").forEach { st.getLayer(it)?.setProperties(visibility(pv)) }
        listOf("kd-poi-zone-fill", "kd-poi-zone-line").forEach { st.getLayer(it)?.setProperties(visibility(zv)) }
    }

    /** Tapped POI (points win over attendance areas), as a KFeature for the detail sheet. */
    fun pickPoint(m: MapLibreMap, box: android.graphics.RectF): KFeature? =
        byId(runCatching { m.queryRenderedFeatures(box, "kd-poi-icon", "kd-poi-pt", "kd-poi-dpt") }.getOrNull()?.firstOrNull()?.getStringProperty("id"))
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
