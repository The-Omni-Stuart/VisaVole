@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cbkres.visavole

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnboardingFlowSmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val testRules: TestRule = RuleChain.outerRule(FreshInstallRule()).around(composeRule)

    /** Catches: onboarding's country picker → chained date pickers → scaffold handoff breaking,
     *  and the new passport not reaching the documents list. Anchored on the real labels:
     *  "Choose dates"/"Skip" (validity dialog), "Next"/"Done" (the two VisaDatePickerDialog
     *  confirm labels), "Documents" (nav), "Passport"/"Germany" (document card). */
    @Test
    fun onboardingWithDefaultDatesReachesDocuments() {
        composeRule.onboardingPickGermany()
        composeRule.onNode(hasText("Choose dates")).performClick()
        // Both pickers keep a legal default selection (valid-from = today, expiry = the earliest
        // legal date), so confirming without touching the calendar is deterministic.
        composeRule.waitUntilExactlyOneExists(hasText("Next"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("Next")).performClick()
        composeRule.waitUntilExactlyOneExists(hasText("Done"), STEP_TIMEOUT_MS)
        composeRule.onNode(hasText("Done")).performClick()
        // The passport triggers a home-country world reload; the scaffold's top bar lands after it.
        composeRule.waitForAnyText("Visa Vole", timeoutMillis = LOAD_TIMEOUT_MS)

        composeRule.clickNavTab("Documents")
        composeRule.waitForAnyText("Passport", "Germany")
        composeRule.onAllNodesWithText("Something went wrong").assertCountEquals(0)
    }

    /** Catches: the persist path not reaching disk, and the app not coming back to the main UI
     *  after the activity is torn down and restored. */
    @Test
    fun addedPassportSurvivesRecreate() {
        composeRule.onboardingPickGermany()
        composeRule.onboardingFinishWithSkip()

        // The state write is an async queue — poll the real file instead of assuming it landed.
        val stateFile = File(composeRule.activity.applicationContext.filesDir, "visavole_state.json")
        composeRule.waitUntil(STEP_TIMEOUT_MS) {
            val text = runCatching { stateFile.readText() }.getOrDefault("")
            text.contains("\"kind\":\"passport\"") && text.contains("\"iso2\":\"DE\"")
        }

        composeRule.clickNavTab("Documents")
        composeRule.waitForAnyText("Passport")

        // smoke: recreate() keeps the ViewModel, so this proves the saveable-restore + no-crash
        // path and the on-disk write above; a real process kill is out of reach from the test
        // process and the decode side is JVM-covered (StateCodecTest, VisaStateStoreTest).
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForAnyText("Visa Vole", timeoutMillis = LOAD_TIMEOUT_MS)
        composeRule.clickNavTab("Documents")
        composeRule.waitForAnyText("Passport", "Germany")
    }
}
