package com.cbkres.visavole.data

import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripStop
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
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * Tests for [VisaStateStore] at its public seam — `load()`/`save(PersistedState)` — verified
 * through the state file on disk (a temp directory), never through the internal write queue.
 */
class VisaStateStoreTest {

    private lateinit var dir: File
    private lateinit var stateFile: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: VisaStateStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("visavole-store").toFile()
        stateFile = File(dir, "visavole_state.json")
        scope = CoroutineScope(Job() + Dispatchers.Default)
        store = VisaStateStore(file = stateFile, scope = scope)
    }

    @After
    fun tearDown() {
        // cancelAndJoin (not just cancel): the writer may be mid-write, and an in-flight file
        // operation must finish before the temp directory goes away.
        runBlocking {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        dir.deleteRecursively()
    }

    @Test
    fun loadReturnsNullWhenNoStateFileExists() {
        assertNull(store.load())
    }

    @Test
    fun savedStateLoadsBackUnchanged() {
        store.save(fullState)

        assertTrue("the saved state never reached disk", awaitUntil { diskContains(fullState) })
        assertEquals(fullState, store.load())
    }

    @Test
    fun rapidSavesLeaveTheLastStateAndNoTempFile() {
        val states = (1..5).map { n ->
            PersistedState("doc-$n", listOf(Document("doc-$n", "Passport · $n", DocKind.Passport("CZ"))), emptyList())
        }
        states.forEach { store.save(it) }

        assertTrue(
            "the fifth save never reached disk",
            awaitUntil { diskContains(states.last()) && !File(dir, "${stateFile.name}.tmp").exists() },
        )
        assertTrue(
            "a temp file survived the atomic-rename write",
            dir.listFiles { f: File -> f.name.endsWith(".tmp") }?.isEmpty() ?: false,
        )
    }

    @Test
    fun corruptStateFileIsQuarantinedAndLoadReturnsNull() {
        val garbage = "{ not valid json"
        stateFile.writeText(garbage)

        assertNull(store.load())

        assertTrue("corrupt state file was not moved aside", !stateFile.exists())
        val quarantined = dir.listFiles { f: File -> f.name.contains(".corrupt-") }.orEmpty()
        assertEquals("expected exactly one quarantine file", 1, quarantined.size)
        assertEquals(garbage, quarantined[0].readText())
    }

    @Test
    fun quarantineFilesArePrunedToTheThreeNewest() {
        // Quarantine names end in 13-digit epoch millis, so name order is age order. Seed 5 stale
        // ones; reading a corrupt state file adds a 6th (now) — only the 3 newest may survive.
        val staleBase = 1_700_000_000_000L // old enough that "now" quarantines always sort newer
        val staleNames = (1L..3L).map { "${stateFile.name}.corrupt-${staleBase + it * 1_000}" }
        (1L..5L).forEach { epoch ->
            File(dir, "${stateFile.name}.corrupt-${staleBase + epoch * 1_000}").writeText("{}")
        }
        stateFile.writeText("{ not valid json")

        assertNull(store.load())

        val survivors = dir.listFiles { f: File -> f.name.contains(".corrupt-") }.orEmpty().map { it.name }.sorted()
        assertEquals("expected the 2 seeded survivors plus the new quarantine", 3, survivors.size)
        assertTrue("stale quarantines were not pruned: $survivors", survivors.intersect(staleNames.toSet()).isEmpty())
    }

    /**
     * Decodes the state file straight off disk. Deliberately bypasses `store.load()`: load
     * quarantines on a decode failure, and while the writer is still running a partially copied
     * file could otherwise be "recovered" away by the very poll that waits for it.
     */
    private fun diskContains(expected: PersistedState): Boolean =
        runCatching { StateCodec.decode(stateFile.readText()) == expected }.getOrDefault(false)

    /** Polls [condition] until it holds or [timeoutMs] elapses; save() hands off to an async writer. */
    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private companion object {
        val fullState = PersistedState(
            primaryDocId = "doc-passport",
            docs = listOf(
                Document("doc-passport", "Passport · Czech Republic", DocKind.Passport("CZ"), "2031-05-01", "2021-05-01"),
                Document("doc-holding", "Schengen residence", DocKind.Holding("schengen-residence", "multiple"), "2029-11-30", null),
            ),
            trips = listOf(
                Trip(
                    "trip-1",
                    listOf(
                        TripStop("stop-1", "DE", LocalDate.parse("2026-10-01"), null, "doc-holding"),
                        TripStop("stop-2", "FR", LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-12"), null),
                    ),
                    "Berlin then Paris",
                ),
            ),
        )
    }
}
