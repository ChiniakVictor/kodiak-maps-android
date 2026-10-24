package com.victor.kodiakmaps.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val metric: Boolean = false,
    val dimMap: Boolean = false,
    val keepScreenOn: Boolean = false,
    val base: String = "usgs",               // "usgs" | "otm"
    val sheetState: Int = 0,                 // 0 collapsed, 1 half, 2 expanded
    val fullscreen: Boolean = false,
    val coordFormat: Int = 0,                // 0 decimal degrees, 1 deg-min, 2 deg-min-sec
    val paceMps: Float = 1.34f,              // ETA pace while navigating
    val layerEasements: Boolean = true,
    val layerRow: Boolean = true,
    val easementCheck: Boolean = false,
    val layerTrails: Boolean = true,
    val layerLogRoads: Boolean = true,
    val layerBike: Boolean = true,
    val showTracks: Boolean = true,
    val layerParcels: Boolean = true,
    val layerRoads: Boolean = true,
    val layerFish: Boolean = true,
    val railOpen: Boolean = true,
    val layerTides: Boolean = true,
    val layerHot: Boolean = true,
)

object Prefs {
    private const val FILE = "kodiakmaps"
    private val _s = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> get() = _s
    @Volatile private var loaded = false

    @Synchronized
    fun load(ctx: Context): Settings {
        if (loaded) return _s.value
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Settings()
        _s.value = Settings(
            metric = p.getBoolean("metric", d.metric), dimMap = p.getBoolean("dimMap", d.dimMap),
            keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn), base = p.getString("base", d.base) ?: d.base,
            sheetState = p.getInt("sheetState", d.sheetState), fullscreen = p.getBoolean("fullscreen", d.fullscreen),
            coordFormat = p.getInt("coordFormat", d.coordFormat), paceMps = p.getFloat("paceMps", d.paceMps),
            layerEasements = p.getBoolean("layerEasements", true), layerRow = p.getBoolean("layerRow", true),
            easementCheck = p.getBoolean("easementCheck", false), layerTrails = p.getBoolean("layerTrails", true),
            layerLogRoads = p.getBoolean("layerLogRoads", true), layerBike = p.getBoolean("layerBike", true), showTracks = p.getBoolean("showTracks", true),
            layerParcels = p.getBoolean("layerParcels", true), layerRoads = p.getBoolean("layerRoads", true),
            layerFish = p.getBoolean("layerFish", true), railOpen = p.getBoolean("railOpen", true), layerTides = p.getBoolean("layerTides", true), layerHot = p.getBoolean("layerHot", true),
        )
        loaded = true
        return _s.value
    }

    @Synchronized
    fun update(ctx: Context, f: (Settings) -> Settings) {
        load(ctx)
        val n = f(_s.value); _s.value = n
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("metric", n.metric).putBoolean("dimMap", n.dimMap).putBoolean("keepScreenOn", n.keepScreenOn)
            .putString("base", n.base).putInt("sheetState", n.sheetState).putBoolean("fullscreen", n.fullscreen)
            .putInt("coordFormat", n.coordFormat).putFloat("paceMps", n.paceMps)
            .putBoolean("layerEasements", n.layerEasements).putBoolean("layerRow", n.layerRow).putBoolean("easementCheck", n.easementCheck)
            .putBoolean("layerTrails", n.layerTrails).putBoolean("layerLogRoads", n.layerLogRoads).putBoolean("layerBike", n.layerBike).putBoolean("showTracks", n.showTracks).putBoolean("layerParcels", n.layerParcels).putBoolean("layerRoads", n.layerRoads).putBoolean("layerFish", n.layerFish).putBoolean("railOpen", n.railOpen).putBoolean("layerTides", n.layerTides).putBoolean("layerHot", n.layerHot)
            .apply()
    }
}
