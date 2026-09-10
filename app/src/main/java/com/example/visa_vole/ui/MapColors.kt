package com.example.visa_vole.ui

import androidx.compose.ui.graphics.Color
import com.example.visa_vole.domain.AccessLevel
import com.example.visa_vole.domain.ResidenceClass

// Ocean + unknown-land base.
val OCEAN = Color(0xFF0B1F2A)
// Borders: a solid navy line (matches the sea) so the bright pastel fills read as separate
// countries. Drawn in a second pass on top of every fill so shared borders stay crisp.
val LAND_STROKE = Color(0xFF0B1F2A)

// The traveller's home country (citizenship) — special-cased on the map, not a map level.
val HOME = Color(0xFF3B82F6)

// A short-term visa's own country (the doc's home / issuing country) — darker than the lighter
// purple of the rest of the bloc the visa unlocks, so the primary destination stands out.
val COVERED_OWN = Color(0xFF7C3AED)

// Residence shades, lightest (temporary) to deepest (permanent).
val RESIDENCE_TEMPORARY = Color(0xFF99F6E4)
val RESIDENCE_LONG_TERM = Color(0xFF2DD4BF)
val RESIDENCE_PERMANENT = Color(0xFF0D9488)

/** The map shade for a RESIDENCE country by its document's residence class. */
fun residenceColor(residenceClass: ResidenceClass?): Color = when (residenceClass) {
    ResidenceClass.TEMPORARY -> RESIDENCE_TEMPORARY
    ResidenceClass.LONG_TERM -> RESIDENCE_LONG_TERM
    ResidenceClass.PERMANENT -> RESIDENCE_PERMANENT
    null -> RESIDENCE_LONG_TERM
}

// Choropleth palette, best (blue / teal / green) -> worst (red). Home is drawn separately.
fun colorFor(level: AccessLevel?): Color = when (level) {
    AccessLevel.FREEDOM -> Color(0xFF93C5FD)       // light blue — bloc citizenship
    AccessLevel.RESIDENCE -> Color(0xFF2DD4BF)     // teal — residence permit
    AccessLevel.VISA_FREE -> Color(0xFF4ADE80)     // green — visa-free / on arrival, no step needed
    AccessLevel.COVERED -> Color(0xFFC4B5FD)       // light purple — covered via a visa's travel bloc
    AccessLevel.ETA -> Color(0xFFFACC15)           // yellow — quick pre-authorisation (eTA/ESTA/ETIAS)
    AccessLevel.E_VISA -> Color(0xFFF59E0B)        // orange — e-visa, apply online
    AccessLevel.VISA_REQUIRED -> Color(0xFF94A3B8) // gray — embassy visit
    AccessLevel.REFUSED -> Color(0xFFF87171)       // red — no entry
    AccessLevel.UNKNOWN, null -> Color(0xFF334155) // slate — no data
}
