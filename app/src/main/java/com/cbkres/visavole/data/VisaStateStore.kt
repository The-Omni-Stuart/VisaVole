package com.cbkres.visavole.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** [VisaStateStore.load]'s result: the file's [state] plus [droppedRecords] records it could not read. */
data class LoadedState(val state: PersistedState, val droppedRecords: Int)

/**
 * Reads and writes snapshots of the user's [PersistedState] to the app-private state file.
 * It owns *how* state is saved safely — ordered writes off the UI thread, atomic replacement,
 * corrupted-file quarantine — but not *when* to save or whether a restore is valid; those
 * decisions stay with the caller.
 */
internal class VisaStateStore(
    private val file: File,
    scope: CoroutineScope,
    // Test seam: production always encodes with StateCodec.
    private val encode: (PersistedState) -> String = StateCodec::encode,
) {
    // Ordered queue of snapshots handed to the writer below (Kotlin runs property initializers +
    // init blocks in source order). Drained in FIFO order on the IO dispatcher so rapid mutations
    // hit disk in the order they happened (each write is the full state, so the last one wins);
    // encoding and disk I/O both run there, so the caller's thread does neither.
    private val persistQueue = Channel<PersistedState>(Channel.UNLIMITED)

    /** Set when the state file exists but cannot be read or quarantined; while set, save()
     *  refuses to run, because writing would overwrite the user's only copy of the data. */
    @Volatile private var stateFileBlocked = false

    // P3-56: mtime of the state file as of our last write (or the read at startup). If it advances
    // on its own before the next persist(), something else (e.g. a pushed A/B fixture) modified the
    // file while we were running, and persist() would silently clobber that change.
    @Volatile private var lastPersistMs: Long = 0L

    init {
        // Ordered persistence writer: drains persistQueue in FIFO order on the IO dispatcher so
        // the calling thread never encodes or touches disk.
        scope.launch {
            var inFlight: PersistedState? = null
            try {
                for (state in persistQueue) {
                    inFlight = state
                    withContext(Dispatchers.IO) { encodeAndWrite(state) }
                    inFlight = null
                }
            } finally {
                // Cancellation used to strand every queued write: flush what remains (including an
                // item whose write was interrupted) so the last edit survives the scope ending.
                val tail = buildList {
                    inFlight?.let { add(it) }
                    while (true) {
                        val state = persistQueue.tryReceive().getOrNull() ?: break
                        add(state)
                    }
                }
                if (tail.isNotEmpty()) {
                    withContext(NonCancellable + Dispatchers.IO) { tail.forEach { encodeAndWrite(it) } }
                }
            }
        }
    }

    /**
     * Reads the persisted state, or null when there is no state file (fresh install) or it could
     * not be read. A file that cannot be decoded is quarantined rather than ignored, so the next
     * write can never destroy the user's only copy of the data. Partially readable files carry
     * their unreadable-record count (M6) so the caller can warn the user. Must be called on an
     * IO dispatcher.
     */
    fun load(): LoadedState? {
        val f = file
        try {
            if (!f.exists()) return null
            // P3-56: baseline the mtime we read so the first persist() doesn't false-positive on a
            // pre-existing (older) file.
            lastPersistMs = f.lastModified()
            val decoded = StateCodec.decode(f.readText())
            return LoadedState(decoded.state, decoded.droppedRecords)
        } catch (e: Exception) {
            // Never start empty on top of a file we could not read: the next persist() would
            // overwrite it and the user's data would be gone for good. Quarantine it instead,
            // so it can be inspected or recovered later.
            Log.e(TAG, "Could not decode state file $f; quarantining", e)
            val quarantine = File(f.parentFile, "${f.name}.corrupt-${System.currentTimeMillis()}")
            if (f.renameTo(quarantine)) {
                Log.w(TAG, "Quarantined unreadable state file as $quarantine")
            } else {
                // Renaming failed: copy the file aside best-effort, keep the original untouched
                // and BLOCK persisting for this process — writing would destroy the user's data.
                try {
                    f.copyTo(quarantine, overwrite = false)
                    Log.w(TAG, "Copied unreadable state file to $quarantine")
                } catch (copyError: Exception) {
                    Log.e(TAG, "Could not copy $f to $quarantine", copyError)
                }
                stateFileBlocked = true
                Log.e(TAG, "Unreadable state file $f could not be quarantined; persisting disabled for this process")
            }
            // P3-29: cap the quarantine files so repeated decode failures don't accumulate forever.
            // parentFile is @Nullable in java.io.File (null only for a parentless path, which the
            // caller's filesDir-based path never is), so prune only when there is a directory.
            f.parentFile?.let { pruneQuarantined(it) }
            return null
        }
    }

    /**
     * Queues [state] for writing. Both encoding and the disk write run on the ordered IO writer,
     * so the caller's thread does neither; [state] is an immutable snapshot, so encoding it later
     * serializes exactly this moment's content.
     */
    fun save(state: PersistedState) {
        if (stateFileBlocked) {
            Log.e(TAG, "Refusing to persist: unreadable state file $file still present (overwriting it would lose the user's data)")
            return
        }
        persistQueue.trySend(state)
    }

    /** Ordered IO writer: encode one snapshot, then tmp-write + atomic rename it. */
    private fun encodeAndWrite(state: PersistedState) {
        val text = runCatching {
            encode(state)
        }.getOrElse { e ->
            Log.e(TAG, "Could not encode state for $file", e)
            return
        }
        writeFile(text)
    }

    /** Atomic tmp-write + rename for one encoded state. */
    private fun writeFile(text: String) {
        // Flag the A/B fixture race — the file changed on disk since we last wrote it, so we
        // are about to overwrite a change made by something else (e.g. an adb push of a state file).
        val currentMtime = file.lastModified()
        if (currentMtime > lastPersistMs) {
            Log.w(TAG, "State file $file modified since our last write (mtime $currentMtime > $lastPersistMs); overwriting with in-memory state")
        }
        try {
            // Write to a sibling temp file and rename over the target: the rename is atomic, so a
            // process death mid-write leaves the previous file intact instead of truncated JSON.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            lastPersistMs = file.lastModified()
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist state to $file", e)
        }
    }

    /** P3-29: keep only the newest [keep] quarantine files so a run of decode failures doesn't fill
     *  the files dir. Names end in `.corrupt-<epoch-millis>`, so sorting by name is newest-first. */
    private fun pruneQuarantined(dir: File, keep: Int = 3) {
        val files = dir.listFiles { f -> f.isFile && f.name.contains(".corrupt-") }?.sortedByDescending { it.name } ?: return
        for (old in files.drop(keep)) runCatching { old.delete() }
    }

    companion object {
        private const val TAG = "VisaStateStore"
    }
}
