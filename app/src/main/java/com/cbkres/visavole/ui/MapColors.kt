package com.cbkres.visavole.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.cbkres.visavole.domain.AccessLevel

/**
 * The effective dark/light theme the user is actually seeing. It may be forced light/dark via the
 * Settings override, so it is not necessarily the OS setting — provided once at the root by
 * `Visa_VoleTheme` and read by the per-theme status colours (P1-12) instead of
 * `isSystemInDarkTheme()`, so a forced theme drives the right contrast set.
 */
val LocalEffectiveDarkTheme: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

// Ocean + unknown-land base.
val OCEAN = Color(0xFF0B1F2A)
// Light-theme ocean (used when the effective theme is light, so the map doesn't stay a dark
// island under a light app). The bright country fills and the navy [LAND_STROKE] borders both
// still read on this lighter water.
val OCEAN_LIGHT = Color(0xFFDCE7EF)
// Borders: a solid navy line (matches the sea) so the bright fills read as separate
// countries. Drawn in a second pass on top of every fill so shared borders stay crisp.
val LAND_STROKE = Color(0xFF0B1F2A)

// The traveller's home country (citizenship) — special-cased on the map, not a map level.
val HOME = Color(0xFF0460EE)

// A short-term visa's own country (the doc's home / issuing country) — darker than the lighter
// purple of the rest of the bloc the visa unlocks, so the primary destination stands out.
val COVERED_OWN = Color(0xFFA12AFA)

// A uniform residence shade.
val RESIDENCE_FILL = Color(0xFF14DC9D)
// Dark-theme text variants of the two map special cases (brighter so they keep AA contrast on a
// near-black surface — the base [HOME]/[COVERED_OWN] shades were tuned for light surfaces).
val HOME_DARK = Color(0xFF60A5FA)
val COVERED_OWN_DARK = Color(0xFFC084FC)

/**
 * The single severity → colour table: one entry per access level with three renderings. [fill] is
 * the bright canvas choropleth (same in both themes — it sits on the ocean, a large graphic that
 * only needs 3:1); [tone] is the darker shade for labels/chips on a *light* surface; [darkTone]
 * is the brighter shade for the same labels/chips on a *dark* surface (P1-12 — the old single
 * shade failed WCAG AA on near-black). Every level colour in the app (map fills, status pills,
 * detail-card text) derives from this table — best (blue / teal / green) -> worst (red), worst-first.
 */
private data class LevelPalette(val fill: Color, val tone: Color, val darkTone: Color)

private val LEVEL_PALETTES: Map<AccessLevel, LevelPalette> = mapOf(
    AccessLevel.FREEDOM to LevelPalette(Color(0xFF5BDCEC), Color(0xFF00B8D4), Color(0xFF67E8F9)),       // cyan — bloc citizenship
    AccessLevel.RESIDENCE to LevelPalette(RESIDENCE_FILL, Color(0xFF00C48C), Color(0xFF6EE7B7)),         // teal — residence permit
    AccessLevel.VISA_FREE to LevelPalette(Color(0xFF44F17A), Color(0xFF00D26A), Color(0xFF86EFAC)),      // green — visa-free / on arrival, no step needed
    AccessLevel.COVERED to LevelPalette(Color(0xFFBD6AFC), Color(0xFFBD6AFC), Color(0xFFD8B4FE)),        // magenta — covered via a visa's travel bloc
    AccessLevel.ETA to LevelPalette(Color(0xFFB9F04A), Color(0xFF7CB342), Color(0xFFC5E1A5)),            // lime — quick pre-authorisation (eTA/ESTA/ETIAS)
    AccessLevel.E_VISA to LevelPalette(Color(0xFFFFD84A), Color(0xFFCA8A04), Color(0xFFFBBF24)),          // yellow — e-visa, apply online
    AccessLevel.VISA_REQUIRED to LevelPalette(Color(0xFF94A3B8), Color(0xFF475569), Color(0xFF94A3B8)),  // grey — embassy visit
    AccessLevel.REFUSED to LevelPalette(Color(0xFFFB1604), Color(0xFFFB1604), Color(0xFFFCA5A5)),         // red — no entry
)

