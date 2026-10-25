package com.victor.kodiakmaps.ui

import android.graphics.*
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.victor.kodiakmaps.data.PoiPrefs
import com.victor.kodiakmaps.map.MbtilesInfo
import com.victor.kodiakmaps.map.OfflineMaps
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class CamState(val bearing: Double = 0.0, val zoom: Double = 11.0, val lat: Double = 61.1, val metersPerDp: Double = 0.0)

class MapHandle {
    var map: MapLibreMap? = null
    var style: Style? = null
    var didInitialFit = false
    var base = "usgs"
    var didPhoneFit = false
    /** Once per launch: centred on a fresh own fix / on a recent last-known position; user panned since launch. */
    val autoCenter = com.victor.kodiakmaps.core.AutoCenter()
    /** Bumped when the auto-centre re-arms (return from background), to re-run the centring effect. */
    val armTick = kotlinx.coroutines.flow.MutableStateFlow(0)
    var centredOnce = false
    var lastGestureLog = 0L
    /** Last camera, restored when the map view is recreated (e.g. coming back to the Map tab). */
    var savedCamera: org.maplibre.android.camera.CameraPosition? = null
    fun rearm(now: Long) { if (autoCenter.onForeground(now)) { centredOnce = false; armTick.value++; com.victor.kodiakmaps.service.AppLog.log("Map: back after > 5 min, auto-centre re-armed") } }
    var density = 2.6f
    val camera = kotlinx.coroutines.flow.MutableStateFlow(CamState())
    /** Set when the user pans the map by hand (cancels follow-me). */
    var onUserPan: (() -> Unit)? = null
    /** Screen area (px) covered by our overlays: left, top, right, bottom. */
    var insets = intArrayOf(0, 0, 0, 0)
    fun visibleBounds(): LatLngBounds? = map?.projection?.visibleRegion?.latLngBounds
    fun zoom(): Double = map?.cameraPosition?.zoom ?: 10.0
    /** A flyTo requested while the map view isn't composed (other tab): applied once the map is ready. */
    var pendingFly: Triple<Double, Double, Double?>? = null
    fun flyTo(lat: Double, lon: Double, zoom: Double? = null, ms: Int = 700) {
        val m = map ?: run { pendingFly = Triple(lat, lon, zoom); autoCenter.onUserGesture(); return }
        m.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(lat, lon)).zoom(zoom ?: m.cameraPosition.zoom.coerceAtLeast(13.0)).padding(pad()).build()), ms)
    }
    fun follow(lat: Double, lon: Double) {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(lat, lon)).padding(pad()).build()), 500)
    }
    fun northUp() {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400)
    }
    private fun pad() = doubleArrayOf(insets[0].toDouble(), insets[1].toDouble(), insets[2].toDouble(), insets[3].toDouble())
    fun applyPadding() {
        val m = map ?: return
        // padding is passed with each camera move instead (a moveCamera here would cancel a running fit/fly animation)
    }
    fun fit(points: List<LatLng>) {
        val m = map ?: return
        if (points.isEmpty()) return
        if (points.size == 1) { flyTo(points[0].latitude, points[0].longitude, 14.0); return }
        val b = LatLngBounds.Builder().includes(points).build()
        val pad = (60 * density).toInt()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, insets[0] + pad, insets[1] + pad, insets[2] + pad, insets[3] + pad), 700)
    }
}

