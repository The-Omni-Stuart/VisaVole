package com.cbkres.visavole.ui.screens.map

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.ui.ringColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The WCAG AA pin for the palette text columns: [LevelPalette.tone] (light theme) and
 * [LevelPalette.darkTone] (dark theme) are painted as text — detail-card chips, breakdown rows,
 * document badges — so each must clear 4.5:1 on every real surface colour those components use
 * (the scheme's surface tiers). [LevelPalette.fill] is deliberately not tested: canvas fills are
 * graphics, which only need 3:1.
 */
class MapColorsTest {

    private fun relativeLuminance(c: Color): Double {
        fun lin(v: Float): Double =
            if (v <= 0.03928f) v / 12.92 else ((v.toDouble() + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    /** WCAG contrast ratio: (lighter+0.05)/(darker+0.05), so white-on-black is exactly 21.0. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = if (la >= lb) la else lb
        val lo = if (la >= lb) lb else la
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun contrastHelperMatchesTheHandComputedWhiteOnBlack() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 1e-9)
    }

    @Test fun lightTextTonesClearAaOnEveryLightSurface() {
        val scheme = lightColorScheme()
        val surfaces = listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh)
        for ((level, palette) in LEVEL_PALETTES) {
            val worst = surfaces.minOf { contrastRatio(palette.tone, it) }
            assertTrue("$level light tone fails AA ($worst)", worst >= 4.5)
        }
        val unknownWorst = surfaces.minOf { contrastRatio(statusTone(null, isDark = false), it) }
        assertTrue("UNKNOWN text tone fails AA ($unknownWorst)", unknownWorst >= 4.5)
    }

    @Test fun darkTextTonesClearAaOnEveryDarkSurface() {
        val scheme = darkColorScheme()
        val surfaces = listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh)
        for ((level, palette) in LEVEL_PALETTES) {
            val worst = surfaces.minOf { contrastRatio(palette.darkTone, it) }
            assertTrue("$level dark tone fails AA ($worst)", worst >= 4.5)
        }
        val unknownWorst = surfaces.minOf { contrastRatio(statusTone(null, isDark = true), it) }
        assertTrue("UNKNOWN dark tone fails AA ($unknownWorst)", unknownWorst >= 4.5)
    }

    /**
     * The allowance ring's colour is also painted as the summary text, so the neutral state must
     * return a text tone, not the canvas fill.
     */
    @Test fun ringColorForAnUnknownAllowanceIsAnAaTextTone() {
        val light = AllowanceSnapshot("stay:x", "Somewhere", null, AllowanceKind.ROLLING, AllowanceStatus.UNKNOWN)
        val lightScheme = lightColorScheme()
        val lightSurfaces = listOf(lightScheme.surface, lightScheme.surfaceContainer, lightScheme.surfaceContainerHigh)
        val lightWorst = lightSurfaces.minOf { contrastRatio(ringColor(light, isDark = false), it) }
        assertTrue("ringColor(UNKNOWN) light fails AA ($lightWorst)", lightWorst >= 4.5)

        val darkScheme = darkColorScheme()
        val darkSurfaces = listOf(darkScheme.surface, darkScheme.surfaceContainer, darkScheme.surfaceContainerHigh)
        val darkWorst = darkSurfaces.minOf { contrastRatio(ringColor(light, isDark = true), it) }
        assertTrue("ringColor(UNKNOWN) dark fails AA ($darkWorst)", darkWorst >= 4.5)
    }

    /** The home-country and own-visa special cases are text too, on both themes. */
    @Test fun homeAndOwnVisaTextTonesClearAaOnEverySurface() {
        val lightScheme = lightColorScheme()
        val lightSurfaces = listOf(lightScheme.surface, lightScheme.surfaceContainer, lightScheme.surfaceContainerHigh)
        val homeLight = lightSurfaces.minOf { contrastRatio(statusTone(null, isDark = false, isHome = true), it) }
        assertTrue("home light tone fails AA ($homeLight)", homeLight >= 4.5)
        val ownLight = lightSurfaces.minOf { contrastRatio(statusTone(AccessLevel.COVERED, isDark = false, isOwn = true), it) }
        assertTrue("own-visa light tone fails AA ($ownLight)", ownLight >= 4.5)
        val resLight = lightSurfaces.minOf { contrastRatio(statusTone(AccessLevel.FREEDOM, isDark = false, isResidence = true), it) }
        assertTrue("residence light tone fails AA ($resLight)", resLight >= 4.5)

        val darkScheme = darkColorScheme()
        val darkSurfaces = listOf(darkScheme.surface, darkScheme.surfaceContainer, darkScheme.surfaceContainerHigh)
        val homeDark = darkSurfaces.minOf { contrastRatio(statusTone(null, isDark = true, isHome = true), it) }
        assertTrue("home dark tone fails AA ($homeDark)", homeDark >= 4.5)
        val ownDark = darkSurfaces.minOf { contrastRatio(statusTone(AccessLevel.COVERED, isDark = true, isOwn = true), it) }
        assertTrue("own-visa dark tone fails AA ($ownDark)", ownDark >= 4.5)
        val resDark = darkSurfaces.minOf { contrastRatio(statusTone(AccessLevel.FREEDOM, isDark = true, isResidence = true), it) }
        assertTrue("residence dark tone fails AA ($resDark)", resDark >= 4.5)
    }

    /** A residence permit paints teal even where a passport's freedom of movement outranks it. */
    @Test fun residencePermitOutranksFreedomOfMovementOnTheMap() {
        assertEquals(RESIDENCE_FILL, mapFill(AccessLevel.FREEDOM, isResidence = true))
        // Home still wins: a country you are a citizen of is blue, not teal.
        assertEquals(HOME, mapFill(AccessLevel.FREEDOM, isHome = true, isResidence = true))
        // Without the permit the level paints as before.
        assertEquals(colorFor(AccessLevel.FREEDOM), mapFill(AccessLevel.FREEDOM))
    }
}
