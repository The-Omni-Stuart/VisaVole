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

// Choropleth palette, best (blue / teal / green) -> worst (red). Home is drawn separately.
fun colorFor(level: AccessLevel?): Color = when (level) {
    AccessLevel.FREEDOM -> Color(0xFF5BDCEC)       // cyan — bloc citizenship
    AccessLevel.RESIDENCE -> RESIDENCE_FILL         // teal — residence permit
    AccessLevel.VISA_FREE -> Color(0xFF44F17A)     // green — visa-free / on arrival, no step needed
    AccessLevel.COVERED -> Color(0xFFBD6AFC)       // magenta — covered via a visa's travel bloc
    AccessLevel.ETA -> Color(0xFFB9F04A)           // lime — quick pre-authorisation (eTA/ESTA/ETIAS)
    AccessLevel.E_VISA -> Color(0xFFFFD84A)        // yellow — e-visa, apply online
    AccessLevel.VISA_REQUIRED -> Color(0xFF94A3B8) // grey — embassy visit
    AccessLevel.REFUSED -> Color(0xFFFB1604)       // red — no entry
    AccessLevel.UNKNOWN, null -> Color(0xFF334155) // slate — no data
}

// Accessible text/pill tones derived from the map palette. The bright map fills are for the
// canvas; these darker shades are for labels and tonal chips on theme-driven surfaces.
const val STATUS_CHIP_ALPHA = 0.16f
// The standard dimming for an inactive/disabled control — e.g. the delete button while a doc
// is in use, or a non-primary passport star. One shared value so every "greyed out" element
// reads with the same tone.
const val DIM_ALPHA = 0.35f
val STATUS_OK = Color(0xFF15803D)
val STATUS_WARN = Color(0xFFC2410C)
val STATUS_BAD = Color(0xFFB91C1C)

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

fun statusTone(level: AccessLevel?, isHome: Boolean = false, isOwnCovered: Boolean = false): Color = when {
    isHome -> Color(0xFF0460EE)
    level == AccessLevel.FREEDOM -> Color(0xFF00B8D4)
    level == AccessLevel.RESIDENCE -> Color(0xFF00C48C)
    level == AccessLevel.VISA_FREE -> Color(0xFF00D26A)
    level == AccessLevel.COVERED && isOwnCovered -> Color(0xFFA12AFA)
    level == AccessLevel.COVERED -> Color(0xFFBD6AFC)
    level == AccessLevel.ETA -> Color(0xFF7CB342)
    level == AccessLevel.E_VISA -> Color(0xFFCA8A04)
    level == AccessLevel.VISA_REQUIRED -> Color(0xFF475569)
    level == AccessLevel.REFUSED -> Color(0xFFFB1604)
    else -> Color(0xFF334155)
}
