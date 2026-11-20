package com.cbkres.visavole.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PillTextTest {

    @Test
    fun numbersAndParentheticalsStayTogether() {
        assertEquals("Expires in 0\u00a0days", tidyPillText("Expires in 0 days"))
        assertEquals("74\u00a0days left", tidyPillText("74 days left"))
        assertEquals("Valid for 1138\u00a0days", tidyPillText("Valid for 1138 days"))
        assertEquals("Replaced by Foo from 2026-01-01", tidyPillText("Replaced by Foo from 2026-01-01"))
    }
}
