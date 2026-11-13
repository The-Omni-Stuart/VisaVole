package com.cbkres.visavole

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * Every smoke test must start as a real first launch, and androidx.test 1.3.0's
 * ActivityScenarioRule has no clearPackageData — wipe the mutable app dirs from a rule chained
 * OUTSIDE the activity rule instead, so this runs before the activity launches.
 */
class FreshInstallRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            app.filesDir?.deleteContents()
            app.cacheDir?.deleteContents()
            app.codeCacheDir?.deleteContents()
            app.databaseList()?.forEach(app::deleteDatabase)
            File(app.dataDir, "shared_prefs").listFiles()
                ?.forEach { app.deleteSharedPreferences(it.nameWithoutExtension) }
            base.evaluate()
        }
    }

    private fun File.deleteContents() = listFiles()?.forEach { it.deleteRecursively() }
}
