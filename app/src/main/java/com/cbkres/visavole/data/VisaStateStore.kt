package com.cbkres.visavole.data

import android.system.Os
import android.system.OsConstants
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
    // Test seams: production fsyncs and renames for real; tests pin the durability protocol's
    // order and exercise the rename-denied fallback.
    private val sync: (File) -> Unit = VisaStateStore::fsync,
    private val rename: (File, File) -> Boolean = { src, dest -> src.renameTo(dest) },
) {
    // Ordered queue of snapshots handed to the writer below (Kotlin runs property initializers +
    // init blocks in source order). Drained in FIFO order on the IO dispatcher so rapid mutations
    // hit disk in the order they happened (each write is the full state, so the last one wins);
    // encoding and disk I/O both run there, so the caller's thread does neither.
    private val persistQueue = Channel<PersistedState>(Channel.UNLIMITED)

    /** Set when the state file exists but cannot be read or quarantined; while set, save()
     *  refuses to run, because writing would overwrite the user's only copy of the data. */
    @Volatile private var stateFileBlocked = false

    // mtime of the state file as of our last write (or the read at startup). If it advances
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

    /** Damaged content is quarantined so a later write cannot clobber it; an unreadable file is left in place and persisting is disabled instead. */
    fun load(): LoadedState? {
        val f = file
        if (!f.exists()) return null
        // baseline the mtime we read, or the first persist() false-positives on an older file.
        lastPersistMs = f.lastModified()
        val text = try {
            f.readText()
        } catch (e: Exception) {
            return unreadableWithoutQuarantine(f, e)
        }
        val decoded = try {
            StateCodec.decode(text)
        } catch (e: Exception) {
            quarantineUndecodable(f, e)
            return null
        }
        return LoadedState(decoded.state, decoded.droppedRecords)
    }

    /** The file is intact on disk but unreadable right now: keep it, and refuse to overwrite it. */
    private fun unreadableWithoutQuarantine(f: File, cause: Exception): LoadedState? {
        Log.e(TAG, "Could not read state file $f; leaving it in place and disabling persisting", cause)
        blockPersisting(f)
        return null
    }

    private fun blockPersisting(f: File) {
        stateFileBlocked = true
        Log.e(TAG, "State file $f is still present and unusable; persisting disabled for this process")
    }

    private fun quarantineUndecodable(f: File, cause: Exception) {
        Log.e(TAG, "Could not decode state file $f; quarantining", cause)
        val quarantine = File(f.parentFile, "${f.name}.corrupt-${System.currentTimeMillis()}")
        if (f.renameTo(quarantine)) {
            Log.w(TAG, "Quarantined unreadable state file as $quarantine")
        } else {
            // Rename failed: copy aside best-effort, leave the original, and stop persisting.
            try {
                f.copyTo(quarantine, overwrite = false)
                Log.w(TAG, "Copied unreadable state file to $quarantine")
            } catch (copyError: Exception) {
                Log.e(TAG, "Could not copy $f to $quarantine", copyError)
            }
            blockPersisting(f)
        }
        // parentFile is nullable only for a parentless path, which a filesDir path never is.
        f.parentFile?.let { pruneQuarantined(it) }
    }

    /**
     * Queues [state] for writing. Both encoding and the disk write run on the ordered IO writer,
     * so the caller's thread does neither; [state] is an immutable snapshot, so encoding it later
     * serializes exactly this moment's content. False when nothing was queued (persisting is
     * disabled), so callers can tell the user their change is memory-only.
     */
    fun save(state: PersistedState): Boolean {
        if (stateFileBlocked) {
            Log.e(TAG, "Refusing to persist: unreadable state file $file still present (overwriting it would lose the user's data)")
            return false
        }
        return persistQueue.trySend(state).isSuccess
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

    /**
     * Durable tmp-write + atomic rename for one encoded state. The tmp file is fsynced **before**
     * the rename (else a power cut can swap in not-yet-durable bytes and leave a file that
     * decodes corrupt), and the directory is fsynced after (the rename itself is only durable
     * once its directory entry is). A failed rename falls back to a second tmp + rename — never
     * a copy onto the live file, which truncates the user's only copy before writing it.
     */
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
            sync(tmp)
            if (!rename(tmp, file)) {
                // Cross-device or denied replace: stage via a second tmp and rename that. If even
                // this rename fails, give up — refusing loses only this edit, while copying
                // straight onto the live file loses everything on a cut mid-copy.
                val alt = File(file.parentFile, "${file.name}.alt-tmp")
                if (alt.exists() && !alt.delete()) {
                    Log.e(TAG, "Could not clear stale alt tmp $alt; refusing to persist")
                    return
                }
                tmp.copyTo(alt, overwrite = false)
                sync(alt)
                tmp.delete()
                if (rename(alt, file)) {
                    file.parentFile?.let { dir -> runCatching { sync(dir) } }
                } else {
                    alt.delete()
                    Log.e(TAG, "Neither rename of $file succeeded; refusing to overwrite the live state file")
                }
            } else {
                file.parentFile?.let { dir -> runCatching { sync(dir) } }
            }
            lastPersistMs = file.lastModified()
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist state to $file", e)
        }
    }

    /** keep only the newest [keep] quarantine files so a run of decode failures doesn't fill
     *  the files dir. Names end in `.corrupt-<epoch-millis>`, so sorting by name is newest-first. */
    private fun pruneQuarantined(dir: File, keep: Int = 3) {
        val files = dir.listFiles { f -> f.isFile && f.name.contains(".corrupt-") }?.sortedByDescending { it.name } ?: return
        for (old in files.drop(keep)) runCatching { old.delete() }
    }

    companion object {
        private const val TAG = "VisaStateStore"

        /** Flush a file's bytes (or a directory's entries) to durable storage. */
        private fun fsync(f: File) {
            try {
                // O_RDONLY is the only way to open a directory fd and fsync accepts it;
                // RandomAccessFile refuses directories outright, so the old path never synced one.
                val fd = Os.open(f.absolutePath, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    Os.close(fd)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not fsync $f", e)
            }
        }
    }
}
