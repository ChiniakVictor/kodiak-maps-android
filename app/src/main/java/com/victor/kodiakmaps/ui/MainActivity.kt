package com.victor.kodiakmaps.ui

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import com.victor.kodiakmaps.data.Prefs
import com.victor.kodiakmaps.map.OfflineMaps

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val mapHandle = MapHandle()
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.startSensors() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.load(this)
        val need = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (need.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) perms.launch(need)
        OfflineMaps.refresh(this)
        DebugFocus.from(intent)
        setContent {
            KMTheme {
                val s by vm.settings.collectAsState()
                LaunchedEffect(s.keepScreenOn) {
                    if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    MapScreen(vm, mapHandle)
                    OnboardingHost("Kodiak Maps", KodiakMapsTour.PRIVACY, KodiakMapsTour.steps)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); DebugFocus.from(intent) }

    // GPS + compass only while visible (no background work unless a track is recording, which has its own service).
    override fun onStart() { super.onStart(); mapHandle.rearm(System.currentTimeMillis()); vm.startSensors() }
    override fun onStop() { mapHandle.autoCenter.onBackground(System.currentTimeMillis()); vm.stopSensors(); super.onStop() }
}

/** Launch extra `--es focus "<Kodiak feature name or EIN>"`: centres the map on it and opens its detail sheet (testing / screenshots). */
object DebugFocus {
    val target = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun from(i: android.content.Intent?) { i?.getStringExtra("focus")?.takeIf { it.isNotBlank() }?.let { target.value = it } }
}

/** First-run privacy note and guided tour content (Settings → Privacy / Tutorial reopen them). */
object KodiakMapsTour {
    val PRIVACY = listOf(
        "Your location stays on your phone. Kodiak Maps does not share it with anyone.",
        "We don't sell your info.",
        "We don't store your info on any server. Your pins, tracks and settings are saved only on this phone.",
        "There are no accounts, no ads and no trackers.",
        "The app goes online only to get map pictures, tide tables, fishing reports, easement updates and app updates. It never sends your GPS position. Map sites can see which area of the map you look at, like with any map app.",
        "Pins and tracks only leave your phone if you choose to share or export them.",
    )
    val steps = listOf(
        TourStep(null, "Kodiak Maps tour", "A quick look at the main parts of the app. Tap Next to go on, Back to go back, or Skip to leave the tour."),
        TourStep("rail", "Layer bar", "This bar turns map layers on and off. Tap a circle to switch a layer. Filled means on. Long-press a circle to see what its colours mean. Tap ‹ to hide the bar."),
        TourStep("layers", "All layers", "This button opens the full layer list with on/off switches and more detail about each layer. On the folded screen it is inside the ⋯ menu."),
        TourStep("rail", "Easements", "⛺ shows 17(b) public easements on Native land. Orange is a 25-ft trail, red a 50-ft trail, purple a road, and blue P a 1- or 2-acre site. Tap one to read its allowed uses and the patent text."),
        TourStep("rail", "Bike trails", "🚵 shows the town bike trails in their map colours. Green dots are trailheads. Orange dots are trail features and work spots. Tap any of them for notes."),
        TourStep("rail", "Fishing spots", "🐟 shows ADF&G fishing spots with species, run timing and limits. 🔥 marks spots that are hot right now from recent reports."),
        TourStep("rail", "New places", "🍴 Food (orange = ready to eat, green = groceries), ✈ Travel (airports, ferry, floatplanes, harbors, air taxis), 🏫 Schools with attendance areas, ★ Military history near Chiniak, and 📚 the Chiniak Library. They start off. Turn on the ones you want."),
        TourStep("sheet", "Tap cards", "Tap anything on the map: an easement, trail, fishing spot or place. Its card opens down here with details, Call and Website buttons, and Go to navigate there."),
        TourStep("gps", "GPS and centering", "Tap here to follow your position. Tap again to stop following. The app centres on you when it opens."),
        TourStep("sheet", "Offline use", "All Kodiak layers are built in and work with no signal. To use the topo map offline, open this panel, tap ⬇ Maps and save an area while you have Wi-Fi."),
        TourStep("update", "Check for update", "This button checks kodiakking.com for a newer version. It checks once when the app opens. You can also check from Settings."),
        TourStep("sheet", "Settings", "Open this panel and tap ⚙ for Settings. There you can replay this Tutorial, read the Privacy note again, and check for updates."),
    )
}