@Composable
fun TrailMap(
    modifier: Modifier,
    handle: MapHandle,
    phone: PhoneFix?,
    dim: Boolean,
    base: String,
    mbtiles: List<MbtilesInfo>,
    tracks: List<List<Pair<Double, Double>>> = emptyList(),
    showParcels: Boolean = true,
    showRoads: Boolean = true,
    pins: List<com.victor.kodiakmaps.data.PinEntity> = emptyList(),
    selectedPin: String? = null,
    navLine: Pair<LatLng, LatLng>? = null,
    onPinTap: (String) -> Unit = {},
    onLongPress: (Double, Double) -> Unit = { _, _ -> },
    kodiak: com.victor.kodiakmaps.data.KodiakData.Loaded? = null,
    showEsmt: Boolean = true,
    showRow: Boolean = true,
    showTrails: Boolean = true,
    showLog: Boolean = true,
    showBike: Boolean = true,
    showFish: Boolean = true,
    tides: com.victor.kodiakmaps.core.Tides.Set? = null,
    showTides: Boolean = true,
    hot: List<Pair<String, Double>> = emptyList(),
    showHot: Boolean = true,
    onTideTap: (String) -> Unit = {},
    onKodiakTap: (com.victor.kodiakmaps.core.KFeature, Double, Double) -> Unit = { _, _, _ -> },
    onMapReady: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(ctx).also { it.onCreate(Bundle()) } }
    var styleReady by remember { mutableStateOf(false) }
    val pinTap by rememberUpdatedState(onPinTap)
    val longPress by rememberUpdatedState(onLongPress)
    val kodiakTap by rememberUpdatedState(onKodiakTap)
    val tideTap by rememberUpdatedState(onTideTap)
    val kodiakNow by rememberUpdatedState(kodiak)
    var inKodiak by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            handle.map = null; handle.style = null
        }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            handle.map = m
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false   // our own compass button
            handle.density = ctx.resources.displayMetrics.density
            fun pushCam() {
                val c = m.cameraPosition
                val lat = c.target?.latitude ?: 61.1
                handle.camera.value = CamState(c.bearing, c.zoom, lat, m.projection.getMetersPerPixelAtLatitude(lat) * handle.density)
            }
            m.addOnCameraMoveListener { pushCam() }
            fun checkKodiak() {
                val b = m.projection.visibleRegion.latLngBounds
                val k = com.victor.kodiakmaps.core.KodiakArea.overlaps(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
                if (k != inKodiak) { inKodiak = k; com.victor.kodiakmaps.service.AppLog.log("Map: Kodiak layer ${if (k) "in view" else "out of view"}") }
            }
            m.addOnCameraIdleListener { pushCam(); handle.savedCamera = m.cameraPosition; checkKodiak(); addVisibleLabels(m) }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    val t = System.currentTimeMillis()
                    if (t - handle.lastGestureLog > 30_000) { handle.lastGestureLog = t; com.victor.kodiakmaps.service.AppLog.log("Map: user gesture (pan/zoom)${if (handle.autoCenter.armed) ", auto-centre off" else ""}") }
                    handle.autoCenter.onUserGesture(); handle.onUserPan?.invoke()
                }
            }
            m.addOnMapLongClickListener { ll -> longPress(ll.latitude, ll.longitude); true }
            m.addOnMapClickListener { ll ->
                val pt = m.projection.toScreenLocation(ll)
                val box = android.graphics.RectF(pt.x - 45, pt.y - 140, pt.x + 45, pt.y + 15)
                runCatching { m.queryRenderedFeatures(box, "pin-sym") }.getOrNull()?.firstOrNull()?.getStringProperty("id")?.let { pinTap(it); return@addOnMapClickListener true }
                val kbox = android.graphics.RectF(pt.x - 28, pt.y - 28, pt.x + 28, pt.y + 28)
                runCatching { m.queryRenderedFeatures(kbox, "kd-tide-icon") }.getOrNull()?.firstOrNull()?.getStringProperty("id")?.let { tideTap(it); return@addOnMapClickListener true }
                PoiMap.pickPoint(m, kbox)?.let { kodiakTap(it, ll.latitude, ll.longitude); return@addOnMapClickListener true }
                val kf = runCatching { m.queryRenderedFeatures(kbox, "kd-fish-icon", "kd-esmt-pt", "kd-esmt-line", "kd-esmt-area", "kd-row-fill", *KD_TRAIL_TAP, *KD_LOG_TAP, *KD_BIKE_TAP, "kd-road-line") }.getOrNull()
                if (!kf.isNullOrEmpty()) {
                    // prefer easements over the ROW band, points over lines
                    val pick = kf.minBy { when (it.getStringProperty("k")) { "fish" -> -1; "bikept" -> -1; "bikeline" -> 2; "road" -> 4; "row" -> 3; "trail", "logroad", "logarea" -> 2; "logpin" -> 0; else -> if (it.getStringProperty("geom") == "pt") 0 else 1 } }
                    val pk = pick.getStringProperty("k")
                    val all = kodiakNow?.features.orEmpty()
                    val match = all.firstOrNull { kfe ->
                        if (pk == "fish") kfe.k == "fish" && kfe.props.optString("name") == pick.getStringProperty("name")
                        else if (pk == "trail" || pk?.startsWith("log") == true || pk?.startsWith("bike") == true) kfe.k == pk && kfe.props.optString("id") == pick.getStringProperty("id")
                        else if (pk == "row") kfe.k == "row" && kfe.props.optString("route_id") == pick.getStringProperty("route_id") && kfe.props.optString("mp") == pick.getStringProperty("mp")
                        else kfe.k == "esmt" && kfe.props.optString("id") == (pick.getStringProperty("id") ?: "") && kfe.props.optString("geom") == (pick.getStringProperty("geom") ?: "") &&
                            kfe.props.optString("ein") == (pick.getStringProperty("ein") ?: "")
                    }
                    if (match != null) { kodiakTap(match, ll.latitude, ll.longitude); return@addOnMapClickListener true }
                    if (pk == "road") renderedToK(pick)?.let { kodiakTap(it, ll.latitude, ll.longitude); return@addOnMapClickListener true }
                }
                // property: parcel under the tap, else the land-ownership polygon
                val pf = runCatching { m.queryRenderedFeatures(pt, "kd-parcel-fill", "kd-own-fill") }.getOrNull()
                    ?.minByOrNull { if (it.getStringProperty("k") == "parcel") 0 else 1 }
                if (pf != null) renderedToK(pf)?.let { kodiakTap(it, ll.latitude, ll.longitude); return@addOnMapClickListener true }
                PoiMap.pickZone(m, pt)?.let { kodiakTap(it, ll.latitude, ll.longitude); return@addOnMapClickListener true }
                false
            }
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.setMinZoomPreference(3.0); m.setMaxZoomPreference(18.0)
            m.cameraPosition = handle.savedCamera
                ?: org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(61.10, -149.68)).zoom(11.0).build()
            handle.base = base
            m.setStyle(Style.Builder().fromUri(OfflineMaps.styleUrl(base))) { st ->
                try { setupLayers(st) } catch (e: Exception) { android.util.Log.e("TrailLink", "map layers: $e") }
                handle.style = st
                styleReady = true
                handle.pendingFly?.let { (la, lo, z) -> handle.pendingFly = null; handle.flyTo(la, lo, z) }
                onMapReady()
            }
        }
    }

    LaunchedEffect(base) {
        val m = handle.map ?: return@LaunchedEffect
        if (handle.base == base) return@LaunchedEffect
        handle.base = base; styleReady = false; handle.style = null
        m.setStyle(Style.Builder().fromUri(OfflineMaps.styleUrl(base))) { st ->
            try { setupLayers(st) } catch (e: Exception) { android.util.Log.e("KodiakMaps", "map layers: $e") }
            handle.style = st; styleReady = true
        }
    }

    // MBTiles overlays (raster), placed below our trails.
    LaunchedEffect(styleReady, mbtiles) {
        val st = handle.style ?: return@LaunchedEffect
        st.layers.filter { it.id.startsWith("mb-") }.forEach { st.removeLayer(it) }
        st.sources.filter { it.id.startsWith("mb-") }.forEach { st.removeSource(it) }
        mbtiles.forEachIndexed { i, mb ->
            val id = "mb-$i"
            st.addSource(RasterSource(id, "mbtiles://" + mb.file.absolutePath, 256))
            st.addLayerBelow(RasterLayer(id, id), "kd-row-fill")
        }
    }

    // Kodiak offline layer: 17(b) easements + State ROW, only drawn while the view overlaps the archipelago.
    LaunchedEffect(styleReady, kodiak) {
        val st = handle.style ?: return@LaunchedEffect
        val k = kodiak ?: return@LaunchedEffect
        k.features.mapNotNull { it.props.optString("label").ifBlank { null } }.distinct().forEach { lbl ->
            val id = "ein-$lbl"; if (st.getImage(id) == null) st.addImage(id, labelBitmap(lbl, 0xFFFFE0B2.toInt(), false))
        }
        if (st.getImage("site-icon") == null) st.addImage("site-icon", labelBitmap("P ⛺", 0xFF2979FF.toInt(), false))
        if (st.getImage("misc-icon") == null) st.addImage("misc-icon", labelBitmap("17b", 0xFF90A4AE.toInt(), false))
        st.getSourceAs<GeoJsonSource>("kd-esmt")?.setGeoJson(k.esmtJson)
        st.getSourceAs<GeoJsonSource>("kd-row")?.setGeoJson(k.rowJson)
        // Trails + (future) logging roads: name pills as bitmaps (no glyph downloads offline).
        k.features.filter { it.k == "trail" || it.k.startsWith("log") }.mapNotNull { it.props.optString("label").ifBlank { null }?.let { l -> it.k to l } }.distinct().forEach { (kk, lbl) ->
            val trail = kk == "trail"; val id = (if (trail) "tr-" else "lg-") + lbl
            if (st.getImage(id) == null) st.addImage(id, labelBitmap(lbl, if (trail) 0xFF84FFFF.toInt() else 0xFFFFF59D.toInt(), false))
        }
        k.features.filter { it.k.startsWith("bike") }.mapNotNull { it.props.optString("label").ifBlank { null } }.distinct().forEach { lbl ->
            if (st.getImage("bk-$lbl") == null) st.addImage("bk-$lbl", labelBitmap(lbl, 0xFFEA80FC.toInt(), false)) }
        st.getSourceAs<GeoJsonSource>("kd-bike")?.setGeoJson(k.bikeJson)
        st.getSourceAs<GeoJsonSource>("kd-trails")?.setGeoJson(k.trailsJson)
        st.getSourceAs<GeoJsonSource>("kd-log")?.setGeoJson(k.logJson ?: com.victor.kodiakmaps.data.KodiakData.EMPTY)
        st.getSourceAs<GeoJsonSource>("kd-parcels")?.setGeoJson(k.parcelsJson)
        st.getSourceAs<GeoJsonSource>("kd-own")?.setGeoJson(k.ownJson)
        st.getSourceAs<GeoJsonSource>("kd-roads")?.setGeoJson(k.roadsJson)
        com.victor.kodiakmaps.core.Fishing.TYPE_COLOR.forEach { (t, c) -> if (st.getImage("fish-$t") == null) st.addImage("fish-$t", fishBitmap(android.graphics.Color.parseColor(c), if (t == "ocean") 0xFFFFFFFF.toInt() else 0xFF101214.toInt())) }
        k.features.filter { it.k == "fish" }.forEach { f -> val n = f.props.optString("name"); if (st.getImage("fl-$n") == null)
            st.addImage("fl-$n", labelBitmap(n.substringBefore(":").take(32), android.graphics.Color.parseColor(com.victor.kodiakmaps.core.Fishing.textColor(f.props.optString("type"))), false)) }
        st.getSourceAs<GeoJsonSource>("kd-fish")?.setGeoJson(k.fishJson)
    }
    // Places (food, travel, schools + attendance areas, military history, community): bundled, all OFF by default.
    val ctxPoi = LocalContext.current
    val poi by PoiPrefs.state.collectAsState()
    LaunchedEffect(styleReady) {
        val st = handle.style ?: return@LaunchedEffect
        PoiPrefs.load(ctxPoi)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PoiMap.load(ctxPoi) }
        PoiMap.setData(st, { t, c -> labelBitmap(t, c, false) })
        PoiMap.apply(st, inKodiak, PoiPrefs.state.value)
    }
    LaunchedEffect(styleReady, inKodiak, poi) { handle.style?.let { PoiMap.apply(it, inKodiak, poi) } }
    LaunchedEffect(styleReady, tides) {
        val st = handle.style ?: return@LaunchedEffect; val t = tides ?: return@LaunchedEffect
        if (st.getImage("tide-icon") == null) st.addImage("tide-icon", labelBitmap("≈ Tide", 0xFF80DEEA.toInt(), false))
        st.getSourceAs<GeoJsonSource>("kd-tide")?.setGeoJson(FeatureCollection.fromFeatures(t.stations.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).also { it.addStringProperty("id", s.id) } }))
    }
    LaunchedEffect(styleReady, kodiak, hot) {
        val st = handle.style ?: return@LaunchedEffect; val k = kodiak ?: return@LaunchedEffect
        if (st.getImage("hot-flame") == null) st.addImage("hot-flame", labelBitmap("🔥", 0xFFFF7043.toInt(), true))
        st.getSourceAs<GeoJsonSource>("kd-hot")?.setGeoJson(FeatureCollection.fromFeatures(hot.mapNotNull { (name, sc) ->
            k.features.firstOrNull { it.k == "fish" && it.props.optString("name") == name }?.point?.let { p ->
                Feature.fromGeometry(Point.fromLngLat(p[1], p[0])).also { it.addNumberProperty("score", sc) } } }))
    }
    LaunchedEffect(styleReady, inKodiak, showHot) {
        val v = if (inKodiak && showHot) Property.VISIBLE else Property.NONE
        listOf("kd-hot-glow", "kd-hot-icon").forEach { handle.style?.getLayer(it)?.setProperties(visibility(v)) }
    }
    LaunchedEffect(styleReady, inKodiak, showTides) {
        handle.style?.getLayer("kd-tide-icon")?.setProperties(visibility(if (inKodiak && showTides) Property.VISIBLE else Property.NONE))
    }
    LaunchedEffect(styleReady, inKodiak, showFish) {
        val st = handle.style ?: return@LaunchedEffect
        val v = if (inKodiak && showFish) Property.VISIBLE else Property.NONE
        listOf("kd-fish-icon", "kd-fish-label").forEach { st.getLayer(it)?.setProperties(visibility(v)) }
    }
    LaunchedEffect(styleReady, inKodiak, showParcels, showRoads) {
        val st = handle.style ?: return@LaunchedEffect
        val p = if (inKodiak && showParcels) Property.VISIBLE else Property.NONE
        val r = if (inKodiak && showRoads) Property.VISIBLE else Property.NONE
        KD_PARCEL_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(p)) }
        KD_ROAD_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(r)) }
        handle.map?.let { addVisibleLabels(it) }
    }
    LaunchedEffect(styleReady, tracks) {
        val st = handle.style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("tracks")?.setGeoJson(FeatureCollection.fromFeatures(tracks.filter { it.size >= 2 }.map { t ->
            Feature.fromGeometry(LineString.fromLngLats(t.map { Point.fromLngLat(it.second, it.first) })) }))
    }
    LaunchedEffect(styleReady, inKodiak, showTrails, showLog, kodiak?.logJson != null) {
        val st = handle.style ?: return@LaunchedEffect
        val t = if (inKodiak && showTrails) Property.VISIBLE else Property.NONE
        val l = if (inKodiak && showLog && kodiak?.logJson != null) Property.VISIBLE else Property.NONE
        KD_TRAIL_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(t)) }
        KD_LOG_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(l)) }
    }
    LaunchedEffect(styleReady, inKodiak, showBike) {
        val v = if (inKodiak && showBike) Property.VISIBLE else Property.NONE
        KD_BIKE_LAYERS.forEach { handle.style?.getLayer(it)?.setProperties(visibility(v)) }
    }
    LaunchedEffect(styleReady, inKodiak, showEsmt, showRow) {
        val st = handle.style ?: return@LaunchedEffect
        val e = if (inKodiak && showEsmt) Property.VISIBLE else Property.NONE
        val r = if (inKodiak && showRow) Property.VISIBLE else Property.NONE
        KD_ESMT_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(e)) }
        KD_ROW_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(r)) }
    }

    LaunchedEffect(styleReady, dim) {
        handle.style?.getLayerAs<RasterLayer>("usgs-topo")?.setProperties(rasterBrightnessMax(if (dim) 0.72f else 1f), rasterContrast(if (dim) 0.05f else 0f))
    }

    LaunchedEffect(styleReady, pins, selectedPin) {
        val st = handle.style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("pins")?.setGeoJson(FeatureCollection.fromFeatures(pins.map { p ->
            val emoji = com.victor.kodiakmaps.core.PinKind.of(p.kind).emoji
            val sel = p.id == selectedPin
            val icon = "pin-${p.id}-${p.color}-${p.name.hashCode()}-$emoji-$sel"
            if (st.getImage(icon) == null) st.addImage(icon, pinBitmap(emoji, p.name, p.color, sel))
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("icon", icon); addStringProperty("id", p.id) }
        }))
    }
    LaunchedEffect(styleReady, navLine) {
        val st = handle.style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("nav")?.setGeoJson(navLine?.let { (a, b) ->
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(a.longitude, a.latitude), Point.fromLngLat(b.longitude, b.latitude)))))
        } ?: FeatureCollection.fromFeatures(emptyList()))
    }
    LaunchedEffect(styleReady, phone) {
        val st = handle.style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("phone")?.setGeoJson(
            if (phone == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(phone.lon, phone.lat))))
        st.getSourceAs<GeoJsonSource>("phone-acc")?.setGeoJson(
            if (phone?.accuracy == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(accuracyCircle(phone.lat, phone.lon, phone.accuracy.toDouble().coerceAtLeast(3.0)))))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Polygon approximating a circle of [radiusM] metres (accuracy ring). */
