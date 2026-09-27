package com.cbkres.visavole.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pins the committed `visa_data.db` asset to its SHA-256 sidecar. The network `updateVisaDb`
 * task is opt-in, so builds and tests run hermetically against the committed asset — this test
 * is the tripwire that catches a stale or hand-edited asset (and, after a manual
 * `./gradlew updateVisaDb`, forces the refreshed pair to be committed together).
 */
class AssetIntegrityTest {

    private val assetDir = File("src/main/assets")

    @Test
    fun committedDatabaseMatchesSha256Sidecar() {
        val db = File(assetDir, "visa_data.db")
        val sidecar = File(assetDir, "visa_data.db.sha256")
        assertTrue("visa_data.db missing at ${db.absolutePath}", db.exists())
        assertTrue("visa_data.db.sha256 missing at ${sidecar.absolutePath}", sidecar.exists())
        assertEquals(
            "committed visa_data.db does not match its SHA-256 sidecar — re-run ./gradlew updateVisaDb and commit both files together",
            sidecar.readText().trim().lowercase(),
            sha256Hex(db),
        )
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
