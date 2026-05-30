package com.github.pilotbellytspec.modernuserstyles

import android.graphics.Color
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.content.res.Resources
import androidx.core.graphics.ColorUtils

class NameStyleSpan(
    private val colors: IntArray,
    private val effectId: Int,
    private val textLength: Int,
) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        val safeColors = colors.takeIf { it.isNotEmpty() } ?: return
        val main = DiscordRoleGradient.opaque(safeColors[0])

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
        val density = tp.density.takeIf { it > 0f } ?: Resources.getSystem().displayMetrics.density
        tp.shader = DiscordRoleGradient.roleShader(source.toList(), density)
    }

    private fun brighten(color: Int, amount: Float): Int =
        ColorUtils.blendARGB(color, Color.WHITE, amount)

    private fun darken(color: Int, amount: Float): Int =
        ColorUtils.blendARGB(color, Color.BLACK, amount)
}
