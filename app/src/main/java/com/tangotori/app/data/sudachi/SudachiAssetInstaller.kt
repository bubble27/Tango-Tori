package com.tangotori.app.data.sudachi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies the bundled SudachiDict from APK assets to internal storage on first launch.
 * Sudachi memory-maps a file path, so it cannot read directly from an APK asset stream.
 *
 * The dictionary lives at `assets/sudachi/system_core.dic` inside the APK (deflate-
 * compressed — see app/build.gradle.kts) and is copied exactly once to
 * `<filesDir>/sudachi/system_core.dic`.
 *
 * Staleness is tracked with a sibling marker file holding [DICT_VERSION]. The
 * previous check compared the on-disk size against `assets.openFd().length`, but
 * openFd() throws for compressed assets, and the fallback would have re-copied
 * the 206 MB dictionary on every launch. Bump [DICT_VERSION] whenever the bundled
 * dictionary file changes so existing installs re-copy it.
 */
class SudachiAssetInstaller(private val appContext: Context) {

    private val targetFile: File
        get() = File(appContext.filesDir, "sudachi/system_core.dic")

    private val versionMarker: File
        get() = File(appContext.filesDir, "sudachi/system_core.dic.version")

    fun isInstalled(): Boolean =
        targetFile.exists() && targetFile.length() > 0 && markerMatches()

    private fun markerMatches(): Boolean =
        runCatching { versionMarker.readText() == DICT_VERSION }.getOrDefault(false)

    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext
        val parent = targetFile.parentFile ?: error("filesDir missing")
        if (!parent.exists()) parent.mkdirs()

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
        versionMarker.writeText(DICT_VERSION)
    }

    companion object {
        const val ASSET_PATH = "sudachi/system_core.dic"

        /** Identity of the bundled dictionary. Bump when the asset changes. */
        const val DICT_VERSION = "sudachi-20230927-core"
    }
}
