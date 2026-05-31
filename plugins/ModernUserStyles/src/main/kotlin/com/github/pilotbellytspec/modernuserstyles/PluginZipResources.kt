package com.github.pilotbellytspec.modernuserstyles

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.zip.ZipFile

class PluginZipResources(private val context: Context) {
    private val pluginZip: File by lazy {
        File(Environment.getExternalStorageDirectory(), "Aliucord/plugins/ModernUserStyles.zip")
    }

    fun readEntry(path: String): ByteArray? =
        runCatching {
            ZipFile(pluginZip).use { zip ->
                val entry = zip.getEntry(path) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()

    fun extractEntry(path: String, cacheSubdir: String): File? {
        val outDir = File(context.cacheDir, "modern_user_styles/$cacheSubdir").apply { mkdirs() }
        val outFile = File(outDir, path.substringAfterLast('/'))
        if (!outFile.exists() || outFile.length() == 0L) {
            val bytes = readEntry(path) ?: return null
            outFile.writeBytes(bytes)
        }
        return outFile
    }
}
