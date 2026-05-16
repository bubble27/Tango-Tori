package com.tangotori.app.data.sudachi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the SudachiDict core to <filesDir>/sudachi/system_core.dic on first launch.
 * Emits progress as a percentage 0..100.
 *
 * The upstream SudachiDict releases live at WorksApplications/SudachiDict on GitHub.
 * We use the "core" dictionary (~50 MB unpacked); see [SUDACHI_DICT_URL].
 */
class FirstLaunchDictionaryDownloader(private val appContext: Context) {

    sealed interface Progress {
        data class Downloading(val percent: Int) : Progress
        data class Failed(val reason: String) : Progress
        data object Complete : Progress
    }

    val systemDictFile: File
        get() = File(appContext.filesDir, "sudachi/system_core.dic")

    fun isAlreadyDownloaded(): Boolean = systemDictFile.exists() && systemDictFile.length() > 0

    fun download(): Flow<Progress> = flow {
        if (isAlreadyDownloaded()) {
            emit(Progress.Complete); return@flow
        }
        val parent = systemDictFile.parentFile ?: error("filesDir missing")
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, "system_core.dic.part")
        if (tmp.exists()) tmp.delete()

        try {
            val conn = (URL(SUDACHI_DICT_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            conn.connect()
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloaded = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                emit(Progress.Downloading(pct))
                                lastPct = pct
                            }
                        }
                    }
                }
            }
            // Atomic move so a partial file never looks like a completed dict.
            if (!tmp.renameTo(systemDictFile)) {
                tmp.copyTo(systemDictFile, overwrite = true)
                tmp.delete()
            }
            emit(Progress.Complete)
        } catch (t: Throwable) {
            tmp.takeIf { it.exists() }?.delete()
            emit(Progress.Failed(t.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        // SudachiDict core (Apache 2.0). Pinned to a specific release for reproducibility.
        // If this URL 404s, swap to a later release tag from
        // https://github.com/WorksApplications/SudachiDict/releases
        const val SUDACHI_DICT_URL =
            "https://github.com/WorksApplications/SudachiDict/releases/download/v20230927/sudachi-dictionary-20230927-core.zip"
    }
}
