package com.github.khoben.libwoff2dec

/**
 * Native WOFF2 decoder API used by woff2-android.
 *
 * This small wrapper mirrors khoben/woff2-android's Apache-2.0 decoder class so
 * the native library can be built from source instead of pulled from its AAR.
 */
object Woff2Decoder {
    init {
        System.loadLibrary("woff2decoder")
    }

    external fun decodeFile(inPath: String, outPath: String): Boolean

    external fun decodeBytes(inBytes: ByteArray): ByteArray?
}