private fun accuracyCircle(lat: Double, lon: Double, radiusM: Double): org.maplibre.geojson.Polygon {
    val pts = (0..48).map { i ->
        val a = Math.toRadians(i * 7.5)
        val dLat = radiusM * Math.cos(a) / 111_320.0
        val dLon = radiusM * Math.sin(a) / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        Point.fromLngLat(lon + dLon, lat + dLat)
    }
    return org.maplibre.geojson.Polygon.fromLngLats(listOf(pts))
}

private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

private fun setupLayers(st: Style) {
    st.addSource(GeoJsonSource("tracks"))
    st.addSource(GeoJsonSource("phone"))
    st.addSource(GeoJsonSource("phone-acc"))
    st.addSource(GeoJsonSource("kd-parcels"))
    st.addSource(GeoJsonSource("kd-own"))
    st.addSource(GeoJsonSource("kd-roads"))
    st.addSource(GeoJsonSource("pins"))
    st.addSource(GeoJsonSource("nav"))
    st.addSource(GeoJsonSource("kd-esmt"))
    st.addSource(GeoJsonSource("kd-row"))
    st.addSource(GeoJsonSource("kd-trails"))
    st.addSource(GeoJsonSource("kd-log"))
    st.addSource(GeoJsonSource("kd-bike"))
    st.addSource(GeoJsonSource("kd-fish"))
    st.addSource(GeoJsonSource("kd-tide"))
    st.addSource(GeoJsonSource("kd-hot"))
    setupKodiakLayers(st)
    // Fishing spots (ADF&G): fish icon coloured by water type, name pills from z12. Above all Kodiak lines.
    st.addLayer(CircleLayer("kd-hot-glow", "kd-hot").withProperties(visibility(Property.NONE), circleColor("#FF5722"),
        circleRadius(interpolate(linear(), zoom(), stop(8, 12f), stop(13, 22f))), circleOpacity(0.35f), circleBlur(0.8f)))
    st.addLayer(SymbolLayer("kd-fish-icon", "kd-fish").withProperties(visibility(Property.NONE), iconImage(concat(literal("fish-"), get("type"))),
        iconAllowOverlap(true), iconIgnorePlacement(true), iconSize(interpolate(linear(), zoom(), stop(8, 0.5f), stop(12, 0.8f), stop(15, 1f)))))
    st.addLayer(SymbolLayer("kd-hot-icon", "kd-hot").withProperties(visibility(Property.NONE), iconImage("hot-flame"), iconSize(0.6f),
        iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconOffset(arrayOf(0f, -26f)), iconAllowOverlap(true), iconIgnorePlacement(true)))
    st.addLayer(SymbolLayer("kd-tide-icon", "kd-tide").withProperties(visibility(Property.NONE), iconImage("tide-icon"), iconSize(0.7f),
        iconAllowOverlap(true), iconIgnorePlacement(true)).also { it.minZoom = 9f })
    st.addLayer(SymbolLayer("kd-fish-label", "kd-fish").withProperties(visibility(Property.NONE), iconImage(concat(literal("fl-"), get("name"))),
        iconSize(0.7f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 26f)), iconAllowOverlap(false), iconOptional(true)).also { it.minZoom = 12f })
    PoiMap.setup(st)
    st.addLayer(LineLayer("tracks-casing", "tracks").withProperties(lineColor("#000000"), lineOpacity(0.5f), lineWidth(7f),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("tracks-line", "tracks").withProperties(lineColor("#FF4081"), lineWidth(4f),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(FillLayer("phone-acc-fill", "phone-acc").withProperties(fillColor("#2979FF"), fillOpacity(0.18f)))
    st.addLayer(LineLayer("phone-acc-line", "phone-acc").withProperties(lineColor("#2979FF"), lineWidth(1.5f), lineOpacity(0.8f)))
    st.addLayer(CircleLayer("phone-halo", "phone").withProperties(circleRadius(18f), circleColor("#2979FF"), circleOpacity(0.25f)))
    st.addLayer(CircleLayer("phone-dot", "phone").withProperties(circleRadius(10f), circleColor("#2979FF"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
    st.addImage("me-label", labelBitmap("Me", PHONE_COLOR, false))
    st.addLayer(SymbolLayer("phone-label", "phone").withProperties(iconImage("me-label"), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 14f))))
    st.addLayer(LineLayer("nav-line", "nav").withProperties(lineColor("#FFD740"), lineWidth(4f), lineOpacity(0.9f), lineDasharray(arrayOf(2f, 1.5f))))
    st.addLayer(SymbolLayer("pin-sym", "pins").withProperties(iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconAnchor(Property.ICON_ANCHOR_BOTTOM)))
}

/** Pin marker: coloured teardrop with the emoji, name pill below the point (bitmap: works offline, no glyphs). */
private fun pinBitmap(emoji: String, name: String, color: Int, sel: Boolean): Bitmap {
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; typeface = Typeface.DEFAULT_BOLD; this.color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    val label = name.take(18)
    val lw = tp.measureText(label) + 24
    val r = if (sel) 34f else 28f
    val labelH = 40f
    val w = maxOf(lw, r * 2 + 8).toInt()
    val headTop = labelH + 4f
    val tipY = headTop + r * 2 + r * 0.9f
    val b = Bitmap.createBitmap(w, (tipY + 2).toInt(), Bitmap.Config.ARGB_8888); val c = Canvas(b)
    val cx = w / 2f; val cy = headTop + r
    c.drawRoundRect(RectF((w - lw) / 2, 0f, (w + lw) / 2, labelH - 2), 19f, 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xE6101214.toInt() })
    c.drawText(label, cx, (labelH - 2) / 2 - (tp.descent() + tp.ascent()) / 2, tp)
    val path = android.graphics.Path().apply {
        addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        moveTo(cx - r * 0.62f, cy + r * 0.75f); lineTo(cx, tipY); lineTo(cx + r * 0.62f, cy + r * 0.75f); close()
    }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = if (sel) 6f else 4f; this.color = 0xFF101214.toInt() })
    val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = r * 1.05f; textAlign = Paint.Align.CENTER }
    c.drawText(emoji, cx, cy - (ep.descent() + ep.ascent()) / 2, ep)
    return b
}

