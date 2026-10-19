package com.cbkres.visavole.ui.app

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.AppState
import com.cbkres.visavole.ui.screens.ErrorScreen
import com.cbkres.visavole.ui.screens.LoadingScreen
import com.cbkres.visavole.ui.screens.onboarding.OnboardingScreen
import com.cbkres.visavole.ui.theme.ThemePrefs

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
    when (val s = state) {
        AppState.Loading -> LoadingScreen()
        is AppState.Failed -> ErrorScreen(s.message) { vm.retryLoad() }
        is AppState.Ready -> {
            if (s.homeCountries.isEmpty()) {
                OnboardingScreen(countries = s.world.countries, today = s.today, onAddPassport = { iso, vfrom, expiry -> vm.addOnboardingPassport(iso, vfrom, expiry) })
            } else {
                MainScaffold(
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