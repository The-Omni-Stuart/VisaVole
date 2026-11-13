package com.cbkres.visavole.data

import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Tests for the backup export body at its JVM-testable seam — [writeBackupJson], the function
 * [BackupManager.exportTo] writes through. ContentResolver/Uri need a device, so these pin the
 * load-bearing contract: a backup is always the live state handed in, never whatever the state
 * file happens to hold.
 */
class BackupManagerTest {

    private lateinit var dir: File
    private lateinit var stateFile: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: VisaStateStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("visavole-backup").toFile()
        stateFile = File(dir, BackupManager.STATE_FILE_NAME)
        scope = CoroutineScope(Job() + Dispatchers.Default)
        store = VisaStateStore(file = stateFile, scope = scope)
    }

    @After
    fun tearDown() {
        runBlocking {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        dir.setWritable(true)
        dir.deleteRecursively()
    }

    @Test
    fun exportReflectsStateEnqueuedImmediatelyBeforeIt() {
        store.save(oldState)
        assertTrue("the first save never reached disk", awaitUntil { diskState() == oldState })

        store.save(newState) // queued for the async writer, deliberately not awaited
        val backup = ByteArrayOutputStream().also { writeBackupJson(newState, it) }.toString()

        assertEquals(
            "the backup must be the live state, not whatever the state file had reached yet",
            newState,
            StateCodec.decode(backup).state,
        )
    }

    @Test
    fun exportFromBlockedStoreNeverReportsTheStaleFile() {
        // Block the store: an undecodable state file the quarantiner cannot rename or copy away
        // (read-only dir), exactly the stateFileBlocked path where save() refuses for the process.
        stateFile.writeText("{ not valid json")
        assertTrue(dir.setWritable(false))
        assertNull("a quarantine-impossible file must block persisting via a null load", store.load())
        assertTrue("the file must survive a failed quarantine", stateFile.exists())
        assertTrue(dir.setWritable(true))
        // While persisting is blocked, something else pushes a valid but stale state to disk.
        stateFile.writeText(StateCodec.encode(oldState))

        val backup = ByteArrayOutputStream().also { writeBackupJson(newState, it) }.toString()

        assertEquals(
            "a blocked store must still export the live state — never claim the stale file as fresh",
            newState,
            StateCodec.decode(backup).state,
        )
        assertEquals("the export must not have read (or rewritten) the state file", oldState, diskState())
    }

    /** Decodes the state file straight off disk (bypasses load(), which quarantines). */
    private fun diskState(): PersistedState? =
        runCatching { StateCodec.decode(stateFile.readText()).state }.getOrNull()

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private companion object {
        val oldState = PersistedState(
            "doc-old",
            listOf(Document("doc-old", "Old passport", DocKind.Passport("CZ"))),
            emptyList(),
        )
        val newState = PersistedState(
            "doc-new",
            listOf(
                Document("doc-new", "New passport", DocKind.Passport("DE")),
                Document("doc-extra", "Just added", DocKind.Passport("FR")),
            ),
            emptyList(),
        )
    }
}