// The single neutral slate for "no data / unknown" on status surfaces (allowance rings, no-data
// country fills, and their label tones) — one value so the ring and the map can't drift apart.
val NO_DATA = Color(0xFF64748B)
private val NO_DATA_PALETTE = LevelPalette(NO_DATA, NO_DATA, Color(0xFF94A3B8))        // slate — no data

private fun paletteFor(level: AccessLevel?): LevelPalette =
    level?.let { LEVEL_PALETTES[it] } ?: NO_DATA_PALETTE

/** The bright canvas choropleth for a level; home / own-visa overrides go through [mapFill]. */
fun colorFor(level: AccessLevel?): Color = paletteFor(level).fill

/**
 * The one place the map's special cases are decided: the traveller's home country and a
 * short-term visa's own country override the level palette; everything else is the level's
 * fill. [isOwn] means the country is one of the traveller's own-visa countries.
 */
fun mapFill(level: AccessLevel?, isHome: Boolean = false, isOwn: Boolean = false): Color = when {
    isHome -> HOME
    level == AccessLevel.COVERED && isOwn -> COVERED_OWN
    else -> colorFor(level)
}

/**
 * Label/pill tone for a level on theme-driven surfaces — same special cases as [mapFill], but the
 * shade is theme-aware (P1-12): [isDark] picks the brighter [LevelPalette.darkTone] on a near-black
 * surface so the text keeps AA contrast, and the darker [LevelPalette.tone] on a light one.
 */
fun statusTone(level: AccessLevel?, isDark: Boolean, isHome: Boolean = false, isOwn: Boolean = false): Color = when {
    isHome -> if (isDark) HOME_DARK else HOME
    level == AccessLevel.COVERED && isOwn -> if (isDark) COVERED_OWN_DARK else COVERED_OWN
    else -> paletteFor(level).let { if (isDark) it.darkTone else it.tone }
}

// Accessible text/pill tones derived from the map palette. The bright map fills are for the
// canvas; these darker shades are for labels and tonal chips on theme-driven surfaces.
const val STATUS_CHIP_ALPHA = 0.16f
// The standard dimming for an inactive/disabled control — e.g. the delete button while a doc
// is in use, or a non-primary passport star. One shared value so every "greyed out" element
// reads with the same tone.
const val DIM_ALPHA = 0.35f

/**
 * The severity levels every status colour in the app collapses to: allowance rings, guard
 * findings, document expiry pills and entry types all decide a [Severity], and [severityColor]
 * is the only place a severity becomes a concrete shade.
 */
enum class Severity { OK, CAUTION, WARN, BAD }

/**
 * The one place a severity becomes a concrete text shade (P1-12). Two sets, chosen by [isDark]:
 * the darker Material -700 shades on light surfaces, the brighter -400 shades on near-black
 * surfaces, so the text keeps ≥4.5:1 in both themes. (The old single amber `#CA8A04` failed AA as
 * light-theme text; it now only appears in the dark set, where it passes.)
 */
fun severityColor(severity: Severity, isDark: Boolean): Color = if (isDark) {
    when (severity) {
        Severity.OK -> Color(0xFF4ADE80)      // green-400
        Severity.CAUTION -> Color(0xFFFBBF24) // amber-400
        Severity.WARN -> Color(0xFFFB923C)    // orange-400
        Severity.BAD -> Color(0xFFF87171)     // red-400
    }
} else {
    when (severity) {
        Severity.OK -> Color(0xFF15803D)      // green-700
        Severity.CAUTION -> Color(0xFFA16207) // amber-700
        Severity.WARN -> Color(0xFFC2410C)    // orange-700
        Severity.BAD -> Color(0xFFB91C1C)     // red-700
    }
}

/**
 * Finding codes that were DANGERs before the unified guard (savable, per Q1): they stay WARN in
 * the engine but are rendered in the danger colour so the visual weight is unchanged.
 */
val SAVABLE_DANGER_CODES = setOf(
    "trip.allowance.exceeded",
    "trip.entries.over",
    "trip.access.none",
    "trip.access.blocked",
    "trip.passport.expiringSoon3",
)
