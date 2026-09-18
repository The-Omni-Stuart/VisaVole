package com.cbkres.visavole

import android.app.Application
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
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
import com.cbkres.visavole.ui.TripsScreen
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
        is AppState.Ready -> {
            if (s.homeCountries.isEmpty()) {
                OnboardingScreen(countries = s.world.countries, today = s.today, onAddPassport = { iso, expiry -> vm.addOnboardingPassport(iso, expiry) })
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
    val expandedTrips = rememberSaveable { mutableStateListOf<String>() }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.snacks.collect { snackbarHostState.showSnackbar(it) }
    }
    val mapShapes = remember(s.geometry) { buildShapes(s.geometry) }
    val mapFocus = remember(s.geometry) { buildFocus(s.geometry) }
    val mapZoom = remember { mutableFloatStateOf(1f) }
    val mapCenter = remember(s.geometry) { mutableStateOf(defaultCenter(s.geometry)) }
    val docsScroll = rememberLazyListState()
    val tripsScroll = rememberLazyListState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visa Vole") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    label = { Text("Map") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Face, contentDescription = null) },
                    label = { Text("Documents") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
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
    val focusManager = LocalFocusManager.current
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
    val pick: (String?) -> Unit = { iso ->
        onQuery("")
        focusManager.clearFocus(true)
        onSelect(iso)
    }
    val density = LocalDensity.current
    var searchBarH by remember { mutableStateOf(0.dp) }
    var cardH by remember { mutableStateOf(0.dp) }
    var legendH by remember { mutableStateOf(0.dp) }
    val selName = selected?.let { iso -> s.world.countries[iso]?.name ?: s.geometry.countries[iso]?.name ?: iso }
    val cardVisible = selected != null && selName != null
    // Animated (same spring as the card slide). The map canvas is never shrunk for the card —
    // the card slides over the full-bleed map — so no dark band can appear between the map
    // edge and the card while the fixed 420dp drop exceeds the card's actual height. The
    // floating zoom controls instead ride cardH above the bottom, keeping them 24dp above the
    // card's top edge; the map's focus-fit reads the same padding, so the selected country is
    // centred above the card, not underneath it.
    val cardAnimSpec = spring<Dp>(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
    val controlsBottomPadding by animateDpAsState(
        targetValue = if (cardVisible) cardH + 24.dp else legendH + 8.dp,
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
            topInset = searchBarH,
            modifier = Modifier.fillMaxSize(),
        )
        SearchBar(
            query = query,
            onQuery = onQuery,
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
            animationSpec = spring<Dp>(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
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
                val stayRule = when {
                    selected in s.homeCountries -> null
                    selectedAccess?.level == AccessLevel.RESIDENCE -> null // you live here — no short-stay limit
                    else -> s.world.stayRuleFor(selected, s.homeCountries)
                }
                CountryDetailCard(
                    countryName = selName!!,
                    access = selectedAccess,
                    breakdown = AccessModel.breakdownFor(selected, s.docs, s.world, trips = s.trips),
                    onDismiss = { pick(null) },
                    modifier = Modifier.graphicsLayer { translationY = cardDrop.toPx() },
                    isHome = selected in s.homeCountries,
                    isOwnCovered = selected in s.ownVisaCountries,
                    homePassport = if (selected in s.homeCountries) s.world.countries[selected]?.name else null,
                    daysLabel = AccessModel.stayLabelFor(selectedAccess, stayRule),
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
                            EmptyState("No matching countries")
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
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    var searchFocused by remember { mutableStateOf(false) }
    val searchFocusedNow = rememberUpdatedState(searchFocused)

    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val imeHeight = imeHeightOf(view)
            val threshold = view.resources.displayMetrics.heightPixels / 5
            if (imeHeight <= threshold && searchFocusedNow.value) {
                focusManager.clearFocus(false)
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

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
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(true) }),
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 4.dp)
            .onFocusChanged { searchFocused = it.isFocused },
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

/** Bottom inset currently occupied by the IME. API 30+ reads WindowInsets.Type.ime(); on
 *  API 28–29 it falls back to the visible-frame diff, which is accurate here because the
 *  activity runs edge-to-edge (enableEdgeToEdge), so the frame shrinks by exactly the IME height. */
private fun imeHeightOf(view: View): Int =
    if (Build.VERSION.SDK_INT >= 30) {
        ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
    } else {
        @Suppress("DEPRECATION")
        run {
            val visible = Rect()
            view.getWindowVisibleDisplayFrame(visible)
            view.height - visible.bottom
        }
    }
