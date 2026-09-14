package com.cbkres.visavole

import android.app.Application
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.cbkres.visavole.ui.DocumentsScreen
import com.cbkres.visavole.ui.OnboardingScreen
import com.cbkres.visavole.ui.TripsScreen
import com.cbkres.visavole.ui.theme.Visa_VoleTheme
import com.cbkres.visavole.ui.WorldMapCanvas
import com.cbkres.visavole.ui.HOME
import com.cbkres.visavole.ui.colorFor

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
                OnboardingScreen(countries = s.world.countries, onPick = { iso, expiry -> vm.setHome(iso, expiry) })
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
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visa Vole") },
            )
        },
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
            MapTab(
                s,
                selected,
                { selected = it },
                active = tab == 0,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (tab == 0) 1f else 0f)
                    .zIndex(if (tab == 0) 1f else 0f),
            )
            DocumentsScreen(
                vm,
                s,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (tab == 1) 1f else 0f)
                    .zIndex(if (tab == 1) 1f else 0f),
            )
            TripsScreen(
                vm,
                s,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (tab == 2) 1f else 0f)
                    .zIndex(if (tab == 2) 1f else 0f),
            )
        }
    }
}

@Composable
private fun MapTab(
    s: AppState.Ready,
    selected: String?,
    onSelect: (String?) -> Unit,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    val matches = remember(query, s.world) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else s.world.countries.values
            .filter { it.name.lowercase().contains(q) || it.iso2.lowercase() == q }
            .sortedBy { it.name }
            .take(20)
    }
    val pick: (String?) -> Unit = { iso ->
        query = ""
        if (active) focusManager.clearFocus(true)
        onSelect(iso)
    }
    val density = LocalDensity.current
    var searchBarH by remember { mutableStateOf(0.dp) }
    var cardH by remember { mutableStateOf(0.dp) }
    var legendH by remember { mutableStateOf(0.dp) }
    val mapBottomPadding = if (selected != null) (cardH - 16.dp).coerceAtLeast(0.dp) else 0.dp
    val controlsBottomPadding = if (selected != null) 24.dp else legendH + 8.dp
    Box(modifier.fillMaxSize()) {
        WorldMapCanvas(
            geometry = s.geometry,
            access = s.access,
            selected = selected,
            onCountryTap = pick,
            homeCountries = s.homeCountries,
            ownVisaCountries = s.ownVisaCountries,
            controlsBottomPadding = controlsBottomPadding,
            topInset = searchBarH,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = mapBottomPadding),
        )
        SearchBar(
            query = query,
            onQuery = { query = it },
            active = active,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { searchBarH = Dp(it.height / density.density) },
        )
        val selName = selected?.let { iso -> s.world.countries[iso]?.name ?: s.geometry.countries[iso]?.name ?: iso }
        if (selected != null && selName != null) {
            val selectedAccess = s.access[selected]
            val stayRule = when {
                selected in s.homeCountries -> null
                selectedAccess?.level == AccessLevel.RESIDENCE -> null // you live here — no short-stay limit
                else -> s.world.stayRuleFor(selected, s.homeCountries)
            }
            CountryDetailCard(
                countryName = selName,
                access = selectedAccess,
                breakdown = AccessModel.breakdownFor(selected, s.docs, s.world),
                onDismiss = { pick(null) },
                isHome = selected in s.homeCountries,
                isOwnCovered = selected in s.ownVisaCountries,
                homePassport = if (selected in s.homeCountries) s.world.countries[selected]?.name else null,
                daysLabel = AccessModel.stayLabelFor(selectedAccess, stayRule),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .onSizeChanged { cardH = Dp(it.height / density.density) },
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { legendH = Dp(it.height / density.density) },
            ) {
                LegendRow(s.access.values.groupBy { it.level }.mapValues { it.value.size }, s.homeCountries.size)
            }
        }
        if (query.isNotBlank() && matches.isNotEmpty()) {
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
                        matches.forEach { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pick(c.iso2) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium)
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
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    var searchFocused by remember { mutableStateOf(false) }
    val searchFocusedNow = rememberUpdatedState(searchFocused)

    DisposableEffect(view, active) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = Rect()
            view.getWindowVisibleDisplayFrame(visible)
            val imeHeight = view.height - visible.bottom
            val threshold = view.resources.displayMetrics.heightPixels / 5
            if (active && imeHeight <= threshold && searchFocusedNow.value) {
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
