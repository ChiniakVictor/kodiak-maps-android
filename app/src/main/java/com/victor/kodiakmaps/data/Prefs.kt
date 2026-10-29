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
    val layerRow: Boolean = false,
    val easementCheck: Boolean = false,
    val layerTrails: Boolean = false,
    val layerLogRoads: Boolean = true,
    val layerBike: Boolean = false,
    val showTracks: Boolean = true,
    val layerParcels: Boolean = false,
    val layerRoads: Boolean = false,
    val layerFish: Boolean = true,
    val railOpen: Boolean = true,
    val layerTides: Boolean = false,
    val layerHot: Boolean = true,
)

/** v1.5.13 (Victor): the app opens with only logging roads, easements, fishing and hot spots on.
 *  Every other layer starts off but stays in the layers bar. Installs upgraded from 1.5.12 or older get these defaults
 *  once (the first start of 1.5.13); after that the user's own toggles are kept as before. */
object LayerDefaults {
    const val VERSION = 1
    const val KEY = "layerDefaultsVer"
    /** Layer keys in the "kodiakmaps" prefs file and their default on/off. */
    val MAP = linkedMapOf("layerEasements" to true, "layerLogRoads" to true, "layerFish" to true, "layerHot" to true,
        "layerRow" to false, "layerTrails" to false, "layerBike" to false, "layerParcels" to false, "layerRoads" to false, "layerTides" to false)
    /** POI layer keys in the "poi_onboarding" prefs file: all off. */
    val POI = listOf("food", "travel", "mil", "schools", "community")

    /** Writes [defaults] once per prefs file (when its stored version is below [VERSION]); returns true if it did. */
    fun migrate(p: android.content.SharedPreferences, defaults: Map<String, Boolean>): Boolean {
        if (p.getInt(KEY, 0) >= VERSION) return false
        val e = p.edit(); defaults.forEach { (k, v) -> e.putBoolean(k, v) }; e.putInt(KEY, VERSION).apply()
        return true
    }
}

object Prefs {
    private const val FILE = "kodiakmaps"
    private val _s = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> get() = _s
    @Volatile private var loaded = false

    @Synchronized
    fun load(ctx: Context): Settings {
        if (loaded) return _s.value
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        LayerDefaults.migrate(p, LayerDefaults.MAP) // one-time: 1.5.13 default layers (fresh installs and upgrades); other settings untouched
        val d = Settings()
        _s.value = Settings(
            metric = p.getBoolean("metric", d.metric), dimMap = p.getBoolean("dimMap", d.dimMap),
            keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn), base = p.getString("base", d.base) ?: d.base,
            sheetState = p.getInt("sheetState", d.sheetState), fullscreen = p.getBoolean("fullscreen", d.fullscreen),
            coordFormat = p.getInt("coordFormat", d.coordFormat), paceMps = p.getFloat("paceMps", d.paceMps),
            layerEasements = p.getBoolean("layerEasements", d.layerEasements), layerRow = p.getBoolean("layerRow", d.layerRow),
            easementCheck = p.getBoolean("easementCheck", false), layerTrails = p.getBoolean("layerTrails", d.layerTrails),
            layerLogRoads = p.getBoolean("layerLogRoads", d.layerLogRoads), layerBike = p.getBoolean("layerBike", d.layerBike), showTracks = p.getBoolean("showTracks", true),
            layerParcels = p.getBoolean("layerParcels", d.layerParcels), layerRoads = p.getBoolean("layerRoads", d.layerRoads),
            layerFish = p.getBoolean("layerFish", d.layerFish), railOpen = p.getBoolean("railOpen", true), layerTides = p.getBoolean("layerTides", d.layerTides), layerHot = p.getBoolean("layerHot", d.layerHot),
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