/** Fishing-spot marker: coloured disc with a white fish (drawn, no emoji font needed). */
fun fishBitmap(color: Int, fish: Int = 0xFF101214.toInt()): Bitmap {
    val sz = 64; val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888); val c = Canvas(b)
    c.drawCircle(32f, 32f, 29f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    c.drawCircle(32f, 32f, 29f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; this.color = if (fish == 0xFFFFFFFF.toInt()) 0xFFFFFFFF.toInt() else 0xFF101214.toInt() })
    val w = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = fish }
    c.drawOval(RectF(12f, 22f, 44f, 42f), w)
    c.drawPath(android.graphics.Path().apply { moveTo(42f, 32f); lineTo(54f, 21f); lineTo(54f, 43f); close() }, w)
    c.drawCircle(20f, 30f, 2.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    return b
}

/** Name pill drawn as a bitmap so the map needs no font/glyph downloads offline. */
fun labelBitmap(text: String, color: Int, focus: Boolean): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = if (focus) 44f else 36f; typeface = Typeface.DEFAULT_BOLD }
    val w = (p.measureText(text) + 36).toInt(); val h = (p.textSize * 1.45f).toInt()
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xE6101214.toInt() }
    c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, bg)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = if (focus) 6f else 4f; this.color = color }
    c.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), h / 2f, h / 2f, stroke)
    p.color = color
    c.drawText(text, 18f, h / 2f - (p.descent() + p.ascent()) / 2, p)
    return b
}

