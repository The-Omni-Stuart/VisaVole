package com.cbkres.visavole

import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.hazeAlphas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.AppState
import com.cbkres.visavole.ui.CountryDetailCard
import com.cbkres.visavole.ui.CountryRow
import com.cbkres.visavole.ui.EmptyState
import com.cbkres.visavole.ui.DocumentsScreen
import com.cbkres.visavole.ui.OnboardingScreen
import com.cbkres.visavole.ui.SettingsScreen
import com.cbkres.visavole.ui.TripsScreen
import com.cbkres.visavole.ui.VisaSnackbarHost
import com.cbkres.visavole.ui.theme.Visa_VoleTheme
import com.cbkres.visavole.ui.WorldMapCanvas
import com.cbkres.visavole.ui.HOME
import com.cbkres.visavole.ui.colorFor
import com.cbkres.visavole.ui.Focus
import com.cbkres.visavole.ui.IsoShape
import com.cbkres.visavole.ui.buildFocus
import com.cbkres.visavole.ui.buildShapes
import com.cbkres.visavole.ui.defaultCenter
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val darkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        setContent {
            Visa_VoleTheme {
                VisaVoleApp()
            }
        }
    }
}

@Composable
fun VisaVoleApp() {
    val app = LocalContext.current.applicationContext as Application
    val vm: AccessViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app),
    )
    val state by vm.state.collectAsState(initial = AppState.Loading)
    when (val s = state) {
        AppState.Loading -> LoadingScreen()
        is AppState.Failed -> ErrorScreen(s.message) { vm.retryLoad() }
        is AppState.Ready -> {
            if (s.homeCountries.isEmpty()) {
                OnboardingScreen(countries = s.world.countries, today = s.today, onAddPassport = { iso, vfrom, expiry -> vm.addOnboardingPassport(iso, vfrom, expiry) })
            } else {
                MainScaffold(vm, s)
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Shown when the bundled visa data could not be loaded; retry reruns the initial load. */
@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

// P3-49: Float/Offset states aren't directly saveable, so persist their underlying values.
private val MapZoomSaver = object : Saver<MutableFloatState, Float> {
    override fun SaverScope.save(value: MutableFloatState): Float = value.floatValue
    override fun restore(value: Float): MutableFloatState = mutableFloatStateOf(value)
}

private val MapCenterSaver = object : Saver<MutableState<Offset>, List<Float>> {
    override fun SaverScope.save(value: MutableState<Offset>): List<Float> = listOf(value.value.x, value.value.y)
    override fun restore(value: List<Float>): MutableState<Offset> = mutableStateOf(Offset(value[0], value[1]))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AccessViewModel, s: AppState.Ready) {
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
    val expandedTrips = rememberSaveable { mutableStateListOf<String>() }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.snacks.collect { snackbarHostState.showSnackbar(it) }
    }
    val mapShapes = remember(s.geometry) { buildShapes(s.geometry) }
    val mapFocus = remember(s.geometry) { buildFocus(s.geometry) }
    // P3-49: map framing (zoom + centre) survives process death — plain `remember` reset it to
    // the default view on every cold start.
    val mapZoom = rememberSaveable(saver = MapZoomSaver) { mutableFloatStateOf(1f) }
    val mapCenter = rememberSaveable(saver = MapCenterSaver) { mutableStateOf(defaultCenter(s.geometry)) }
    // rememberLazyListState() is rememberSaveable(saver = LazyListState.Saver) under the hood,
    // so the scroll position survives both the tab switch and process death — the same guarantee
    // the Phase-C saveable pass gave tab/selected/query/expandedTrips (P2-15).
    val docsScroll = rememberLazyListState()
    val tripsScroll = rememberLazyListState()
    Scaffold(
        topBar = {
            // The bar is persistent: Settings swaps its contents (back arrow + title) instead of
            // removing it, so the content area never moves — the map's search bar and legend stay
            // put while Settings is open and nothing pops back on the way out.
            TopAppBar(
                title = { Text(if (showSettings) "Settings" else "Visa Vole") },
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
            // Persistent too — switching tabs while Settings is open just closes it.
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { showSettings = false; tab = 0 },
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    label = { Text("Map") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { showSettings = false; tab = 1 },
                    icon = { Icon(Icons.Filled.Face, contentDescription = null) },
                    label = { Text("Documents") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { showSettings = false; tab = 2 },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("Trips") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> MapTab(
                    s,
                    selected,
                    { selected = it },
                    query = mapQuery,
                    onQuery = { mapQuery = it },
                    shapes = mapShapes,
                    focus = mapFocus,
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showSettings) {
                SettingsScreen(
                    vm,
                    s,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MapTab(
    s: AppState.Ready,
    selected: String?,
    onSelect: (String?) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    shapes: List<IsoShape>,
    focus: Map<String, Focus>,
    zoomState: MutableFloatState,
    centerState: MutableState<Offset>,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    // P3-59: the search field must blur when the map is tapped or Back is pressed, otherwise it
    // keeps its focus/selection forever. This Compose build only exposes clearFocus on the
    // FocusManager (there is no public Modifier.clearFocus / FocusRequester), so grab the manager
    // from LocalFocusManager and clear directly. searchFocused (via onFocusChanged) gates the Back
    // handler + the map-tap blur so we only clear while the field actually holds focus.
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = searchFocused) {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    // The dropdown fades out over 120ms after the query is cleared; matches must keep the last
    // non-blank query during that exit, otherwise the content flips to "No matching countries"
    // mid-fade right after the user picks a country.
    var shownQuery by remember { mutableStateOf(query) }
    if (query.isNotBlank() && query != shownQuery) shownQuery = query
    val matches = remember(shownQuery, s.world) {
        val q = shownQuery.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else s.world.countries.values
            .filter { it.name.lowercase().contains(q) || it.iso2.lowercase() == q }
            .sortedBy { it.name }
            .take(20)
    }
    // Stable identity: a fresh lambda on every recomposition would make WorldMapCanvas re-run
    // and repaint every country for the whole duration of the card slide (P1-13).
    val pick: (String?) -> Unit = remember {
        { iso: String? ->
            keyboardController?.hide()
            if (searchFocused) focusManager.clearFocus()
            onQuery("")
            onSelect(iso)
        }
    }
    val density = LocalDensity.current
    var searchBarH by remember { mutableStateOf(0.dp) }
    var cardH by remember { mutableStateOf(0.dp) }
    var legendH by remember { mutableStateOf(0.dp) }
    val selName = selected?.let { iso -> s.world.countries[iso]?.name ?: s.geometry.countries[iso]?.name ?: iso }
    val cardVisible = selected != null && selName != null
    // The bottom padding the map's floating UI keeps between itself and the card/legend: the
    // measured card height plus 24dp while a card is up, the legend height plus 8dp otherwise.
    val settledBottomPadding = if (cardVisible) cardH + 24.dp else legendH + 8.dp
    // Animated (same spring as the card slide). The map canvas is never shrunk for the card —
    // the card slides over the full-bleed map — so no dark band can appear between the map
    // edge and the card while the fixed 420dp drop exceeds the card's actual height. The
    // floating zoom controls ride cardH above the bottom, keeping them 24dp above the card's
    // top edge. The map's focus-fit uses the SETTLED padding instead, so its centre-on-country
    // target is stable while the card slides in (see WorldMapCanvas).
    val cardAnimSpec = spring<Dp>(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
    val controlsBottomPadding by animateDpAsState(
        targetValue = settledBottomPadding,
        animationSpec = cardAnimSpec,
        label = "controlsBottomPadding",
    )
    Box(modifier.fillMaxSize()) {
        WorldMapCanvas(
            geometry = s.geometry,
            access = s.access,
            shapes = shapes,
            focus = focus,
            zoomState = zoomState,
            centerState = centerState,
            selected = selected,
            onCountryTap = pick,
            homeCountries = s.homeCountries,
            ownVisaCountries = s.ownVisaCountries,
            controlsBottomPadding = controlsBottomPadding,
            centeringBottomPadding = settledBottomPadding,
            topInset = searchBarH,
            modifier = Modifier.fillMaxSize(),
        )
        SearchBar(
            query = query,
            onQuery = onQuery,
            onFocusChanged = { searchFocused = it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { searchBarH = Dp(it.height / density.density) },
        )
        // The slide is a graphicsLayer offset, not a layout slide (slideInVertically): while the
        // enter animation runs the card must stay hit-testable at its final position, otherwise
        // an early swipe on the breakdown falls through to the map canvas and pans the map
        // instead of scrolling the list.
        val cardDrop by animateDpAsState(
            targetValue = if (cardVisible) 0.dp else 420.dp,
            animationSpec = cardAnimSpec,
            label = "cardDrop",
        )
        // align must be on the AnimatedVisibility node itself (the direct Box child) — on the
        // inner card it is ignored by the AnimatedVisibility layout and the card lands at TopStart.
        AnimatedVisibility(
            visible = cardVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .onSizeChanged { cardH = Dp(it.height / density.density) },
        ) {
            if (selected != null) {
                val selectedAccess = s.access[selected]
                // The springs above recompose this screen every frame while the card slides, so
                // the per-selection domain lookups are remembered rather than recomputed inline.
                // Stay windows resolve against the passports that are not expired (P2-30).
                val validHomes = remember(s.docs, s.world, s.today) { AccessModel.validHomeCountries(s.docs, s.world, s.today) }
                val stayRule = remember(selected, s.homeCountries, s.access, s.world, validHomes) {
                    when {
                        selected in s.homeCountries -> null
                        s.access[selected]?.level == AccessLevel.RESIDENCE -> null // you live here — no short-stay limit
                        else -> s.world.stayRuleFor(selected, validHomes, s.today)
                    }
                }
                val breakdown = remember(selected, s.docs, s.world, s.trips) {
                    AccessModel.breakdownFor(selected, s.docs, s.world, trips = s.trips)
                }
                val daysLabel = remember(selectedAccess, stayRule) {
                    AccessModel.stayLabelFor(selectedAccess, stayRule)
                }
                CountryDetailCard(
                    countryName = selName!!,
                    access = selectedAccess,
                    breakdown = breakdown,
                    onDismiss = { pick(null) },
                    modifier = Modifier.graphicsLayer { translationY = cardDrop.toPx() },
                    isHome = selected in s.homeCountries,
                    isOwnCovered = selected in s.ownVisaCountries,
                    homePassport = if (selected in s.homeCountries) s.world.countries[selected]?.name else null,
                    daysLabel = daysLabel,
                )
            }
        }
        if (selName == null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { legendH = Dp(it.height / density.density) },
            ) {
                LegendRow(s.access.values.groupBy { it.level }.mapValues { it.value.size }, s.homeCountries.size)
            }
        }
        AnimatedVisibility(
            visible = query.isNotBlank(),
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.95f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.95f),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .offset(y = searchBarH)
                    .heightIn(max = 240.dp),
            ) {
                val searchScroll = rememberScrollState()
                val (searchTop, searchEnd) = searchScroll.hazeAlphas()
                HazeBox(searchTop, searchEnd, MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    Column(Modifier.verticalScroll(searchScroll)) {
                        if (matches.isEmpty()) {
                            // match CountryRow's inner horizontal padding so the text aligns with rows
                            EmptyState("No matching countries", modifier = Modifier.padding(horizontal = 10.dp))
                        } else {
                            matches.forEach { c ->
                                CountryRow(name = c.name, onClick = { pick(c.iso2) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search country…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun LegendRow(counts: Map<AccessLevel, Int>, homeCount: Int = 1) {
    val levels = listOf(
        AccessLevel.FREEDOM, AccessLevel.RESIDENCE, AccessLevel.VISA_FREE, AccessLevel.COVERED,
        AccessLevel.ETA, AccessLevel.E_VISA, AccessLevel.VISA_REQUIRED, AccessLevel.REFUSED,
    )
    val legendScroll = rememberScrollState()
    val (legendStart, legendEnd) = legendScroll.hazeAlphas()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp) {
        HazeBox(legendStart, legendEnd, MaterialTheme.colorScheme.surfaceContainerHigh, horizontal = true, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(legendScroll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .background(HOME),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (homeCount > 1) "Your countries $homeCount" else "Your country",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            levels.forEach { lvl ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(colorFor(lvl)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(lvl.label(), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${counts[lvl] ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        }
    }
}

