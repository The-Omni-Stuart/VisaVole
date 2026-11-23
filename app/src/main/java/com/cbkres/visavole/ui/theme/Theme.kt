package com.cbkres.visavole.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cbkres.visavole.ui.LocalReducedMotion
import com.cbkres.visavole.ui.reducedMotionFromScale
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme

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
internal fun Visa_VoleTheme(
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

    // The OS animation scale, sampled at the theme root; 0 means the user asked for no motion.
    // Re-read on every resume so toggling the setting lands without restarting the app.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val animatorScale = remember { mutableFloatStateOf(context.animatorScale()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) animatorScale.floatValue = context.animatorScale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val reducedMotion = reducedMotionFromScale(animatorScale.floatValue)
    // Expose the *effective* theme (which may be forced light/dark via the Settings override) so
    // every `isSystemInDarkTheme()` call below the theme reflects what the user is actually seeing
    // — not just the OS setting. The per-theme status colours key off this.
    CompositionLocalProvider(LocalEffectiveDarkTheme provides darkTheme, LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

private fun Context.animatorScale(): Float =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
