package com.cbkres.visavole

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import com.cbkres.visavole.ui.app.VisaVoleApp
import com.cbkres.visavole.ui.theme.ThemePrefs
import com.cbkres.visavole.ui.theme.Visa_VoleTheme

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
            val appContext = this
            var themeMode by remember { mutableStateOf(ThemePrefs.getMode(appContext)) }
            var dynamicColor by remember { mutableStateOf(ThemePrefs.getDynamic(appContext)) }
            val darkTheme = when (themeMode) {
                ThemePrefs.Mode.SYSTEM -> isSystemInDarkTheme()
                ThemePrefs.Mode.LIGHT -> false
                ThemePrefs.Mode.DARK -> true
            }
            // Keep the status/nav-bar icon tint in sync with the *effective* theme, so forcing
            // light on a dark-mode system (or vice versa) doesn't leave invisible icons.
            LaunchedEffect(darkTheme) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            Visa_VoleTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                VisaVoleApp (
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    onThemeModeChanged = { m ->
                        themeMode = m
                        ThemePrefs.setMode(appContext, m)
                    },
                    onDynamicColorChanged = { d ->
                        dynamicColor = d
                        ThemePrefs.setDynamic(appContext, d)
                    },
                )
            }
        }
    }
}