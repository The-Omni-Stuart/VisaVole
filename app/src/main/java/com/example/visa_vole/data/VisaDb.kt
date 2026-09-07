package com.example.visa_vole.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Owns the bundled, read-only VisaDB SQLite file.
 *
 * The database ships in `assets/` (copied once to internal storage on first launch, or refreshed
 * when the bundled size changes) and is opened read-only. No migrations: the schema is fixed by
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
        if (dbFile.exists()) return
        appContext.assets.open(ASSET_NAME).use { input ->
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    companion object {
        private const val ASSET_NAME = "visa_data.db"
        private const val DB_NAME = "visa_data.db"
    }
}
