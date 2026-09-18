package com.cbkres.visavole.ui

import androidx.compose.ui.graphics.Color
import com.cbkres.visavole.domain.AccessLevel

// Ocean + unknown-land base.
val OCEAN = Color(0xFF0B1F2A)
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

/**
 * The single severity → colour table: one entry per access level with two renderings. [fill] is
 * the bright canvas choropleth; [tone] is the darker shade for labels and tonal chips on
 * theme-driven surfaces. Every level colour in the app (map fills, status pills, detail-card
 * text) derives from this table — best (blue / teal / green) -> worst (red), worst-first.
 */
private data class LevelPalette(val fill: Color, val tone: Color)

private val LEVEL_PALETTES: Map<AccessLevel, LevelPalette> = mapOf(
    AccessLevel.FREEDOM to LevelPalette(Color(0xFF5BDCEC), Color(0xFF00B8D4)),       // cyan — bloc citizenship
    AccessLevel.RESIDENCE to LevelPalette(RESIDENCE_FILL, Color(0xFF00C48C)),        // teal — residence permit
    AccessLevel.VISA_FREE to LevelPalette(Color(0xFF44F17A), Color(0xFF00D26A)),     // green — visa-free / on arrival, no step needed
    AccessLevel.COVERED to LevelPalette(Color(0xFFBD6AFC), Color(0xFFBD6AFC)),       // magenta — covered via a visa's travel bloc
    AccessLevel.ETA to LevelPalette(Color(0xFFB9F04A), Color(0xFF7CB342)),           // lime — quick pre-authorisation (eTA/ESTA/ETIAS)
    AccessLevel.E_VISA to LevelPalette(Color(0xFFFFD84A), Color(0xFFCA8A04)),        // yellow — e-visa, apply online
    AccessLevel.VISA_REQUIRED to LevelPalette(Color(0xFF94A3B8), Color(0xFF475569)), // grey — embassy visit
    AccessLevel.REFUSED to LevelPalette(Color(0xFFFB1604), Color(0xFFFB1604)),       // red — no entry
)

private val NO_DATA_PALETTE = LevelPalette(Color(0xFF334155), Color(0xFF334155))    // slate — no data

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

/** Label/pill tone for a level on theme-driven surfaces — same special cases as [mapFill]. */
fun statusTone(level: AccessLevel?, isHome: Boolean = false, isOwn: Boolean = false): Color = when {
    isHome -> HOME
    level == AccessLevel.COVERED && isOwn -> COVERED_OWN
    else -> paletteFor(level).tone
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

fun severityColor(severity: Severity): Color = when (severity) {
    Severity.OK -> Color(0xFF15803D)
    Severity.CAUTION -> Color(0xFFCA8A04)
    Severity.WARN -> Color(0xFFC2410C)
    Severity.BAD -> Color(0xFFB91C1C)
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
