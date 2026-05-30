package com.github.pilotbellytspec.modernuserstyles

import android.graphics.Color
import androidx.core.graphics.ColorUtils

object DisplayNameWebEffect {
    private const val BACKGROUND_BASE_LOW = 0x111214
    private const val TOON_CONTRAST_BACKGROUND = 0x333333

    data class DerivedColors(
        val main: Int,
        val light1: Int,
        val light2: Int,
        val dark1: Int,
        val dark2: Int,
        val toonStroke: Int,
        val neonStroke: Int,
    )

    fun correctedColors(colors: List<Int>, effectId: Int): List<Int> {
        val ratio = when (effectId) {
            DisplayNameCatalog.Effect.GRADIENT,
            DisplayNameCatalog.Effect.GLOW -> 2.5
            else -> 3.0
        }
        val background = if (effectId == DisplayNameCatalog.Effect.TOON) {
            TOON_CONTRAST_BACKGROUND
        } else {
            BACKGROUND_BASE_LOW
        }
        return colors.map { ensureContrast(DiscordRoleGradient.opaque(it), background, ratio) }
    }

    fun derive(mainColor: Int): DerivedColors {
        val main = DiscordRoleGradient.opaque(mainColor)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(main, hsl)

        val saturation = hsl[1]
        val lightness = hsl[2]
        val neonSaturation = (1.2f * saturation).coerceAtMost(1f)
        val neonLightness = (lightness + 0.1f).coerceAtMost(0.6f)

        return DerivedColors(
            main = main,
            light1 = withLightness(hsl, (1.2f * lightness).coerceAtMost(1f)),
            light2 = withLightness(hsl, (1.6f * lightness).coerceAtMost(1f)),
            dark1 = withLightness(hsl, (0.6f * lightness).coerceAtLeast(0f)),
            dark2 = withLightness(hsl, (0.2f * lightness).coerceAtLeast(0f)),
            toonStroke = withLightness(hsl, (0.4f * lightness).coerceAtLeast(0.12f)),
            neonStroke = withHsl(hsl[0], neonSaturation, neonLightness),
        )
    }

    fun isProfileEffect(effectId: Int): Boolean = when (effectId) {
        DisplayNameCatalog.Effect.SOLID,
        DisplayNameCatalog.Effect.GRADIENT,
        DisplayNameCatalog.Effect.NEON,
        DisplayNameCatalog.Effect.TOON,
        DisplayNameCatalog.Effect.POP,
        DisplayNameCatalog.Effect.GLOW,
        DisplayNameCatalog.Effect.TEST_1,
        DisplayNameCatalog.Effect.TEST_2,
        DisplayNameCatalog.Effect.TEST_3,
        DisplayNameCatalog.Effect.TEST_4 -> true
        else -> false
    }

    private fun ensureContrast(color: Int, background: Int, ratio: Double): Int {
        var next = color
        val hsl = FloatArray(3)
        val darkBackground = ColorUtils.calculateLuminance(DiscordRoleGradient.opaque(background)) <= 0.5

        repeat(10) {
            if (ColorUtils.calculateContrast(next, DiscordRoleGradient.opaque(background)) >= ratio) return next
            ColorUtils.colorToHSL(next, hsl)
            val lightness = hsl[2]
            hsl[2] = if (darkBackground) {
                if (lightness >= 0.95f) return next
                (lightness + 0.05f).coerceAtMost(1f)
            } else {
                if (lightness <= 0.05f) return next
                (lightness - 0.05f).coerceAtLeast(0f)
            }
            next = ColorUtils.HSLToColor(hsl)
        }

        return next
    }

    private fun withLightness(source: FloatArray, lightness: Float): Int =
        withHsl(source[0], source[1], lightness)

    private fun withHsl(hue: Float, saturation: Float, lightness: Float): Int =
        ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
}
