package com.example.visa_vole.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Owns the bundled, read-only VisaDB SQLite file.
 *
 * The database ships in `assets/` (copied once to internal storage on first launch, or refreshed
 * when the bundled file changes) and is opened read-only. No migrations: the schema is fixed by
 * the VisaDB generator.
 */
class VisaDb(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dbFile: File = File(appContext.filesDir, DB_NAME)
    private var db: SQLiteDatabase? = null

    val database: SQLiteDatabase
        get() {
            db?.takeIf { it.isOpen }?.let { return it }
            ensureCopied()
            val opened = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db = opened
            return opened
        }

    private fun ensureCopied() {
        // Re-copy when the bundled asset has changed (a schema/data refresh in an app update), so
        // an existing install never keeps a stale copy. SQLite files can change content while
        // keeping the same byte size, so the size check is only a fast path; the SHA-256 digest is
        // authoritative.
        val assetLen = appContext.assets.openFd(ASSET_NAME).use { it.length }
        if (dbFile.exists() && dbFile.length() == assetLen && fileSha256(dbFile) == assetSha256()) return
        if (dbFile.exists()) dbFile.delete()
        appContext.assets.open(ASSET_NAME).use { input ->
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private fun assetSha256(): String =
        appContext.assets.open(ASSET_NAME).use { sha256(it) }

    private fun fileSha256(file: File): String =
        file.inputStream().use { sha256(it) }

    private fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var read = stream.read(buffer)
        while (read != -1) {
            digest.update(buffer, 0, read)
            read = stream.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ASSET_NAME = "visa_data.db"
        private const val DB_NAME = "visa_data.db"
    }
}