private val KD_ROW_LAYERS = listOf("kd-row-fill", "kd-row-line", "kd-row-cl")
private val KD_ESMT_LAYERS = listOf("kd-esmt-area", "kd-esmt-area-line", "kd-esmt-line-casing", "kd-esmt-line", "kd-esmt-pt", "kd-esmt-label", "kd-esmt-label-pt")

/** Layers for the Kodiak offline data; inserted under the trails so partners and pins stay on top. Start hidden. */
private fun setupKodiakLayers(st: Style) {
    setupPropertyLayers(st)
    val kindColor = toColor(match(get("kind"), literal("#BDBDBD"),
        stop("trail25", "#FF9100"), stop("trail50", "#FF3D00"), stop("road", "#D500F9"), stop("site", "#2979FF"),
        stop("utility", "#9E9E9E"), stop("air", "#78909C"), stop("airstrip", "#00B8D4")))
    val hidden = visibility(Property.NONE)
    st.addLayer(FillLayer("kd-row-fill", "kd-row").withFilter(eq(get("k"), literal("row"))).withProperties(hidden,
        fillColor("#FFC107"), fillOpacity(match(get("basis"), literal(0.16f), stop("measured", 0.28f)))))
    st.addLayer(LineLayer("kd-row-line", "kd-row").withFilter(eq(get("k"), literal("row"))).withProperties(hidden,
        lineColor("#FFA000"), lineWidth(1.2f), lineOpacity(0.8f), lineDasharray(arrayOf(4f, 2f))))
    st.addLayer(LineLayer("kd-row-cl", "kd-row").withFilter(eq(get("k"), literal("rowcl"))).withProperties(hidden,
        lineColor("#FFE082"), lineWidth(1f), lineOpacity(0.6f)).also { it.minZoom = 13f })
    setupTrailLayers(st)
    st.addLayer(FillLayer("kd-esmt-area", "kd-esmt").withFilter(eq(get("geom"), literal("area"))).withProperties(hidden,
        fillColor(kindColor), fillOpacity(0.25f)))
    st.addLayer(LineLayer("kd-esmt-area-line", "kd-esmt").withFilter(eq(get("geom"), literal("area"))).withProperties(hidden,
        lineColor(kindColor), lineWidth(1.5f), lineDasharray(arrayOf(2f, 1.5f))))
    st.addLayer(LineLayer("kd-esmt-line-casing", "kd-esmt").withFilter(not(has("geom"))).withProperties(hidden,
        lineColor("#000000"), lineOpacity(0.35f), lineWidth(interpolate(linear(), zoom(), stop(8, 2.5f), stop(14, 5f), stop(17, 8f)))))
    st.addLayer(LineLayer("kd-esmt-line", "kd-esmt").withFilter(not(has("geom"))).withProperties(hidden,
        lineColor(kindColor), lineWidth(interpolate(linear(), zoom(), stop(8, 1.5f), stop(14, 3f), stop(17, 5f))),
        lineDasharray(arrayOf(2.2f, 1.4f))))
    st.addLayer(SymbolLayer("kd-esmt-pt", "kd-esmt").withFilter(eq(get("geom"), literal("pt"))).withProperties(hidden,
        iconImage(match(get("kind"), literal("misc-icon"), stop("site", "site-icon"))), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconSize(interpolate(linear(), zoom(), stop(8, 0.55f), stop(13, 0.8f), stop(16, 1f)))))
    st.addLayer(SymbolLayer("kd-esmt-label", "kd-esmt").withFilter(all(has("label"), not(has("geom")))).withProperties(hidden,
        iconImage(concat(literal("ein-"), get("label"))), iconSize(0.75f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 14f)),
        symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
        iconAllowOverlap(false)).also { it.minZoom = 12.5f })
    st.addLayer(SymbolLayer("kd-esmt-label-pt", "kd-esmt").withFilter(all(has("label"), eq(get("geom"), literal("pt")))).withProperties(hidden,
        iconImage(concat(literal("ein-"), get("label"))), iconSize(0.75f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 18f)),
        iconAllowOverlap(false)).also { it.minZoom = 12.5f })
}

