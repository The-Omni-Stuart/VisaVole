package com.cbkres.visavole.ui.components.statuscard

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.TripModel

/**
 * The focus state of the tab-top allowance display (the card row [StatusCard] renders), shared by
 * the Trips tab (zone allowances) and the Documents tab (document expiries): which allowance the
 * user tapped in the switcher, with the toggle and dead-key rules both tabs behave by.
 */
@Stable
internal class AllowanceFocus {
    private var focusedKey by mutableStateOf<String?>(null)

    /** Tapping a switcher card focuses it; re-tapping the focused one falls back to the primary. */
    fun toggle(key: String) {
        focusedKey = if (focusedKey == key) null else key
    }

    /** The key to show focused: honored only while the tapped card is gone. A focused allowance that
     *  is deleted or lapsed falls back to null (the primary) instead of leaving a dead key behind. */
    fun effectiveFocusKey(allowances: List<AllowanceSnapshot>): String? =
        focusedKey?.takeIf { key -> allowances.any { it.key == key } }

    /** The allowance the display shows: the focused one while it exists, else [TripModel]'s urgency
     *  pick (the same rule both tabs' top displays use). */
    fun primaryAllowance(allowances: List<AllowanceSnapshot>): AllowanceSnapshot? =
        TripModel.primaryAllowance(allowances, effectiveFocusKey(allowances))
}
