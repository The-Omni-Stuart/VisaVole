package com.cbkres.visavole.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RootScreenTest {

    @Test
    fun settingsStayReachableWithNoPassportsAndBackReturnsToOnboarding() {
        assertEquals(RootScreen.ONBOARDING, rootScreen(homeCountriesEmpty = true, settingsOpen = false))
        assertEquals(RootScreen.SETTINGS, rootScreen(homeCountriesEmpty = true, settingsOpen = true))
        assertEquals(RootScreen.MAIN, rootScreen(homeCountriesEmpty = false, settingsOpen = false))
    }
}
