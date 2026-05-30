package com.github.pilotbellytspec.modernuserstyles

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader

object DiscordRoleGradient {
    fun opaque(color: Int): Int = color or Color.BLACK

    fun profileShaderColors(colors: List<Int>): IntArray {
        val opaque = colors.map(::opaque)
        return when {
            opaque.size >= 3 -> intArrayOf(opaque[0], opaque[1], opaque[2])
            opaque.size == 2 -> intArrayOf(opaque[0], opaque[1])
            else -> intArrayOf(opaque.firstOrNull() ?: Color.WHITE)
        }
    }

    fun roleShaderColors(colors: List<Int>): IntArray {
        val opaque = colors.map(::opaque)
        return when {
            opaque.size >= 3 -> intArrayOf(opaque[0], opaque[1], opaque[2], opaque[0])
            opaque.size == 2 -> intArrayOf(opaque[0], opaque[1], opaque[0])
            else -> intArrayOf(opaque.firstOrNull() ?: Color.WHITE)
        }
    }

    fun profilePositions(size: Int): FloatArray? {
        if (size <= 1) return null
        return when (size) {
            2 -> floatArrayOf(0.1f, 0.9f)
            3 -> floatArrayOf(0.1f, 0.5f, 0.9f)
            else -> FloatArray(size) { index -> index.toFloat() / (size - 1).toFloat() }
        }
    }

    fun rolePositions(size: Int): FloatArray? {
        if (size <= 1) return null
        return FloatArray(size) { index -> index.toFloat() / (size - 1).toFloat() }
    }

    fun periodPx(density: Float): Float = 100f * density.coerceAtLeast(1f)

    fun profileShader(colors: List<Int>, width: Float, height: Float): LinearGradient {
        val directColors = profileShaderColors(colors)
        val safeWidth = width.coerceAtLeast(1f)
        val safeHeight = height.coerceAtLeast(1f)
        return LinearGradient(
            0f,
            0f,
            safeWidth,
            safeHeight,
            directColors,
            profilePositions(directColors.size),
            Shader.TileMode.CLAMP,
        )
    }

    fun roleShader(colors: List<Int>, density: Float): LinearGradient {
        val directColors = roleShaderColors(colors)
        val width = periodPx(density)
        return LinearGradient(
            0f,
            0f,
            width,
            0f,
            directColors,
            rolePositions(directColors.size),
            Shader.TileMode.REPEAT,
        )
    }
}
