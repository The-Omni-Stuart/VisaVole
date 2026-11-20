package com.cbkres.visavole.ui.app

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.screens.ErrorScreen
import com.cbkres.visavole.ui.screens.LoadingScreen
import com.cbkres.visavole.ui.screens.onboarding.OnboardingScreen
import com.cbkres.visavole.ui.screens.settings.SettingsScreen
import com.cbkres.visavole.ui.theme.ThemePrefs

internal enum class RootScreen { ONBOARDING, SETTINGS, MAIN }

internal fun rootScreen(homeCountriesEmpty: Boolean, settingsOpen: Boolean): RootScreen = when {
    settingsOpen -> RootScreen.SETTINGS
    homeCountriesEmpty -> RootScreen.ONBOARDING
    else -> RootScreen.MAIN
}

@Composable
internal fun VisaVoleApp(
    themeMode: ThemePrefs.Mode,
    dynamicColor: Boolean,
    onThemeModeChanged: (ThemePrefs.Mode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: AccessViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app),
    )
    val state by vm.state.collectAsState(initial = AppState.Loading)
    // An app left open across UTC midnight must show today's numbers, not yesterday's: the VM
    // re-reads its `today` on every resume and republishes only when the date actually rolled.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _: LifecycleOwner, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    when (val s = state) {
        AppState.Loading -> LoadingScreen()
        is AppState.Failed -> ErrorScreen(s.message) { vm.retryLoad() }
        is AppState.Ready -> {
            var onboardingSettings by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = onboardingSettings) { onboardingSettings = false }
            when (rootScreen(s.homeCountries.isEmpty(), onboardingSettings)) {
                RootScreen.ONBOARDING -> {
                    Box(Modifier.fillMaxSize()) {
                        OnboardingScreen(countries = s.world.countries, today = s.today, onAddPassport = { iso, vfrom, expiry -> vm.addOnboardingPassport(iso, vfrom, expiry) })
                        IconButton(
                            onClick = { onboardingSettings = true },
                            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                        ) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    }
                }
                RootScreen.SETTINGS -> {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onboardingSettings = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Text("Settings", style = MaterialTheme.typography.titleLarge)
                        }
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
                RootScreen.MAIN -> MainScaffold(
                    vm,
                    s,
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    onThemeModeChanged = onThemeModeChanged,
                    onDynamicColorChanged = onDynamicColorChanged,
                )
            }
        }
    }
}