package com.victor.kodiakmaps.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** POI layer toggles (all default OFF) and first-run state (privacy accepted, tour "don't show again"). */
object PoiPrefs {
    data class S(
        val food: Boolean = false, val travel: Boolean = false, val mil: Boolean = false,
        val schools: Boolean = false, val community: Boolean = false,
        val privacyOk: Boolean = false, val tourNever: Boolean = false,
    )
    private const val FILE = "poi_onboarding"
    private val _s = MutableStateFlow(S())
    val state: StateFlow<S> get() = _s
    @Volatile private var loaded = false

    @Synchronized fun load(ctx: Context): S {
        if (loaded) return _s.value
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _s.value = S(p.getBoolean("food", false), p.getBoolean("travel", false), p.getBoolean("mil", false),
            p.getBoolean("schools", false), p.getBoolean("community", false), p.getBoolean("privacyOk", false), p.getBoolean("tourNever", false))
        loaded = true; return _s.value
    }

    @Synchronized fun update(ctx: Context, f: (S) -> S) {
        load(ctx); val n = f(_s.value); _s.value = n
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("food", n.food).putBoolean("travel", n.travel).putBoolean("mil", n.mil).putBoolean("schools", n.schools)
            .putBoolean("community", n.community).putBoolean("privacyOk", n.privacyOk).putBoolean("tourNever", n.tourNever).apply()
    }
}
