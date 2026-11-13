@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cbkres.visavole

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput

// Onboarding only renders once the 7.4 MB bundled DB copy, the world query and the geometry
// parse finish — the slowest step in the app, so cold starts get the long budget.
internal const val LOAD_TIMEOUT_MS = 180_000L

// Budget for in-app steps (dialogs, tab switches, the post-onboarding world reload).
internal const val STEP_TIMEOUT_MS = 30_000L

/** Waits until at least one node with each of [texts] exists (cards can share a word, so no
 *  "exactly one" here). */
internal fun ComposeTestRule.waitForAnyText(vararg texts: String, timeoutMillis: Long = STEP_TIMEOUT_MS) {
    texts.forEach { waitUntilAtLeastOneExists(hasText(it), timeoutMillis) }
}

/** The one editable field on screen — the app's text fields also carry their label as merged
 *  text, but this avoids depending on that. */
internal fun ComposeTestRule.editableField() = onNode(hasSetTextAction())

/** First-run onboarding up to the validity dialog: search Germany, tap its "DE" ISO tag (the
 *  search field itself holds "Germany", so tapping that text would match two nodes). */
internal fun ComposeTestRule.onboardingPickGermany() {
    waitUntilExactlyOneExists(hasText("Welcome to Visa Vole"), LOAD_TIMEOUT_MS)
    editableField().performTextInput("Germany")
    waitUntilExactlyOneExists(hasText("DE"), STEP_TIMEOUT_MS)
    onNode(hasText("DE")).performClick()
    waitUntilExactlyOneExists(hasText("Skip"), STEP_TIMEOUT_MS)
}

/** Skips the validity dates (both-or-neither flow) and waits for the scaffold, which lands
 *  only after the home-country world reload publishes. */
internal fun ComposeTestRule.onboardingFinishWithSkip() {
    onNode(hasText("Skip")).performClick()
    waitForAnyText("Visa Vole", timeoutMillis = LOAD_TIMEOUT_MS)
}
