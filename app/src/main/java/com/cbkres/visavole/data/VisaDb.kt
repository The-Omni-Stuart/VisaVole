package com.cbkres.visavole.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

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
        // an existing install never keeps a stale copy. The DB_VERSION constant is the explicit
        // refresh trigger: bump it whenever the bundled `visa_data.db` asset changes.
        val assetLen = appContext.assets.openFd(ASSET_NAME).use { it.length }
        val expected = "$DB_VERSION|$assetLen"
        if (dbFile.exists() && metaFile.exists() && metaFile.readText().trim() == expected) return
        if (dbFile.exists()) dbFile.delete()
        appContext.assets.open(ASSET_NAME).use { input ->
            dbFile.outputStream().use { input.copyTo(it) }
        }
        metaFile.writeText(expected)
    }

    companion object {
        private const val ASSET_NAME = "visa_data.db"
        private const val DB_NAME = "visa_data.db"
        private const val DB_VERSION = 2
    }
}
