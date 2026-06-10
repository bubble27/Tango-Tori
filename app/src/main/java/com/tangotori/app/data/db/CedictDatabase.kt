package com.tangotori.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * Opens the CC-CEDICT pre-packaged asset as a read-only [SQLiteDatabase].
 *
 * Uses direct SQLiteDatabase (not Room) to avoid Room's strict schema-
 * validation which would reject any column-naming or default-value
 * discrepancy between the Kotlin entities and the Python-generated file.
 *
 * On first launch the asset is copied to the app's database directory.
 * Staleness is tracked with a sibling marker file holding [CEDICT_VERSION]
 * (the asset is deflate-compressed in the APK, so openFd()-based length
 * comparison is no longer possible — see app/build.gradle.kts). Bump
 * [CEDICT_VERSION] whenever the bundled cedict.db is regenerated. Returns
 * null if the asset hasn't been generated yet — callers degrade gracefully.
 */
object CedictAsset {

    private const val ASSET_NAME = "cedict.db"

    /** Identity of the bundled CC-CEDICT database. Bump when the asset changes. */
    private const val CEDICT_VERSION = "cedict-v1"

    fun open(context: Context): SQLiteDatabase? = try {
        val dbFile = context.getDatabasePath(ASSET_NAME)
        val marker = File(dbFile.parentFile, "$ASSET_NAME.version")
        val upToDate = dbFile.exists() && dbFile.length() > 0 &&
            runCatching { marker.readText() == CEDICT_VERSION }.getOrDefault(false)
        if (!upToDate) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(ASSET_NAME).use { src ->
                FileOutputStream(dbFile).use { dst -> src.copyTo(dst) }
            }
            marker.writeText(CEDICT_VERSION)
        }
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
    } catch (_: Exception) {
        null
    }
}
