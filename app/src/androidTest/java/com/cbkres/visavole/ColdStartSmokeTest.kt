@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cbkres.visavole

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Catches: a cold-start crash, or a failed bundled-data/geometry load surfacing as the error
 *  screen instead of the first-run UI. */
@RunWith(AndroidJUnit4::class)
class ColdStartSmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val testRules: TestRule = RuleChain.outerRule(FreshInstallRule()).around(composeRule)

    @Test
    fun freshInstallShowsOnboarding() {
        composeRule.waitUntilExactlyOneExists(hasText("Welcome to Visa Vole"), LOAD_TIMEOUT_MS)
        composeRule.onAllNodesWithText("Something went wrong").assertCountEquals(0)
    }
}
