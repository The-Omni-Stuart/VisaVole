@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cbkres.visavole

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Catches: the app's core flow — tab navigation, the add-trip dialog, the stop editor
 *  (country picker + auto document selection), the ongoing-trip confirm — and the trip card
 *  never rendering. A single home-country stop with today's arrival is the guard-free path: no
 *  access warning, no warnings dialog, only the "No final departure" confirm. */
@RunWith(AndroidJUnit4::class)
class TripsTabSmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val testRules: TestRule = RuleChain.outerRule(FreshInstallRule()).around(composeRule)

    @Test
    fun addTripWithOneStopRendersTripCard() {
        composeRule.onboardingPickGermany()
        composeRule.onboardingFinishWithSkip()

        composeRule.clickNavTab("Trips")
        composeRule.waitForAnyText("No trips yet. Add your first trip to start tracking allowances.")

        composeRule.onNode(hasText("Add trip")).performClick()
        composeRule.waitUntilExactlyOneExists(hasText("No stops yet."), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("Add stop")).performClick()

        // The label-merged editable field — the note field behind it has another label.
        composeRule.waitUntilExactlyOneExists(hasText("Country"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("Country") and hasSetTextAction()).performTextInput("Germany")
        composeRule.waitUntilExactlyOneExists(hasText("DE"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("DE")).performClick()
        // The open keyboard can push the Done button below the dialog's scroll fold.
        composeRule.onNode(hasText("Done")).performScrollTo().performClick()

        // The stop row back inside the Add-trip dialog: country title, then the document on
        // its own line. Exact match — "Passport · Germany" also contains the country name.
        composeRule.waitUntilExactlyOneExists(hasText("Germany", substring = false), STEP_TIMEOUT_MS)
        composeRule.waitUntilExactlyOneExists(hasText("Passport · Germany"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("Add")).performClick()

        composeRule.waitUntilExactlyOneExists(hasText("No final departure"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("Done")).performClick()

        // Trip card: the status pill ("Ongoing" — arrival today, departure open) + the title.
        composeRule.waitForAnyText("Ongoing", "Germany")
    }
}
