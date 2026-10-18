package com.cbkres.visavole.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.cbkres.visavole.ui.LocalEffectiveDarkTheme

private val DarkColorScheme = darkColorScheme(
    primary = VisaGreenDark,
    secondary = VisaBlueDark,
    tertiary = VisaVioletDark
)

private val LightColorScheme = lightColorScheme(
    primary = VisaGreen,
    secondary = VisaBlue,
    tertiary = VisaViolet
)

@Composable
fun Visa_VoleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Expose the *effective* theme (which may be forced light/dark via the Settings override) so
    // every `isSystemInDarkTheme()` call below the theme reflects what the user is actually seeing
    // — not just the OS setting. The per-theme status colours (P1-12) key off this.
    CompositionLocalProvider(LocalEffectiveDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}