package com.cbkres.visavole.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    // A theme switch otherwise repaints every surface in one frame; easing the core roles
    // makes Auto/Light/Dark read as a transition. Fixed-role colours snap — nothing in the
    // app consumes them.
    val duration = if (reducedMotion) 0 else 300
    val animatedScheme = colorScheme.animateRoles(duration)
    // Expose the *effective* theme (which may be forced light/dark via the Settings override) so
    // every `isSystemInDarkTheme()` call below the theme reflects what the user is actually seeing
    // — not just the OS setting. The per-theme status colours key off this.
    CompositionLocalProvider(LocalEffectiveDarkTheme provides darkTheme, LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = animatedScheme,
            typography = Typography,
            content = content
        )
    }
}

/** Eases the core colour roles over [durationMillis]; fixed-role colours keep the target's value. */
@Composable
private fun ColorScheme.animateRoles(durationMillis: Int): ColorScheme {
    val s = this
    @Composable
    fun anim(label: String, target: Color): Color =
        animateColorAsState(target, tween(durationMillis), label = label).value
    return s.copy(
        primary = anim("primary", s.primary),
        onPrimary = anim("onPrimary", s.onPrimary),
        primaryContainer = anim("primaryContainer", s.primaryContainer),
        onPrimaryContainer = anim("onPrimaryContainer", s.onPrimaryContainer),
        inversePrimary = anim("inversePrimary", s.inversePrimary),
        secondary = anim("secondary", s.secondary),
        onSecondary = anim("onSecondary", s.onSecondary),
        secondaryContainer = anim("secondaryContainer", s.secondaryContainer),
        onSecondaryContainer = anim("onSecondaryContainer", s.onSecondaryContainer),
        tertiary = anim("tertiary", s.tertiary),
        onTertiary = anim("onTertiary", s.onTertiary),
        tertiaryContainer = anim("tertiaryContainer", s.tertiaryContainer),
        onTertiaryContainer = anim("onTertiaryContainer", s.onTertiaryContainer),
        error = anim("error", s.error),
        onError = anim("onError", s.onError),
        errorContainer = anim("errorContainer", s.errorContainer),
        onErrorContainer = anim("onErrorContainer", s.onErrorContainer),
        background = anim("background", s.background),
        onBackground = anim("onBackground", s.onBackground),
        surface = anim("surface", s.surface),
        onSurface = anim("onSurface", s.onSurface),
        surfaceVariant = anim("surfaceVariant", s.surfaceVariant),
        onSurfaceVariant = anim("onSurfaceVariant", s.onSurfaceVariant),
        surfaceDim = anim("surfaceDim", s.surfaceDim),
        surfaceBright = anim("surfaceBright", s.surfaceBright),
        surfaceContainerLowest = anim("surfaceContainerLowest", s.surfaceContainerLowest),
        surfaceContainerLow = anim("surfaceContainerLow", s.surfaceContainerLow),
        surfaceContainer = anim("surfaceContainer", s.surfaceContainer),
        surfaceContainerHigh = anim("surfaceContainerHigh", s.surfaceContainerHigh),
        surfaceContainerHighest = anim("surfaceContainerHighest", s.surfaceContainerHighest),
        outline = anim("outline", s.outline),
        outlineVariant = anim("outlineVariant", s.outlineVariant),
        inverseSurface = anim("inverseSurface", s.inverseSurface),
        inverseOnSurface = anim("inverseOnSurface", s.inverseOnSurface),
        scrim = anim("scrim", s.scrim),
    )
}

private fun Context.animatorScale(): Float =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** The detail card's silhouette — rounded top, flush bottom. The screen clips the card's
 *  envelope with it and the card surface draws with it; one shape so they can never diverge. */
val detailCardShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
