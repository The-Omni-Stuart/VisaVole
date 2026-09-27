package com.cbkres.visavole.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
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
            db = openWithSelfHeal()
            return db!!
        }

    /**
     * Opens the internal copy read-only and probes it with a query: a file can pass
     * [SQLiteDatabase.openDatabase] yet still be damaged, in which case only the first query
     * fails. A damaged internal copy (its meta matches, so it is never re-copied) would otherwise
     * crash the app on every launch — drop the pair once and retry from the bundled asset.
     */
    private fun openWithSelfHeal(): SQLiteDatabase {
        try {
            return openAndProbe()
        } catch (e: SQLiteException) {
            Log.w(TAG, "Internal visa_data.db failed to open/probe; re-copying from assets", e)
            dbFile.delete()
            metaFile.delete()
            ensureCopied()
            return openAndProbe()
        }
    }

    private fun openAndProbe(): SQLiteDatabase {
        val conn = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        conn.rawQuery("SELECT 1 FROM countries LIMIT 1", null).use { it.moveToNext() }
        return conn
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
        val expected = fingerprint(sha, assetLen)
        val metaText = if (metaFile.exists()) metaFile.readText() else null
        if (!shouldRecopy(dbFile.exists(), metaText, expected)) return
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
        private const val TAG = "VisaDb"

        /** The refresh trigger: the SHA sidecar when present, else the legacy version constant. */
        internal fun fingerprint(sha: String?, assetLength: Long): String =
            if (sha != null) "$sha|$assetLength" else "$DB_VERSION|$assetLength"

        /** Re-copy when the internal file is missing or its recorded fingerprint is stale/absent. */
        internal fun shouldRecopy(dbFileExists: Boolean, metaText: String?, expected: String): Boolean =
            !dbFileExists || metaText?.trim() != expected
    }
}
