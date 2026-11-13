package com.cbkres.visavole

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cbkres.visavole.data.VisaDb
import com.cbkres.visavole.data.VisaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Catches: the 7.4 MB asset copy failing, the read-only SQLite open breaking, or the bundled
 *  DB's schema drifting from the loader's column contract — none of which the JVM mirror alone
 *  would see, since it reads the file through JDBC on the host. */
@RunWith(AndroidJUnit4::class)
class VisaDbLoadSmokeTest {

    // Force the copy from assets on every run instead of reusing a leftover internal copy.
    @get:Rule
    val fresh = FreshInstallRule()

    @Test
    fun bundledVisaDbLoadsNonEmptyWorld() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val world = VisaRepository(VisaDb(context)).loadWorld(homePassports = setOf("DE"))

        assertTrue("expected the full country list, got ${world.countries.size}", world.countries.size > 150)
        assertEquals("Germany", world.countries["DE"]?.name)
        val deCorridors = world.baseline["DE"]
        assertNotNull("no baseline corridors for DE", deCorridors)
        assertTrue("DE baseline corridors empty", deCorridors!!.isNotEmpty())
        assertTrue(world.regimes.isNotEmpty())
        assertTrue(world.holdings.isNotEmpty())
        assertTrue(world.benefits.values.flatten().isNotEmpty())
        assertTrue(world.stayRules.isNotEmpty())
    }
}
