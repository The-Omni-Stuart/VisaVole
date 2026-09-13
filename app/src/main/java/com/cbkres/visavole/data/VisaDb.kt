package com.cbkres.visavole.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * Owns the bundled, read-only VisaDB SQLite file.
 *
 * The database ships in `assets/` (copied once to internal storage on first launch, or refreshed
 * when the bundled file changes) and is opened read-only. The Gradle build writes a
 * `visa_data.db.sha256` sidecar next to the asset; when present, that SHA is the refresh trigger.
 * No migrations: the schema is fixed by the VisaDB generator.
 */
class VisaDb(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dbFile: File = File(appContext.filesDir, DB_NAME)
    private val metaFile: File = File(appContext.filesDir, "$DB_NAME.meta")
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
        // an existing install never keeps a stale copy. The build-generated SHA sidecar is the
        // refresh trigger; DB_VERSION remains a fallback for assets built without the sidecar.
        val assetLen = appContext.assets.openFd(ASSET_NAME).use { it.length }
        val sha = try {
            appContext.assets.open(ASSET_SHA_NAME).use { it.bufferedReader().readText().trim() }.ifEmpty { null }
        } catch (_: IOException) {
            null
        }
        val expected = if (sha != null) "$sha|$assetLen" else "$DB_VERSION|$assetLen"
        if (dbFile.exists() && metaFile.exists() && metaFile.readText().trim() == expected) return
        if (dbFile.exists()) dbFile.delete()
        appContext.assets.open(ASSET_NAME).use { input ->
            dbFile.outputStream().use { input.copyTo(it) }
        }
        metaFile.writeText(expected)
    }

    companion object {
        private const val ASSET_NAME = "visa_data.db"
        private const val ASSET_SHA_NAME = "visa_data.db.sha256"
        private const val DB_NAME = "visa_data.db"
        private const val DB_VERSION = 2
    }
}
