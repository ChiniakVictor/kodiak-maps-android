package com.victor.kodiakmaps.ui

import android.app.Application
import android.content.Context
import android.hardware.*
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.victor.kodiakmaps.core.Geo
import com.victor.kodiakmaps.core.Gpx
import com.victor.kodiakmaps.data.*
import com.victor.kodiakmaps.service.AppLog
import com.victor.kodiakmaps.service.NavState
import com.victor.kodiakmaps.service.PhoneLocator
import com.victor.kodiakmaps.service.TrackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhoneFix(val lat: Double, val lon: Double, val accuracy: Float?, val time: Long, val alt: Double? = null)
const val PHONE_COLOR = 0xFF2979FF.toInt()
enum class LocStatus { OK, NO_PERMISSION, SYSTEM_OFF, WAITING }
data class TrackUi(val t: TrackEntity, val pts: List<TrackPointEntity>, val lengthM: Double)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app), SensorEventListener {
    val settings = Prefs.settings
    private val _phone = MutableStateFlow<PhoneFix?>(null)
    val phone: StateFlow<PhoneFix?> get() = _phone
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> get() = _heading
    val now = flow { while (true) { emit(System.currentTimeMillis()); delay(1000) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    // ---------------------------------------------------------------- pins
    private val pinDao = MapDb.get(app).pins()
    val pins: StateFlow<List<PinEntity>> = pinDao.visible(0).combine(now) { l, t -> l.filter { it.expires == 0L || it.expires > t } }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedPin = MutableStateFlow<String?>(null)
    val nav = NavState.pin
    fun savePin(p: PinEntity) = viewModelScope.launch(Dispatchers.IO) { pinDao.put(p); AppLog.log("Pin '${p.name}' saved") }
    fun deletePin(p: PinEntity) = viewModelScope.launch(Dispatchers.IO) {
        pinDao.delete(p.id)
        if (NavState.pin.value == p.id) NavState.stop()
        if (selectedPin.value == p.id) selectedPin.value = null
    }
    fun startNav(id: String) = NavState.start(id)
    fun stopNav() = NavState.stop()

    // ---------------------------------------------------------------- tracks
    private val trackDao = MapDb.get(app).tracks()
    val recording = TrackService.recording
    val recStats = TrackService.stats
    val tracks: StateFlow<List<TrackUi>> = trackDao.tracks().flatMapLatest { ts ->
        val ids = ts.map { it.id }
        if (ids.isEmpty()) flowOf(emptyList())
        else trackDao.pointsFor(ids).map { pts ->
            val by = pts.groupBy { it.trackId }
            ts.map { t -> val p = by[t.id].orEmpty(); TrackUi(t, p, Gpx.lengthM(p.map { Gpx.Pt(it.lat, it.lon, it.ele, it.time) })) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hiddenTracks = MutableStateFlow<Set<Long>>(emptySet())
    fun deleteTrack(id: Long) = viewModelScope.launch(Dispatchers.IO) { trackDao.deletePoints(id); trackDao.deleteTrack(id) }
    fun renameTrack(t: TrackEntity, name: String) = viewModelScope.launch(Dispatchers.IO) { trackDao.update(t.copy(name = name)) }

    suspend fun exportGpx(ctx: Context, uri: Uri, trackIds: List<Long>, withPins: Boolean): String = withContext(Dispatchers.IO) {
        val trks = trackIds.mapNotNull { id -> trackDao.track(id)?.let { t ->
            Gpx.Trk(t.name, Gpx.segments(trackDao.points(id).map { Gpx.Pt(it.lat, it.lon, it.ele, it.time) }))
        } }
        val w = if (withPins) pins.value.map { Gpx.Wpt(it.lat, it.lon, it.name, it.note, it.created) } else emptyList()
        val text = Gpx.write(trks, w)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: return@withContext "Couldn't write the file"
        "Exported ${trks.size} track(s), ${trks.sumOf { t -> t.segments.sumOf { it.size } }} points" + if (withPins) ", ${w.size} pins" else ""
    }

    // ---------------------------------------------------------------- phone GPS + compass (only while the app is visible)
    private var locator: PhoneLocator? = null
    private val _locStatus = MutableStateFlow(LocStatus.WAITING)
    val locStatus: StateFlow<LocStatus> get() = _locStatus
    private val sm = app.getSystemService(SensorManager::class.java)
    private var declination = 0f
    private var statusJob: kotlinx.coroutines.Job? = null
    private val _lastKnown = MutableStateFlow<PhoneFix?>(null)
    val lastKnown: StateFlow<PhoneFix?> get() = _lastKnown

    private fun computeStatus(ctx: Context): LocStatus = when {
        !PhoneLocator.hasPermission(ctx) -> LocStatus.NO_PERMISSION
        !PhoneLocator.systemEnabled(ctx) -> LocStatus.SYSTEM_OFF
        _phone.value == null || System.currentTimeMillis() - _phone.value!!.time > 120_000 -> LocStatus.WAITING
        else -> LocStatus.OK
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownRecent(ctx: Context, maxAgeMs: Long): PhoneFix? {
        if (!PhoneLocator.hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        val age = (android.os.SystemClock.elapsedRealtimeNanos() - best.elapsedRealtimeNanos) / 1_000_000
        return if (age in 0..maxAgeMs) PhoneFix(best.latitude, best.longitude, if (best.hasAccuracy()) best.accuracy else null, System.currentTimeMillis() - age) else null
    }

    fun startSensors() {
        val app = getApplication<Application>()
        _lastKnown.value = lastKnownRecent(app, 10 * 60_000L)
        if (locator == null) locator = PhoneLocator(app, 2000L) { onLocation(it) }
        locator?.let { if (!it.running) it.start() }
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            var wasOn = PhoneLocator.systemEnabled(app)
            while (true) {
                _locStatus.value = computeStatus(app)
                val on = PhoneLocator.systemEnabled(app)
                if (on && !wasOn) { locator?.stop(); locator?.start() }
                if (locator?.running != true && PhoneLocator.hasPermission(app)) locator?.start()
                wasOn = on
                delay(1500)
            }
        }
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stopSensors() { locator?.stop(); statusJob?.cancel(); sm?.unregisterListener(this) }

    private fun onLocation(l: Location) {
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > 10 * 60_000L) return
        _phone.value = PhoneFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, System.currentTimeMillis(), if (l.hasAltitude()) l.altitude else null)
        _locStatus.value = computeStatus(getApplication())
        NavState.pin.value?.let { id -> pins.value.firstOrNull { it.id == id }?.let { p ->
            if (!NavState.arrived && Geo.distanceM(l.latitude, l.longitude, p.lat, p.lon) <= 20) {
                NavState.arrived = true
                android.widget.Toast.makeText(getApplication(), "📍 Arrived at ${p.name}", android.widget.Toast.LENGTH_LONG).show()
            }
        } }
        declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination
    }

    private val rot = FloatArray(9); private val rot2 = FloatArray(9); private val ori = FloatArray(3)
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        @Suppress("DEPRECATION")
        val r = (getApplication<Application>().getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val (ax, ay) = when (r) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, ax, ay, rot2)
        SensorManager.getOrientation(rot2, ori)
        val az = ((Math.toDegrees(ori[0].toDouble()).toFloat() + declination) + 360f) % 360f
        val prev = _heading.value
        _heading.value = if (prev == null) az else {
            var d = az - prev; if (d > 180) d -= 360; if (d < -180) d += 360
            (prev + d * 0.25f + 360f) % 360f
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
