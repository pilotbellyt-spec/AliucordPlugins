package com.github.pilotbellytspec.modernuserstyles

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import androidx.core.graphics.ColorUtils

class NameStyleSpan(
    private val colors: IntArray,
    private val effectId: Int,
    private val textLength: Int,
) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        val safeColors = colors.takeIf { it.isNotEmpty() } ?: return
        val main = safeColors[0] or Color.BLACK

        tp.shader = null
        tp.color = main

        when (effectId) {
            DisplayNameCatalog.Effect.GRADIENT,
            DisplayNameCatalog.Effect.GLOW,
            DisplayNameCatalog.Effect.TEST_2,
            DisplayNameCatalog.Effect.TEST_4 -> applyGradient(tp, safeColors)
        }

        when (effectId) {
            DisplayNameCatalog.Effect.NEON -> {
                tp.setShadowLayer(tp.textSize * 0.25f, 0f, 0f, brighten(main, 0.45f))
            }
            DisplayNameCatalog.Effect.TOON -> {
                tp.setShadowLayer(1.6f, 1.4f, 1.4f, darken(main, 0.55f))
            }
            DisplayNameCatalog.Effect.POP,
            DisplayNameCatalog.Effect.TEST_3 -> {
                tp.setShadowLayer(1.8f, 1.6f, 1.6f, darken(main, 0.68f))
            }
            DisplayNameCatalog.Effect.GLOW -> {
                tp.setShadowLayer(tp.textSize * 0.18f, 0f, 0f, brighten(main, 0.35f))
            }
        }
    }

    private fun applyGradient(tp: TextPaint, source: IntArray) {
        val base = source.map { enhance(it or Color.BLACK) }.distinct().toIntArray()
        val gradientColors = when {
            base.size > 1 -> spreadStops(base)
            else -> intArrayOf(base[0], brighten(base[0], 0.42f))
        }

        val width = (textLength.coerceAtLeast(1) * tp.textSize * 0.46f).coerceAtLeast(tp.textSize * 1.4f)
        tp.shader = LinearGradient(
            0f,
            0f,
            width,
            0f,
            gradientColors,
            positionsFor(gradientColors.size),
            Shader.TileMode.CLAMP,
        )
    }

    private fun spreadStops(source: IntArray): IntArray {
        if (source.size == 2) {
            return intArrayOf(
                brighten(source[0], 0.16f),
                source[0],
                source[1],
                brighten(source[1], 0.22f),
            )
        }

        val result = mutableListOf<Int>()
        source.forEachIndexed { index, color ->
            result.add(if (index % 2 == 0) brighten(color, 0.12f) else darken(color, 0.12f))
            result.add(color)
        }
        return result.toIntArray()
    }

    private fun positionsFor(size: Int): FloatArray? {
        if (size <= 1) return null
        val positions = FloatArray(size)
        var index = 0
        while (index < size) {
            positions[index] = index.toFloat() / (size - 1).toFloat()
            index++
        }
        return positions
    }

    private fun enhance(color: Int): Int {
        val luminance = ColorUtils.calculateLuminance(color)
        return when {
            luminance < 0.18 -> brighten(color, 0.28f)
            luminance > 0.82 -> darken(color, 0.16f)
            else -> color
        }
    }

    private fun brighten(color: Int, amount: Float): Int =
        ColorUtils.blendARGB(color, Color.WHITE, amount)

    private fun darken(color: Int, amount: Float): Int =
        ColorUtils.blendARGB(color, Color.BLACK, amount)
}
