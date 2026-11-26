package com.cbkres.visavole.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ConnectingAirports
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.cbkres.visavole.ui.LocalReducedMotion
import com.cbkres.visavole.ui.Motion
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.VisaSnackbarHost
import com.cbkres.visavole.ui.screens.documents.DocumentsScreen
import com.cbkres.visavole.ui.screens.map.MapScreen
import com.cbkres.visavole.ui.screens.map.defaultCenter
import com.cbkres.visavole.ui.screens.settings.SettingsScreen
import com.cbkres.visavole.ui.screens.trips.TripsScreen
import com.cbkres.visavole.ui.theme.ThemePrefs

// Float/Offset states aren't directly saveable, so persist their underlying values.
private val MapZoomSaver = object : Saver<MutableFloatState, Float> {
    override fun SaverScope.save(value: MutableFloatState): Float = value.floatValue
    override fun restore(value: Float): MutableFloatState = mutableFloatStateOf(value)
}

private val MapCenterSaver = object : Saver<MutableState<Offset>, List<Float>> {
    override fun SaverScope.save(value: MutableState<Offset>): List<Float> = listOf(value.value.x, value.value.y)
    override fun restore(value: List<Float>): MutableState<Offset> = mutableStateOf(Offset(value[0], value[1]))
}

/** The top bar's two-line title for tabs that moved their header up here. */
@Composable
private fun BarHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScaffold(
    vm: AccessViewModel,
    s: AppState.Ready,
    themeMode: ThemePrefs.Mode,
    dynamicColor: Boolean,
    onThemeModeChanged: (ThemePrefs.Mode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
) {
    // rememberSaveable: tab/selection/query/expansion survive process death, and (being
    // hoisted above the tab switch) also survive moving between tabs, which disposes screens.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // MapTab is disposed when another tab is selected, so a surviving selection would replay
    // the card's enter animation on return — close it on the way out instead.
    LaunchedEffect(tab) {
        if (tab != 0) selected = null
    }
    var mapQuery by rememberSaveable { mutableStateOf("") }
    // Settings is an overlay over the tabs (the app has no navigation graph); it survives
    // process death like the other screen state.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Plain remember: mutableStateListOf has no Saver, so a saveable restore hands back a
    // dead ArrayList whose mutations stop recomposing. Expanded cards may reset on process death.
    val expandedTrips = remember { mutableStateListOf<String>() }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        // Feedback buffered while onboarding had the screen belongs to that phase; only
        // state warnings (a damaged backup) are flagged to survive into a fresh main screen.
        vm.discardStaleSnacks()
        vm.snacks.collect { snackbarHostState.showSnackbar(it) }
    }
    // the system Back button used to just exit the app. Close the topmost layer instead:
    // Settings overlay -> an open country card (Map) -> a non-home tab (jump home). The
    // search-focus BackHandler lives in MapTab and, being registered after these, takes priority
    // over the card when both apply (clear the field first, then the card on the next press).
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && tab == 0 && selected != null) { selected = null }
    BackHandler(enabled = !showSettings && tab != 0) { tab = 0 }
    // the map shapes + per-country focus are pre-built on the VM's IO thread at load time
    // (see AccessViewModel.launchLoad) and shipped on AppState.Ready, so the one-time ~121k-point
    // Path construction no longer runs on the main thread at first composition.
    // map framing (zoom + centre) survives process death — plain `remember` reset it to
    // the default view on every cold start.
    val mapZoom = rememberSaveable(saver = MapZoomSaver) { mutableFloatStateOf(1f) }
    val mapCenter = rememberSaveable(saver = MapCenterSaver) { mutableStateOf(defaultCenter(s.geometry)) }
    // rememberLazyListState() is rememberSaveable(saver = LazyListState.Saver) under the hood,
    // so the scroll position survives both the tab switch and process death — the same guarantee
    // the Phase-C saveable pass gave tab/selected/query.
    val docsScroll = rememberLazyListState()
    val tripsScroll = rememberLazyListState()
    Scaffold(
        topBar = {
            // The bar is persistent: Settings swaps its contents (back arrow + title) instead of
            // removing it, so the content area never moves — the map's search bar and legend stay
            // put while Settings is open and nothing pops back on the way out.
            TopAppBar(
                title = {
                    // Settings wins first, so its bar never keeps the tab's header behind the
                    // back arrow; Documents/Trips carry their header here to start lists at top.
                    if (showSettings) {
                        Text("Settings", style = MaterialTheme.typography.titleLarge)
                    } else when (tab) {
                        1 -> BarHeader("Documents", "Manage your passports and permits, unlocking your map.")
                        2 -> BarHeader("Trips", "Record your trips and track visa allowance balances.")
                        else -> Text("Visa Vole", style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (!showSettings) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        snackbarHost = { VisaSnackbarHost(snackbarHostState) },
        bottomBar = {
            // Settings is a full-screen overlay, so the persistent nav bar hides while it's open.
            // expand/collapse is a *size* animation (not just an offset), so the Scaffold's content
            // padding tracks the bar's height frame-by-frame — the content area grows in sync and
            // returning to a tab doesn't pop. With no tabs visible, the top-bar back arrow is the
            // Settings exit ("tap a tab to leave" no longer applies).
            val barDur = if (LocalReducedMotion.current) 0 else Motion.ELEMENT
            AnimatedVisibility(
                visible = !showSettings,
                enter = expandVertically(tween(barDur), expandFrom = Alignment.Bottom) + fadeIn(tween(barDur)),
                exit = shrinkVertically(tween(barDur), shrinkTowards = Alignment.Bottom) + fadeOut(tween(barDur)),
            ) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.TravelExplore, contentDescription = null) },
                        label = { Text("Map") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                        label = { Text("Documents") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.ConnectingAirports, contentDescription = null) },
                        label = { Text("Trips") },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // The tabs stay out of composition while Settings is up: the overlay would otherwise
            // leak taps through to the map (WorldMapCanvas accepts already-consumed events) and
            // the Trips tab's animators (Deportometer pulse, zone-pill rotation) would keep
            // running unseen. Everything that must survive this is hoisted above the when(), so
            // reopening a tab behaves exactly like returning to it from another tab.
            if (!showSettings) {
                when (tab) {
                    0 -> MapScreen(
                        s,
                        selected,
                        { selected = it },
                        query = mapQuery,
                        onQuery = { mapQuery = it },
                        shapes = s.shapes,
                        focus = s.focus,
                        zoomState = mapZoom,
                        centerState = mapCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                    1 -> DocumentsScreen(
                        vm,
                        s,
                        scrollState = docsScroll,
                        modifier = Modifier.fillMaxSize(),
                    )
                    2 -> TripsScreen(
                        vm,
                        s,
                        scrollState = tripsScroll,
                        expandedTrips = expandedTrips,
                        onToggleExpanded = { id -> if (id in expandedTrips) expandedTrips.remove(id) else expandedTrips.add(id) },
                        // a deleted trip's id would otherwise linger in the saveable list forever.
                        onTripDeleted = { id -> expandedTrips.remove(id) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (showSettings) {
                SettingsScreen(
                    vm,
                    s,
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    onThemeModeChanged = onThemeModeChanged,
                    onDynamicColorChanged = onDynamicColorChanged,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}