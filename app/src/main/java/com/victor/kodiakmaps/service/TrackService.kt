package com.victor.kodiakmaps.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.victor.kodiakmaps.R
import com.victor.kodiakmaps.core.Geo
import com.victor.kodiakmaps.core.Gpx
import com.victor.kodiakmaps.data.MapDb
import com.victor.kodiakmaps.data.TrackEntity
import com.victor.kodiakmaps.data.TrackPointEntity
import com.victor.kodiakmaps.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Track recording. The ONLY thing in Kodiak Maps that runs in the background, and only while the user
 * has pressed Record (a "Recording track" notification with a Stop button is shown the whole time).
 */
class TrackService : LifecycleService() {
    companion object {
        const val CH = "track"; private const val NOTIF = 4200
        const val ACTION_START = "start"; const val ACTION_STOP = "stop"
        /** Id of the track being recorded, or null. */
        val recording = MutableStateFlow<Long?>(null)
        val stats = MutableStateFlow(Pair(0, 0.0))   // points, metres

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, TrackService::class.java).setAction(ACTION_START))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, TrackService::class.java).setAction(ACTION_STOP))
        fun channel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CH, "Track recording", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a track is being recorded" })
        }
    }

    private var locator: PhoneLocator? = null
    private var last: Gpx.Pt? = null
    private var trackId: Long? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CH)
        .setSmallIcon(R.drawable.ic_stat_track).setContentTitle("Kodiak Maps · recording track").setContentText(text).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 2, Intent(this, TrackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun begin() {
        if (trackId != null) return
        channel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try { ServiceCompat.startForeground(this, NOTIF, notification("Starting…"), type) }
        catch (e: Exception) { AppLog.log("Track: can't start foreground ($e)"); stopSelf(); return }
        val dao = MapDb.get(this).tracks()
        lifecycleScope.launch {
            val name = "Track " + SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date())
            val id = dao.newTrack(TrackEntity(name = name, started = System.currentTimeMillis()))
            trackId = id; recording.value = id; stats.value = 0 to 0.0
            AppLog.log("Track: recording $name ($id)")
            locator = PhoneLocator(this@TrackService, 2000L) { onFix(it) }.also { it.start() }
        }
    }

    private fun onFix(l: Location) {
        val id = trackId ?: return
        val p = Gpx.Pt(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null, l.time)
        val acc = if (l.hasAccuracy()) l.accuracy else null
        if (!Gpx.accept(last, p, acc)) return
        val prev = last; last = p
        val (n, m) = stats.value
        stats.value = (n + 1) to (m + (prev?.let { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) } ?: 0.0))
        lifecycleScope.launch { MapDb.get(this@TrackService).tracks().add(TrackPointEntity(trackId = id, time = p.time, lat = p.lat, lon = p.lon, ele = p.ele, acc = acc)) }
        getSystemService(NotificationManager::class.java)?.notify(NOTIF, notification("${stats.value.first} points · ${Geo.formatDistance(stats.value.second, com.victor.kodiakmaps.data.Prefs.settings.value.metric)}"))
    }

    private fun finish() {
        locator?.stop(); locator = null
        val id = trackId; trackId = null; recording.value = null
        if (id != null) lifecycleScope.launch {
            val dao = MapDb.get(this@TrackService).tracks()
            dao.track(id)?.let { dao.update(it.copy(ended = System.currentTimeMillis())) }
            AppLog.log("Track: stopped ($id)")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        } else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() { locator?.stop(); recording.value = null; super.onDestroy() }
}
