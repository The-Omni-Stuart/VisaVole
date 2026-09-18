package com.cbkres.visavole.data

import android.content.Context
import android.net.Uri
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
            appContext.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: return Outcome.Failed("Could not open the backup location.")
            Outcome.Exported(uri)
        } catch (e: Exception) {
            Outcome.Failed("Export failed: ${e.message ?: "unknown error"}")
        }
    }

    /** Read and validate the backup at [uri]; the caller decides when to apply the state. */
    fun importFrom(uri: Uri): Outcome = try {
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return Outcome.Failed("Could not open the backup file.")
        Outcome.Imported(uri, StateCodec.decode(text))
    } catch (e: Exception) {
        Outcome.Failed("That file does not look like a Visa Vole backup.")
    }

    companion object {
        /** The app-private state file name (inside the app's files directory). */
        const val STATE_FILE_NAME = "visavole_state.json"
        /** Suggested name for a new backup file. */
        const val BACKUP_FILE_NAME = "visavole_userdata.json"
    }
}
