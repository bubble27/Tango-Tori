package com.tangotori.app.data.sudachi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies the bundled SudachiDict from APK assets to internal storage on first launch.
 * Sudachi memory-maps a file path, so it cannot read directly from an APK asset stream.
 *
 * The dictionary lives at `assets/sudachi/system_core.dic` inside the APK and is copied
 * exactly once to `<filesDir>/sudachi/system_core.dic`. On reinstall or app-data clear,
 * the copy is redone — the asset's byte length is the staleness check.
 */
class SudachiAssetInstaller(private val appContext: Context) {

    private val targetFile: File
        get() = File(appContext.filesDir, "sudachi/system_core.dic")

    fun isInstalled(): Boolean = targetFile.exists() && targetFile.length() > 0

    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        val parent = targetFile.parentFile ?: error("filesDir missing")
        if (!parent.exists()) parent.mkdirs()

        // Cheap staleness check: compare on-disk size to the asset size.
        val assetSize = runCatching {
            appContext.assets.openFd("sudachi/system_core.dic").use { it.length }
        }.getOrElse { -1L }
        if (assetSize > 0 && targetFile.exists() && targetFile.length() == assetSize) return@withContext

        val tmp = File(parent, "system_core.dic.part")
        if (tmp.exists()) tmp.delete()
        appContext.assets.open(ASSET_PATH).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        if (!tmp.renameTo(targetFile)) {
            tmp.copyTo(targetFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val ASSET_PATH = "sudachi/system_core.dic"
    }
}
