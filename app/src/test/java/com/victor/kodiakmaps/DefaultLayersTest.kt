package com.victor.kodiakmaps

import android.content.SharedPreferences
import com.victor.kodiakmaps.data.LayerDefaults
import com.victor.kodiakmaps.data.PoiPrefs
import com.victor.kodiakmaps.data.Settings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.5.13 (Victor): open with only logging roads, easements, fishing and hot spots on; everything else off but still in the layers bar. */
class DefaultLayersTest {
    private val on = setOf("layerEasements", "layerLogRoads", "layerFish", "layerHot")

    @Test fun freshDefaults() {
        val s = Settings()
        assertTrue(s.layerEasements && s.layerLogRoads && s.layerFish && s.layerHot)
        assertFalse(s.layerRow || s.layerTrails || s.layerBike || s.layerParcels || s.layerRoads || s.layerTides)
        val p = PoiPrefs.S(); assertFalse(p.food || p.travel || p.mil || p.schools || p.community)
        assertEquals(on, LayerDefaults.MAP.filterValues { it }.keys)
        // the map in LayerDefaults matches the Settings() defaults field by field
        val d = mapOf("layerEasements" to s.layerEasements, "layerLogRoads" to s.layerLogRoads, "layerFish" to s.layerFish, "layerHot" to s.layerHot,
            "layerRow" to s.layerRow, "layerTrails" to s.layerTrails, "layerBike" to s.layerBike, "layerParcels" to s.layerParcels, "layerRoads" to s.layerRoads, "layerTides" to s.layerTides)
        assertEquals(d, LayerDefaults.MAP.toMap())
    }

    @Test fun upgradeAppliesDefaultsOnceThenKeepsUserChoices() {
        // a 1.5.12 install: everything on, food/mil on, privacy accepted, tip dismissed for good
        val p = FakePrefs(mutableMapOf("layerTrails" to true, "layerParcels" to true, "layerTides" to true, "layerEasements" to false, "metric" to true, "base" to "otm"))
        assertTrue(LayerDefaults.migrate(p, LayerDefaults.MAP))
        LayerDefaults.MAP.forEach { (k, v) -> assertEquals(k, v, p.getBoolean(k, !v)) }
        assertEquals(true, p.m["metric"]); assertEquals("otm", p.m["base"])           // other settings untouched
        p.m["layerTrails"] = true                                                         // user turns trails back on later
        assertFalse(LayerDefaults.migrate(p, LayerDefaults.MAP))                          // second start: no reset
        assertTrue(p.getBoolean("layerTrails", false))

        val q = FakePrefs(mutableMapOf("food" to true, "mil" to true, "privacyOk" to true, "tourNever" to true, "layersTipNever" to true))
        assertTrue(LayerDefaults.migrate(q, LayerDefaults.POI.associateWith { false }))
        LayerDefaults.POI.forEach { assertFalse(it, q.getBoolean(it, true)) }
        assertTrue(q.getBoolean("privacyOk", false) && q.getBoolean("tourNever", false) && q.getBoolean("layersTipNever", false))
    }

    @Test fun everyLayerStillInTheBarAndTipStays() {
        val src = File("src/main/java/com/victor/kodiakmaps/ui/MapScreen.kt").readText()
        listOf("\"Easemt\"", "\"ROW\"", "\"Trails\"", "\"Bike\"", "\"Logging\"", "\"Property\"", "\"Roads\"", "\"Fishing\"", "\"Hot\"", "\"Tides\"",
            "\"Food\"", "\"Travel\"", "\"Schools\"", "\"Military\"", "\"Library\"").forEach { assertTrue(it, src.contains("RailItem($it") || src.contains("poiItem($it")) }
        assertTrue(File("src/main/java/com/victor/kodiakmaps/ui/Onboarding.kt").readText().contains("Pick your layers"))
        listOf("Prefs.kt", "PoiPrefs.kt").forEach { assertTrue(it, File("src/main/java/com/victor/kodiakmaps/data/$it").readText().contains("LayerDefaults.migrate(p")) }
    }

    private class FakePrefs(val m: MutableMap<String, Any>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?) = d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pend = HashMap<String, Any>()
            override fun putString(k: String, v: String?) = apply { if (v != null) pend[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = apply { pend[k] = v }
            override fun putLong(k: String, v: Long) = apply { pend[k] = v }
            override fun putFloat(k: String, v: Float) = apply { pend[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { pend[k] = v }
            override fun remove(k: String) = this
            override fun clear() = this
            override fun commit(): Boolean { m.putAll(pend); return true }
            override fun apply() { m.putAll(pend) }
        }
    }
}
