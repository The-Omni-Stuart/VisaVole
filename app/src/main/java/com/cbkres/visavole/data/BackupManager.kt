package com.cbkres.visavole.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads and writes the user's data to backup files through the Storage Access Framework.
 *
 * The canonical state always lives in [stateFile] (an app-private JSON file); a backup is a
 * user-facing copy of it. This class owns everything about where the data lives and how a backup
 * file is read or written — the ViewModel only ever sees a [PersistedState] or an [Outcome], and
 * the UI only ever asks the ViewModel to do things, so no persistence logic leaks into screens.
 */
class BackupManager(context: Context) {
    private val appContext = context.applicationContext

    /** The app-private file that always holds the user's current data. */
    val stateFile: File
        get() = File(appContext.filesDir, STATE_FILE_NAME)

    sealed interface Outcome {
        /** The current data was written to [uri]. */
        data class Exported(val uri: Uri) : Outcome
        /** The backup at [uri] was read and validated; [state] is what it contains. */
        data class Imported(val uri: Uri, val state: PersistedState) : Outcome
        /** Nothing could be done; [message] is safe to show verbatim. */
        data class Failed(val message: String) : Outcome
    }

    /** Copy the current data to the user-chosen location [uri]; validates before writing. */
    fun exportTo(uri: Uri): Outcome {
        return try {
            val text = stateFile.takeIf { it.exists() }?.readText()
                ?: return Outcome.Failed("There is no data to back up yet.")
            StateCodec.decode(text) // fail early if the current state is unreadable
            val out = appContext.contentResolver.openOutputStream(uri)
                ?: return Outcome.Failed("Could not open the backup location.")
            try {
                out.use { it.write(text.toByteArray()) }
            } catch (e: Exception) {
                // P3-33: a mid-write failure (disk full, permission revoked) can leave a truncated
                // backup at the chosen location — say so rather than reporting a clean failure.
                return Outcome.Failed("Export failed mid-way; a partial backup may remain at the chosen location.")
            }
            Outcome.Exported(uri)
        } catch (e: Exception) {
            Outcome.Failed("Export failed: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Read and validate the backup at [uri]; the caller decides when to apply the state. Reads are
     * capped at [MAX_IMPORT_BYTES] — the picker can offer any file on the device, and reading a
     * video/ISO in one shot would OOM the process.
     */
    fun importFrom(uri: Uri): Outcome = try {
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: return Outcome.Failed("Could not open the backup file.")
        val text = stream.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) {
                    return Outcome.Failed(
                        "That file is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MB, " +
                            "so it can't be a Visa Vole backup.",
                    )
                }
                bytes.write(buffer, 0, read)
            }
            bytes.toString(Charsets.UTF_8.name())
        }
        Outcome.Imported(uri, StateCodec.decode(text.removePrefix("\uFEFF")))
    } catch (e: Exception) {
        Outcome.Failed("That file does not look like a Visa Vole backup.")
    }

    companion object {
        /** The app-private state file name (inside the app's files directory). */
        const val STATE_FILE_NAME = "visavole_state.json"
        /** Suggested name for a new backup file. */
        const val BACKUP_FILE_NAME = "visavole_userdata.json"
        /** Hard cap for backup imports — a real backup is a few KB of JSON. */
        const val MAX_IMPORT_BYTES = 16 * 1024 * 1024
    }
}