private val KD_TRAIL_TAP = arrayOf("kd-trail-solid", "kd-trail-single", "kd-trail-route")
private val KD_TRAIL_LAYERS = listOf("kd-trail-casing", *KD_TRAIL_TAP, "kd-trail-label")
private val KD_BIKE_TAP = arrayOf("kd-bike-line", "kd-bike-pt")
private val KD_BIKE_LAYERS = listOf("kd-bike-casing", "kd-bike-line", "kd-bike-pt", "kd-bike-line-label", "kd-bike-pt-label")
private val KD_LOG_TAP = arrayOf("kd-log-line", "kd-log-area", "kd-log-pin")
private val KD_LOG_LAYERS = listOf("kd-log-casing", "kd-log-line", "kd-log-area-casing", "kd-log-area", "kd-log-pin", "kd-log-road-label", "kd-log-label")

/** Kodiak Trails (KIB): cyan; solid = double track / shared-use path, dashed = single track, dotted = route. Above the ROW band, below easements.
 *  Chiniak logging roads (Victor's Google Earth KML): drawn in the KML's own colours, borough property as a dashed white outline, pins. */
private fun setupTrailLayers(st: Style) {
    val hidden = visibility(Property.NONE)
    val w = interpolate(linear(), zoom(), stop(9, 1.2f), stop(13, 2.4f), stop(16, 4f), stop(18, 6f))
    val trailColor = com.victor.kodiakmaps.core.TrailsInfo.COLOR
    st.addLayer(LineLayer("kd-trail-casing", "kd-trails").withFilter(neq(get("cls"), literal("route"))).withProperties(hidden,
        lineColor("#00363D"), lineOpacity(0.55f), lineWidth(interpolate(linear(), zoom(), stop(9, 2.6f), stop(13, 4.6f), stop(16, 7f), stop(18, 10f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-trail-solid", "kd-trails").withFilter(any(eq(get("cls"), literal("double")), eq(get("cls"), literal("shared")))).withProperties(hidden,
        lineColor(trailColor), lineWidth(w), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-trail-single", "kd-trails").withFilter(eq(get("cls"), literal("single"))).withProperties(hidden,
        lineColor(trailColor), lineWidth(w), lineDasharray(arrayOf(3f, 1.6f)), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-trail-route", "kd-trails").withFilter(eq(get("cls"), literal("route"))).withProperties(hidden,
        lineColor(trailColor), lineWidth(interpolate(linear(), zoom(), stop(9, 2f), stop(13, 3.4f), stop(16, 5f), stop(18, 7f))),
        lineDasharray(arrayOf(0.05f, 2f)), lineCap(Property.LINE_CAP_ROUND)))
    st.addLayer(SymbolLayer("kd-trail-label", "kd-trails").withFilter(has("label")).withProperties(hidden,
        iconImage(concat(literal("tr-"), get("label"))), iconSize(0.72f), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(450f),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT), iconAllowOverlap(false), iconPadding(4f)).also { it.minZoom = 13.5f })

    val logColor = toColor(coalesce(get("color"), literal("#FFEB3B")))
    st.addLayer(LineLayer("kd-log-casing", "kd-log").withFilter(eq(get("k"), literal("logroad"))).withProperties(hidden,
        lineColor("#000000"), lineOpacity(0.45f), lineWidth(interpolate(linear(), zoom(), stop(9, 2.6f), stop(14, 5f), stop(17, 8f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-log-line", "kd-log").withFilter(eq(get("k"), literal("logroad"))).withProperties(hidden,
        lineColor(logColor), lineWidth(interpolate(linear(), zoom(), stop(9, 1.4f), stop(14, 3f), stop(17, 5f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-log-area-casing", "kd-log").withFilter(eq(get("k"), literal("logarea"))).withProperties(hidden,
        lineColor("#000000"), lineOpacity(0.5f), lineWidth(4.5f)))
    st.addLayer(LineLayer("kd-log-area", "kd-log").withFilter(eq(get("k"), literal("logarea"))).withProperties(hidden,
        lineColor(toColor(coalesce(get("color"), literal("#FFFFFF")))), lineWidth(2.2f), lineOpacity(0.9f), lineDasharray(arrayOf(3f, 1.5f))))
    st.addLayer(CircleLayer("kd-log-pin", "kd-log").withFilter(eq(get("k"), literal("logpin"))).withProperties(hidden,
        circleColor(toColor(coalesce(get("color"), literal("#2979FF")))), circleRadius(interpolate(linear(), zoom(), stop(9, 4f), stop(15, 7f))),
        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
    st.addLayer(SymbolLayer("kd-log-road-label", "kd-log").withFilter(all(has("label"), eq(get("k"), literal("logroad")))).withProperties(hidden,
        iconImage(concat(literal("lg-"), get("label"))), iconSize(0.66f), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT), iconAllowOverlap(false)).also { it.minZoom = 14f })
    st.addLayer(SymbolLayer("kd-log-label", "kd-log").withFilter(all(has("label"), eq(get("k"), literal("logpin")))).withProperties(hidden,
        iconImage(concat(literal("lg-"), get("label"))), iconSize(0.72f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 14f)),
        iconAllowOverlap(false)).also { it.minZoom = 12.5f })
    // Town bike trails (Victor's Google My Maps): own colours with a white casing so black/blue lines read on topo.
    st.addLayer(LineLayer("kd-bike-casing", "kd-bike").withFilter(eq(get("k"), literal("bikeline"))).withProperties(hidden,
        lineColor("#FFFFFF"), lineOpacity(0.8f), lineWidth(interpolate(linear(), zoom(), stop(11, 2.6f), stop(14, 4.6f), stop(17, 8f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-bike-line", "kd-bike").withFilter(eq(get("k"), literal("bikeline"))).withProperties(hidden,
        lineColor(toColor(coalesce(get("color"), literal("#E040FB")))), lineWidth(interpolate(linear(), zoom(), stop(11, 1.4f), stop(14, 2.6f), stop(17, 5f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(CircleLayer("kd-bike-pt", "kd-bike").withFilter(eq(get("k"), literal("bikept"))).withProperties(hidden,
        circleColor(match(get("kind"), literal("#9E9E9E"), stop("head", literal("#43A047")), stop("work", literal("#FF6D00")))),
        circleRadius(interpolate(linear(), zoom(), stop(11, 3.5f), stop(15, 6.5f))), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.8f)))
    st.addLayer(SymbolLayer("kd-bike-line-label", "kd-bike").withFilter(all(has("label"), eq(get("k"), literal("bikeline")))).withProperties(hidden,
        iconImage(concat(literal("bk-"), get("label"))), iconSize(0.66f), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT), iconAllowOverlap(false)).also { it.minZoom = 14f })
    st.addLayer(SymbolLayer("kd-bike-pt-label", "kd-bike").withFilter(all(has("label"), eq(get("k"), literal("bikept")))).withProperties(hidden,
        iconImage(concat(literal("bk-"), get("label"))), iconSize(0.66f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 12f)),
        iconAllowOverlap(false)).also { it.minZoom = 15f })
}

private val KD_PARCEL_LAYERS = listOf("kd-own-fill", "kd-own-line", "kd-parcel-fill", "kd-parcel-line", "kd-parcel-label")
private val KD_ROAD_LAYERS = listOf("kd-road-line", "kd-road-label")

/** Property lines (KIB parcels: pink, from z13; owner pills from z16) and land ownership (KIB, dashed, z8+), road-name lines. Bottom of the Kodiak stack. */
private fun setupPropertyLayers(st: Style) {
    val hidden = visibility(Property.NONE)
    st.addLayer(FillLayer("kd-own-fill", "kd-own").withProperties(hidden, fillColor("#F48FB1"), fillOpacity(0.01f)).also { it.minZoom = 8f })
    st.addLayer(LineLayer("kd-own-line", "kd-own").withProperties(hidden, lineColor("#F48FB1"), lineOpacity(0.7f),
        lineWidth(interpolate(linear(), zoom(), stop(8, 0.8f), stop(14, 1.8f))), lineDasharray(arrayOf(4f, 2f))).also { it.minZoom = 8f; it.maxZoom = 14f })
    st.addLayer(FillLayer("kd-parcel-fill", "kd-parcels").withProperties(hidden, fillColor("#F48FB1"), fillOpacity(0.01f)).also { it.minZoom = 13f })
    st.addLayer(LineLayer("kd-parcel-line", "kd-parcels").withProperties(hidden, lineColor("#EC407A"), lineOpacity(0.85f),
        lineWidth(interpolate(linear(), zoom(), stop(13, 0.8f), stop(16, 1.6f), stop(18, 2.4f)))).also { it.minZoom = 13f })
    st.addLayer(SymbolLayer("kd-parcel-label", "kd-parcels").withFilter(has("label")).withProperties(hidden,
        iconImage(concat(literal("pc-"), get("label"))), iconSize(0.62f), iconAllowOverlap(false), iconOptional(true)).also { it.minZoom = 16f })
    st.addLayer(LineLayer("kd-road-line", "kd-roads").withProperties(hidden, lineColor("#FFFFFF"), lineOpacity(0.01f), lineWidth(10f)).also { it.minZoom = 10f })
    st.addLayer(SymbolLayer("kd-road-label", "kd-roads").withFilter(has("label")).withProperties(hidden,
        iconImage(concat(literal("rd-"), get("label"))), iconSize(0.66f), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(500f),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconAllowOverlap(false), iconPadding(2f)).also { it.minZoom = 13f })
}

/** Label pills are bitmaps (no glyph downloads offline); add only those for features in view, once. */
private fun addVisibleLabels(m: MapLibreMap) {
    val st = m.style ?: return
    val z = m.cameraPosition.zoom
    if (z < 13) return
    val ids = if (z >= 16) arrayOf("kd-road-line", "kd-parcel-fill") else arrayOf("kd-road-line")
    val w = m.width.toFloat(); val h = m.height.toFloat()
    val fs = runCatching { m.queryRenderedFeatures(android.graphics.RectF(0f, 0f, w, h), *ids) }.getOrNull() ?: return
    var added = 0
    for (f in fs) {
        val lbl = f.getStringProperty("label") ?: continue
        val road = f.getStringProperty("k") == "road"
        val id = (if (road) "rd-" else "pc-") + lbl
        if (st.getImage(id) == null) { st.addImage(id, labelBitmap(lbl, if (road) 0xFFFFFFFF.toInt() else 0xFFF8BBD0.toInt(), false)); added++ }
        if (added > 400) break
    }
}

/** A rendered (not pre-parsed) feature -> KFeature for the details sheet. */
private fun renderedToK(f: Feature): com.victor.kodiakmaps.core.KFeature? = runCatching {
    com.victor.kodiakmaps.core.KGeo.parse("{\"type\":\"FeatureCollection\",\"features\":[" + f.toJson() + "]}").firstOrNull()
}.getOrNull()
