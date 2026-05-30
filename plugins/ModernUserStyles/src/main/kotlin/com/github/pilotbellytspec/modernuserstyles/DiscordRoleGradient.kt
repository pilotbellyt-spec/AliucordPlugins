package com.github.pilotbellytspec.modernuserstyles

import android.graphics.Color

object DiscordRoleGradient {
    fun opaque(color: Int): Int = color or Color.BLACK

    fun shaderColors(colors: List<Int>): IntArray {
        val opaque = colors.map(::opaque)
        return when {
            opaque.size >= 3 -> intArrayOf(opaque[0], opaque[1], opaque[2], opaque[0])
            opaque.size == 2 -> intArrayOf(opaque[0], opaque[1], opaque[0])
            else -> intArrayOf(opaque.firstOrNull() ?: Color.WHITE)
        }
    }

    fun positions(size: Int): FloatArray? {
        if (size <= 1) return null
        return FloatArray(size) { index -> index.toFloat() / (size - 1).toFloat() }
    }

    fun periodPx(density: Float): Float = 100f * density.coerceAtLeast(1f)
}
