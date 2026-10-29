package com.cbkres.visavole.data

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads and writes snapshots of the user's [PersistedState] to the app-private state file.
 * It owns *how* state is saved safely — ordered writes off the UI thread, atomic replacement,
 * corrupted-file quarantine — but not *when* to save or whether a restore is valid; those
 * decisions stay with the caller.
 */
internal class VisaStateStore(
    private val file: File,
    scope: CoroutineScope,
) {
    // P3-48: ordered queue of encoded state strings. Declared before init so the writer launched
    // below can reference it (Kotlin runs property initializers + init blocks in source order).
    // Drained in FIFO order on the IO dispatcher so rapid mutations hit disk in the order they
    // happened (each write is the full state, so the last one wins) and the UI thread never does
    // disk I/O.
    private val persistQueue = Channel<String>(Channel.UNLIMITED)

    /** Set when the state file exists but cannot be read or quarantined; while set, save()
     *  refuses to run, because writing would overwrite the user's only copy of the data. */
    @Volatile private var stateFileBlocked = false

    // P3-56: mtime of the state file as of our last write (or the read at startup). If it advances
    // on its own before the next persist(), something else (e.g. a pushed A/B fixture) modified the
    // file while we were running, and persist() would silently clobber that change.
    @Volatile private var lastPersistMs: Long = 0L

    init {
        // P3-48: ordered persistence writer — drains persistQueue in FIFO order on the IO dispatcher,
        // so the UI thread never does the disk write (and rapid mutations apply in order).
        scope.launch {
            for (text in persistQueue) {
                withContext(Dispatchers.IO) { writeFile(text) }
            }
        }
    }

    /**
     * Reads the persisted state, or null when there is no state file (fresh install) or it could
     * not be read. A file that cannot be decoded is quarantined rather than ignored, so the next
     * write can never destroy the user's only copy of the data. Must be called on an IO dispatcher.
     */
    fun load(): PersistedState? {
        val f = file
        try {
            if (!f.exists()) return null
            // P3-56: baseline the mtime we read so the first persist() doesn't false-positive on a
            // pre-existing (older) file.
            lastPersistMs = f.lastModified()
            return StateCodec.decode(f.readText())
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
            pruneQuarantined(f.parentFile)
            return null
        }
    }

    /**
     * Queues [state] for writing. Encoding happens here (cheap, KBs) and the disk write runs on
     * the ordered IO writer, so the caller's thread never blocks on disk.
     */
    fun save(state: PersistedState) {
        if (stateFileBlocked) {
            Log.e(TAG, "Refusing to persist: unreadable state file $file still present (overwriting it would lose the user's data)")
            return
        }
        // P3-48: encode on the main thread (cheap, KBs) and hand the text to the ordered IO writer,
        // so the disk write no longer runs on the UI thread on the exact frame of the tap.
        val text = runCatching {
            StateCodec.encode(state)
        }.getOrElse { e ->
            Log.e(TAG, "Could not encode state for $file", e)
            return
        }
        persistQueue.trySend(text)
    }

    /** Ordered IO writer (P3-48): tmp-write + atomic rename for one encoded state. */
    private fun writeFile(text: String) {
        // P3-56: flag the A/B fixture race — the file changed on disk since we last wrote it, so we
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
