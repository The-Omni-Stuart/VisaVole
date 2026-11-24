@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cbkres.visavole

import androidx.compose.ui.test.hasContentDescription
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

/** Catches: the Documents tab's add→delete round trip — the add-document dialog (country search,
 *  enabled Add), the new card rendering, and the delete confirm actually removing the card. */
@RunWith(AndroidJUnit4::class)
class DocumentsTabSmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val testRules: TestRule = RuleChain.outerRule(FreshInstallRule()).around(composeRule)

    @Test
    fun addThenDeleteDocumentRoundTrips() {
        composeRule.onboardingPickGermany()
        composeRule.onboardingFinishWithSkip()

        composeRule.clickNavTab("Documents")
        composeRule.waitForAnyText("Add document")

        // Residence is the dialog's default type, so the country search is the only input.
        composeRule.onNode(hasText("Add document")).performClick()
        composeRule.waitUntilExactlyOneExists(hasText("Residence country"), STEP_TIMEOUT_MS)
        composeRule.editableField().performTextInput("France")
        composeRule.waitUntilExactlyOneExists(hasText("FR"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("FR")).performClick()
        composeRule.onNode(hasText("Add", substring = false)).performScrollTo().performClick()

        composeRule.waitUntilExactlyOneExists(hasText("France"), STEP_TIMEOUT_MS)
        // Germany's card is first in the list, so the new card's trash is the second.
        composeRule.onAllNodes(hasContentDescription("Delete"))[1].performClick()
        composeRule.waitUntilExactlyOneExists(hasText("Delete document"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("Delete", substring = false)).performClick()

        composeRule.waitUntilExactlyOneExists(hasText("Germany"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("France")).assertDoesNotExist()
    }
}
