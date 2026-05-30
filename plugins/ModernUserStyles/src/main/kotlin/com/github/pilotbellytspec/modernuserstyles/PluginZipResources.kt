package com.github.pilotbellytspec.modernuserstyles

import android.content.Context
import android.os.Build
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

    fun loadNativeDecoder(): Boolean {
        val abi = runCatching {
            ZipFile(pluginZip).use { zip ->
                Build.SUPPORTED_ABIS.firstOrNull { abi ->
                    zip.getEntry("modern_user_styles/native/$abi/libwoff2decoder.so") != null
                }
            }
        }.getOrNull() ?: return false

        val outDir = File(context.cacheDir, "modern_user_styles/native/$abi").apply { mkdirs() }
        val outFile = File(outDir, "libwoff2decoder.so")
        if (!outFile.exists() || outFile.length() == 0L) {
            val bytes = readEntry("modern_user_styles/native/$abi/libwoff2decoder.so") ?: return false
            outFile.writeBytes(bytes)
        }

        return runCatching {
            System.load(outFile.absolutePath)
            true
        }.getOrDefault(false)
    }
}
