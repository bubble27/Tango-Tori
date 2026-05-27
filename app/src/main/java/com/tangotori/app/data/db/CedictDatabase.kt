package com.tangotori.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.FileOutputStream

/**
 * Opens the CC-CEDICT pre-packaged asset as a read-only [SQLiteDatabase].
 *
 * Uses direct SQLiteDatabase (not Room) to avoid Room's strict schema-
 * validation which would reject any column-naming or default-value
 * discrepancy between the Kotlin entities and the Python-generated file.
 *
 * On first launch the asset is copied to the app's database directory.
 * Subsequent launches re-use the copy (no re-copy unless the file is missing
 * or empty). Returns null if the asset hasn't been generated yet — callers
 * degrade gracefully.
 */
object CedictAsset {

    private const val ASSET_NAME = "cedict.db"

    fun open(context: Context): SQLiteDatabase? = try {
        // noCompress "db" in build.gradle ensures the asset is uncompressed,
        // so openFd works and returns the true length for staleness detection.
        val assetLength = context.assets.openFd(ASSET_NAME).use { it.length }
        val dbFile = context.getDatabasePath(ASSET_NAME)
        if (!dbFile.exists() || dbFile.length() != assetLength) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(ASSET_NAME).use { src ->
                FileOutputStream(dbFile).use { dst -> src.copyTo(dst) }
            }
        }
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
    } catch (_: Exception) {
        null
    }
}
