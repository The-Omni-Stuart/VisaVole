@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cbkres.visavole

import android.util.Log
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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

/** Click the bottom-nav tab labelled [label]: tab labels share their wording with the matching
 *  screen's header, so the tab's selectable role — not the bare text — must pick it. */
internal fun ComposeTestRule.clickNavTab(label: String) {
    onNode(hasText(label) and isSelectable()).performClick()
}

/** Let a dialog's pop-in finish before injecting a click: mid-animation the button's on-screen
 *  centre lags its layout bounds (0.92 scale-in around the dialog centre), so a fast follow-up
 *  tap can miss. Advancing the test clock runs the tween deterministically. */
internal fun ComposeTestRule.settlePop() = mainClock.advanceTimeBy(300)

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

/** The node's text tree in logcat under VisaVole, for CI failure triage — printToPolish left
 *  the compose-test API in 1.10, and the CI dump greps this tag. */
internal fun logNode(node: SemanticsNode, depth: Int) {
    val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        ?: node.config.getOrNull(SemanticsProperties.Role)?.toString()
        ?: "-"
    Log.i("VisaVole", " ".repeat(depth * 2) + label)
    node.children.forEach { logNode(it, depth + 1) }
}
