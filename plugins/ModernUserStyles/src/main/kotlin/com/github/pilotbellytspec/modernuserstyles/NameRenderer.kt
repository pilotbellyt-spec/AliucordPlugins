package com.github.pilotbellytspec.modernuserstyles

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.github.khoben.woff2android.Woff2Typeface
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import java.util.WeakHashMap

class NameRenderer(private val context: Context) {
    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val originalScaleX = WeakHashMap<TextView, Float>()
    private val originalLetterSpacing = WeakHashMap<TextView, Float>()
    private val loadedFonts = mutableMapOf<Int, Typeface?>()
    private val resources = PluginZipResources(context)
    private val woff2Typeface by lazy {
        runCatching {
            resources.loadNativeDecoder()
            Woff2Typeface.Initializer().create(context.applicationContext)
            Woff2Typeface.get()
        }.getOrNull()
    }

    fun renderTextView(
        textView: TextView?,
        label: String?,
        style: DisplayStyleData?,
        roleGradient: RoleGradient?,
        allowDisplayName: Boolean,
        allowNameStyle: Boolean,
        allowRoleGradient: Boolean,
    ) {
        if (textView == null) return

        val nextLabel = label?.takeIf { allowDisplayName && it.trim().isNotEmpty() } ?: textView.text?.toString() ?: return
        val colors = colorsFor(roleGradient, allowRoleGradient)
        val fontId = if (allowNameStyle) style?.fontId else null

        originalTypefaces.putIfAbsent(textView, textView.typeface)
        originalScaleX.putIfAbsent(textView, textView.textScaleX)
        originalLetterSpacing.putIfAbsent(textView, textView.letterSpacing)
        textView.paint.shader = null
        textView.paint.clearShadowLayer()
        textView.paint.isFakeBoldText = false
        textView.paint.textSkewX = 0f
        textView.textScaleX = originalScaleX[textView] ?: 1f
        val originalLetterSpacing = originalLetterSpacing[textView] ?: 0f
        textView.letterSpacing = DisplayNameCatalog.letterSpacing(fontId, originalLetterSpacing)
        textView.setTextColor(Color.WHITE)
        textView.typeface = exactTypeface(fontId) ?: DisplayNameCatalog.typeface(fontId, originalTypefaces[textView])
        textView.text = nextLabel

        if (colors.isNotEmpty() && nextLabel.isNotEmpty()) {
            applyDirectStyle(textView, nextLabel, colors, effectForRoleColors(colors))
            textView.post {
                if (textView.text?.toString() == nextLabel) {
                    applyDirectStyle(textView, nextLabel, colors, effectForRoleColors(colors))
                }
            }
        }
    }

    fun renderDrawee(
        textView: SimpleDraweeSpanTextView?,
        label: String?,
        style: DisplayStyleData?,
        roleGradient: RoleGradient?,
        allowDisplayName: Boolean,
        allowNameStyle: Boolean,
        allowRoleGradient: Boolean,
    ) {
        renderTextView(textView, label, style, roleGradient, allowDisplayName, allowNameStyle, allowRoleGradient)
    }

    fun colorsFor(
        roleGradient: RoleGradient?,
        allowRoleGradient: Boolean,
    ): List<Int> {
        if (!allowRoleGradient || roleGradient == null) return emptyList()

        return listOfNotNull(
            roleGradient.primaryColor,
            roleGradient.secondaryColor,
            roleGradient.tertiaryColor,
        ).distinct()
    }

    fun effectForRoleColors(colors: List<Int>): Int =
        if (colors.size > 1) DisplayNameCatalog.Effect.GRADIENT else DisplayNameCatalog.Effect.SOLID

    private fun exactTypeface(fontId: Int?): Typeface? {
        fontId ?: return null
        return loadedFonts.getOrPut(fontId) {
            val path = DisplayNameCatalog.zipFontPath(fontId) ?: return@getOrPut null
            runCatching {
                val bytes = resources.readEntry(path) ?: return@runCatching null
                woff2Typeface?.createFromBytes(bytes)
            }.getOrNull()
        }
    }

    private fun applyDirectStyle(textView: TextView, label: String, colors: List<Int>, effectId: Int) {
        val main = enhance((colors.firstOrNull() ?: 0xffffff) or Color.BLACK)
        val gradientColors = colors.map { enhance(it or Color.BLACK) }.distinct()

        textView.paint.shader = null
        textView.paint.clearShadowLayer()
        textView.paint.isFakeBoldText = false
        textView.setTextColor(main)

        val shouldGradient = gradientColors.size > 1 ||
            effectId == DisplayNameCatalog.Effect.GRADIENT ||
            effectId == DisplayNameCatalog.Effect.GLOW ||
            effectId == DisplayNameCatalog.Effect.TEST_2 ||
            effectId == DisplayNameCatalog.Effect.TEST_4

        if (shouldGradient) {
            val directColors = when {
                gradientColors.size > 1 -> spreadStops(gradientColors)
                else -> listOf(main, brighten(main, 0.45f))
            }
            val width = textView.paint.measureText(label).coerceAtLeast(textView.textSize * 2f)
            textView.paint.shader = LinearGradient(
                0f,
                0f,
                width,
                0f,
                directColors.toIntArray(),
                positionsFor(directColors.size),
                Shader.TileMode.CLAMP,
            )
        }

        when (effectId) {
            DisplayNameCatalog.Effect.NEON -> {
                textView.paint.setShadowLayer(textView.textSize * 0.28f, 0f, 0f, brighten(main, 0.55f))
            }
            DisplayNameCatalog.Effect.TOON -> {
                textView.paint.setShadowLayer(2.0f, 1.5f, 1.5f, darken(main, 0.72f))
            }
            DisplayNameCatalog.Effect.POP,
            DisplayNameCatalog.Effect.TEST_3 -> {
                textView.paint.setShadowLayer(2.4f, 1.8f, 1.8f, darken(main, 0.78f))
            }
            DisplayNameCatalog.Effect.GLOW -> {
                textView.paint.setShadowLayer(textView.textSize * 0.22f, 0f, 0f, brighten(main, 0.45f))
            }
        }

        textView.invalidate()
    }

    private fun spreadStops(source: List<Int>): List<Int> {
        if (source.size == 2) {
            return listOf(
                brighten(source[0], 0.22f),
                source[0],
                source[1],
                brighten(source[1], 0.28f),
            )
        }

        val result = mutableListOf<Int>()
        source.forEachIndexed { index, color ->
            result.add(if (index % 2 == 0) brighten(color, 0.16f) else darken(color, 0.16f))
            result.add(color)
        }
        return result
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
