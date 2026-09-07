package com.example.visa_vole

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.visa_vole.domain.AccessLevel
import com.example.visa_vole.domain.AccessModel
import com.example.visa_vole.ui.AccessViewModel
import com.example.visa_vole.ui.AppState
import com.example.visa_vole.ui.CountryDetailCard
import com.example.visa_vole.ui.DocumentsScreen
import com.example.visa_vole.ui.OnboardingScreen
import com.example.visa_vole.ui.theme.Visa_VoleTheme
import com.example.visa_vole.ui.WorldMapCanvas
import com.example.visa_vole.ui.HOME
import com.example.visa_vole.ui.colorFor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        AppState.Loading -> LoadingScreen()
        is AppState.Ready -> {
            if (s.homeCountries.isEmpty()) {
                OnboardingScreen(countries = s.world.countries, onPick = { vm.setHome(it) })
            } else {
                MainScaffold(vm, s)
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
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
                title = { Text("VisaVole") },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Change passport") },
                                onClick = {
                                    menu = false
                                    selected = null
                                    vm.changePassport()
                                },
                            )
                        }
                    }
                },
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
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> MapTab(s, selected, { selected = it })
                else -> DocumentsScreen(vm, s)
            }
        }
    }
}

@Composable
private fun MapTab(s: AppState.Ready, selected: String?, onSelect: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, s.world) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else s.world.countries.values
            .filter { it.name.lowercase().contains(q) || it.iso2.lowercase() == q }
            .sortedBy { it.name }
            .take(20)
    }
    val pick: (String?) -> Unit = { iso -> query = ""; onSelect(iso) }
    val density = LocalDensity.current
    var searchBarH by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { searchBarH = Dp(it.height / density.density) },
            ) {
                SearchBar(query, { query = it })
            }
            WorldMapCanvas(
                geometry = s.geometry,
                access = s.access,
                selected = selected,
                onCountryTap = pick,
                homeCountries = s.homeCountries,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            val selName = selected?.let { s.world.countries[it]?.name }
            if (selected != null && selName != null) {
                CountryDetailCard(
                    countryName = selName,
                    access = s.access[selected],
                    breakdown = AccessModel.breakdownFor(selected, s.docs, s.world),
                    onDismiss = { pick(null) },
                )
            } else {
                LegendRow(s.access.values.groupBy { it.level }.mapValues { it.value.size })
            }
        }
        if (query.isNotBlank() && matches.isNotEmpty()) {
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .offset(y = searchBarH)
                    .heightIn(max = 240.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    matches.forEach { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pick(c.iso2) }
                                .padding(vertical = 10.dp),
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

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun LegendRow(counts: Map<AccessLevel, Int>) {
    val levels = listOf(
        AccessLevel.FREEDOM, AccessLevel.RESIDENCE, AccessLevel.VISA_FREE, AccessLevel.COVERED,
        AccessLevel.ETA, AccessLevel.E_VISA, AccessLevel.VISA_REQUIRED, AccessLevel.REFUSED,
    )
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
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
                Text("Your country", style = MaterialTheme.typography.labelSmall)
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
