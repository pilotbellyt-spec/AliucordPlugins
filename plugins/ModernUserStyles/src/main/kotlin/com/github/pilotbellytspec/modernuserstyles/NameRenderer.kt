package com.github.pilotbellytspec.modernuserstyles

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
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

        val nextLabel = label.cleanNameLabel()?.takeIf { allowDisplayName }
            ?: textView.text?.toString().cleanNameLabel()
            ?: return
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
        textView.requestLayout()
        (textView.parent as? View)?.requestLayout()

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

        val primary = roleGradient.primaryColor.takeIf { it != 0 } ?: return emptyList()
        val secondary = roleGradient.secondaryColor?.takeIf { it != 0 }
        val tertiary = roleGradient.tertiaryColor?.takeIf { it != 0 }

        return when {
            tertiary != null -> listOf(primary, secondary ?: primary, tertiary)
            secondary != null -> listOf(primary, secondary)
            else -> listOf(primary)
        }
    }

    fun renderReplyTextView(
        textView: TextView?,
        label: String?,
        style: DisplayStyleData?,
        roleGradient: RoleGradient?,
        allowDisplayName: Boolean,
        allowNameStyle: Boolean,
        allowRoleGradient: Boolean,
    ) {
        renderTextView(textView, label, style, roleGradient, allowDisplayName, allowNameStyle, allowRoleGradient)
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
        val main = DiscordRoleGradient.opaque(colors.firstOrNull() ?: 0xffffff)

        textView.paint.shader = null
        textView.paint.clearShadowLayer()
        textView.paint.isFakeBoldText = false
        textView.setTextColor(main)

        val shouldGradient = colors.size > 1 ||
            effectId == DisplayNameCatalog.Effect.GRADIENT ||
            effectId == DisplayNameCatalog.Effect.TEST_2 ||
            effectId == DisplayNameCatalog.Effect.TEST_4

        if (shouldGradient) {
            val directColors = DiscordRoleGradient.shaderColors(colors)
            val width = DiscordRoleGradient.periodPx(textView.resources.displayMetrics.density)
            textView.paint.shader = LinearGradient(
                0f,
                0f,
                width,
                0f,
                directColors,
                DiscordRoleGradient.positions(directColors.size),
                Shader.TileMode.REPEAT,
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

    private fun brighten(color: Int, amount: Float): Int =
        ColorUtils.blendARGB(color, Color.WHITE, amount)

    private fun darken(color: Int, amount: Float): Int =
        ColorUtils.blendARGB(color, Color.BLACK, amount)

    private fun String?.cleanNameLabel(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}
