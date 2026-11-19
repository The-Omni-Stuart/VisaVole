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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

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
        assertEquals(fullState, store.load()?.state)
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
    fun queuedWritesSurviveScopeCancellation() {
        store.save(fullState)
        assertTrue("the saved state never reached disk", awaitUntil { diskContains(fullState) })

        // Multiple enqueues widen the window: the writer consumes one per dispatch, so the last
        // one is almost certainly still queued when the scope is cancelled below.
        val queued = (1..8).map { n ->
            PersistedState("doc-$n", listOf(Document("doc-$n", "Passport · $n", DocKind.Passport("CZ"))), emptyList())
        }
        queued.forEach { store.save(it) }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }

        assertTrue(
            "the last queued save never reached disk after the scope was cancelled",
            diskContains(queued.last()),
        )
    }

    /**
     * save() must only enqueue: encoding runs on the writer's IO thread, not the caller's, so the
     * (KB-scale) JSON build stays off the mutation frame even for a large state.
     */
    @Test
    fun encodeRunsOnTheWriterThreadNotTheEnqueueingThread() {
        val enqueueThread = Thread.currentThread().name
        val encodedOn = AtomicReference<String>()
        val encodingStore = VisaStateStore(file = stateFile, scope = scope, encode = { state ->
            encodedOn.set(Thread.currentThread().name)
            StateCodec.encode(state)
        })

        encodingStore.save(fullState)

        assertTrue("the saved state never reached disk", awaitUntil { diskContains(fullState) })
        val thread = encodedOn.get()
        assertNotNull("the injected encoder never ran", thread)
        assertTrue(
            "encode ran on the enqueueing thread ($thread)",
            thread != enqueueThread,
        )
        assertEquals(fullState, store.load()?.state)
    }

    @Test
    fun loadReportsDroppedRecordsFromTheStateFile() {
        stateFile.writeText(
            """{"schemaVersion":3,"primaryDocId":null,
               "docs":[{"id":"doc-1","label":"P","kind":"passport","iso2":"CZ"},"idless"],"trips":[]}""",
        )

        val loaded = store.load()

        assertEquals(1, loaded?.state?.docs?.size)
        assertEquals(1, loaded?.droppedRecords)
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
    fun aFileItCouldNotReadIsLeftWhereItIs() {
        // A directory at the state path fails on read, not decode — quarantine would evict good data.
        assertTrue("the state path must be a directory for this test", stateFile.mkdir())

        assertNull(store.load())

        assertTrue("an unreadable file must not be moved", stateFile.isDirectory)
        assertTrue("a read failure must not leave quarantine files behind",
                   dir.listFiles { f: File -> f.name.contains(".corrupt-") }.orEmpty().isEmpty())
        store.save(PersistedState(primaryDocId = null, docs = emptyList(), trips = emptyList()))
        assertTrue("persisting must stay blocked while the state path is unusable",
                   stateFile.isDirectory && stateFile.listFiles().orEmpty().isEmpty())
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
     * The tmp file must reach durable storage before the rename makes it live, and the directory
     * entry must be synced after. The real `fd.sync()` bytes are not observable from JVM, so the
     * sync step is a seam and the test pins the order of the durability protocol.
     */
    @Test
    fun stateFileSyncsBeforeRenameAndFallsBackViaSecondTmp() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val recordingSync: (File) -> Unit = { events += "sync:${it.name}" }
        var denyLiveRename = false
        val recordingRename: (File, File) -> Boolean = { src, dest ->
            val isLive = dest.name == stateFile.name
            val isFirstAttempt = src.name == "${stateFile.name}.tmp"
            events += if (isLive) "rename:live(${if (isFirstAttempt) "tmp" else "alt"})" else "rename:${dest.name}"
            // Deny only the tmp→live rename; the staged alt→live one must still work.
            when {
                isLive && isFirstAttempt && denyLiveRename -> false
                else -> src.renameTo(dest)
            }
        }

        val store1 = VisaStateStore(file = stateFile, scope = scope, sync = recordingSync, rename = recordingRename)
        store1.save(fullState)
        assertTrue("the saved state never reached disk", awaitUntil { diskContains(fullState) })
        assertEquals("the durability protocol ran in the wrong order", listOf(
            "sync:${stateFile.name}.tmp",
            "rename:live(tmp)",
            "sync:${dir.name}",
        ), events)

        // Second save with the live rename denied: must land via a second tmp + rename, the
        // live file never truncated-then-copied.
        events.clear()
        denyLiveRename = true
        val store2 = VisaStateStore(file = stateFile, scope = scope, sync = recordingSync, rename = recordingRename)
        val second = copyOf(fullState, "doc-second")
        store2.save(second)
        assertTrue("the fallback never delivered the new state", awaitUntil { diskContains(second) })
        assertTrue("fallback did not rename the staged alt tmp: $events",
            events.contains("rename:live(alt)"))
        assertTrue("no sync of the second tmp before its rename: $events",
            events.count { it.startsWith("sync:") } >= 2)
        assertTrue(
            "a temp file survived the fallback",
            dir.listFiles { f: File -> f.name.endsWith(".tmp") || f.name.contains("alt") }?.isEmpty() ?: false,
        )
    }

    /**
     * When even the fallback rename cannot land, the store must refuse rather than overwrite —
     * the live file keeps decoding the OLD state. A copy-onto-live branch could lose everything
     * on a cut mid-copy; a refused save only loses the edit. Filesystems that ignore read-only
     * directories cannot force the failure, so the test skips there.
     */
    @Test
    fun refusesToPersistRatherThanTruncateWhenReplacementImpossible() {
        try {
            // Baseline content written straight to disk (before the lock): the writer cannot
            // touch it afterwards, so whatever the store does, the live file must still decode
            // to it.
            stateFile.writeText(StateCodec.encode(fullState))
            if (!makeDirReadOnly()) return // filesystem ignores write-protected directories

            store.save(copyOf(fullState, "doc-second"))
            Thread.sleep(400) // give the writer its turn; nothing may land

            assertEquals(fullState, store.load()?.state)
        } finally {
            dir.setWritable(true)
        }
    }

    private fun makeDirReadOnly(): Boolean {
        dir.setWritable(false)
        // tmpfs/btrfs throw instead of returning false when the directory denies the create.
        val protected_ = runCatching { !File(dir, "probe").createNewFile() }.getOrDefault(true)
        if (!protected_) dir.setWritable(true)
        return protected_
    }

    private fun copyOf(base: PersistedState, id: String) =
        PersistedState(id, listOf(Document(id, "Passport · $id", DocKind.Passport("CZ"))), emptyList())

    /**
     * Decodes the state file straight off disk. Deliberately bypasses `store.load()`: load
     * quarantines on a decode failure, and while the writer is still running a partially copied
     * file could otherwise be "recovered" away by the very poll that waits for it.
     */
    private fun diskContains(expected: PersistedState): Boolean =
        runCatching { StateCodec.decode(stateFile.readText()).state == expected }.getOrDefault(false)

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
