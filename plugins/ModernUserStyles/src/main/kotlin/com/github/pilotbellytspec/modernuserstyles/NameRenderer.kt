package com.github.pilotbellytspec.modernuserstyles

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.github.khoben.woff2android.Woff2Typeface
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import java.util.WeakHashMap

class NameRenderer(private val context: Context) {
    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val originalScaleX = WeakHashMap<TextView, Float>()
    private val originalLetterSpacing = WeakHashMap<TextView, Float>()
    private val runningAnimations = WeakHashMap<TextView, ValueAnimator>()
    private val animationKeys = WeakHashMap<TextView, String>()
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
        allowDisplayStyleColors: Boolean = false,
    ) {
        if (textView == null) return

        val nextLabel = label.cleanNameLabel()?.takeIf { allowDisplayName }
            ?: textView.text?.toString().cleanNameLabel()
            ?: return
        val fontId = if (allowNameStyle) style?.fontId else null
        val styleColors = colorsForDisplayStyle(style, allowNameStyle && allowDisplayStyleColors)
        val colors = styleColors.ifEmpty { colorsFor(roleGradient, allowRoleGradient) }
        val useProfileEffect = styleColors.isNotEmpty() && DisplayNameWebEffect.isProfileEffect(style?.effectId ?: DisplayNameCatalog.Effect.SOLID)
        val effectId = if (styleColors.isNotEmpty()) {
            style?.effectId ?: effectForRoleColors(styleColors)
        } else {
            effectForRoleColors(colors)
        }

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
        if (!useProfileEffect) {
            textView.text = nextLabel
        }
        if (useProfileEffect) {
            textView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        textView.requestLayout()
        (textView.parent as? View)?.requestLayout()

        if (colors.isNotEmpty() && nextLabel.isNotEmpty()) {
            if (useProfileEffect) {
                applyProfileEffect(textView, nextLabel, colors, effectId)
            } else {
                stopProfileAnimation(textView)
                applyDirectStyle(textView, nextLabel, colors, effectId)
            }
            if (!useProfileEffect) {
                textView.post {
                    if (textView.text?.toString() == nextLabel) {
                        applyDirectStyle(textView, nextLabel, colors, effectId)
                    }
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

    fun colorsForDisplayStyle(
        style: DisplayStyleData?,
        allowDisplayStyleColors: Boolean,
    ): List<Int> {
        if (!allowDisplayStyleColors || style == null) return emptyList()
        return style.colors.mapNotNull { color ->
            color.takeIf { it != 0 }
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
                textView.paint.shader = DiscordRoleGradient.roleShader(colors, textView.resources.displayMetrics.density)
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

    private fun applyProfileEffect(textView: TextView, label: String, colors: List<Int>, effectId: Int) {
        textView.paint.shader = null
        textView.paint.clearShadowLayer()
        textView.paint.isFakeBoldText = false

        val correctedColors = DisplayNameWebEffect.correctedColors(colors, effectId)
        when (effectId) {
            DisplayNameCatalog.Effect.SOLID,
            DisplayNameCatalog.Effect.TEST_1 -> {
                stopProfileAnimation(textView)
                textView.text = label
                textView.setTextColor(correctedColors.firstOrNull() ?: Color.WHITE)
            }
            DisplayNameCatalog.Effect.GRADIENT,
            DisplayNameCatalog.Effect.GLOW,
            DisplayNameCatalog.Effect.TEST_2,
            DisplayNameCatalog.Effect.TEST_4 -> {
                stopProfileAnimation(textView)
                textView.text = label
                textView.setTextColor(correctedColors.firstOrNull() ?: Color.WHITE)
                val width = textView.paint.measureText(label)
                    .coerceAtLeast(textView.textSize * 2f)
                val height = textView.lineHeight.toFloat()
                    .coerceAtLeast(textView.textSize)
                val gradientColors = correctedColors.takeIf { it.size > 1 }
                    ?: correctedColors.firstOrNull()?.let { listOf(it, it) }
                    ?: listOf(Color.WHITE, Color.WHITE)
                textView.paint.shader = DiscordRoleGradient.profileShader(gradientColors, width, height)
            }
            else -> {
                val key = profileAnimationKey(label, colors, effectId)
                if (hasDiscordProfileAnimation(effectId) &&
                    animationKeys[textView] == key &&
                    currentProfileEffectSpan(textView, label) != null
                ) {
                    textView.invalidate()
                    return
                }

                textView.setTextColor(Color.WHITE)
                val styled = SpannableString(label)
                val span = ProfileEffectSpan(colors, effectId)
                styled.setSpan(span, 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                textView.text = styled
                maybeAnimateProfileEffect(textView, span, key, effectId)
            }
        }
        textView.invalidate()
    }

    private fun maybeAnimateProfileEffect(textView: TextView, span: ProfileEffectSpan, key: String, effectId: Int) {
        if (!hasDiscordProfileAnimation(effectId)) {
            stopProfileAnimation(textView)
            return
        }

        val running = runningAnimations[textView]
        if (animationKeys[textView] == key && running?.isRunning == true) return
        animationKeys[textView] = key

        runningAnimations.remove(textView)?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                span.animationProgress = animation.animatedValue as Float
                textView.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (runningAnimations[textView] == animation) {
                        runningAnimations.remove(textView)
                        animationKeys.remove(textView)
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (runningAnimations[textView] == animation) {
                        runningAnimations.remove(textView)
                    }
                }
            })
        }
        runningAnimations[textView] = animator
        animator.start()
    }

    private fun hasDiscordProfileAnimation(effectId: Int): Boolean =
        effectId == DisplayNameCatalog.Effect.NEON ||
            effectId == DisplayNameCatalog.Effect.TOON ||
            effectId == DisplayNameCatalog.Effect.POP

    private fun stopProfileAnimation(textView: TextView) {
        runningAnimations.remove(textView)?.cancel()
        animationKeys.remove(textView)
    }

    private fun profileAnimationKey(label: String, colors: List<Int>, effectId: Int): String =
        "$label:$effectId:${colors.joinToString(",")}"

    private fun currentProfileEffectSpan(textView: TextView, label: String): ProfileEffectSpan? {
        val text = textView.text
        if (text.toString() != label) return null
        return (text as? Spanned)
            ?.getSpans(0, text.length, ProfileEffectSpan::class.java)
            ?.firstOrNull()
    }

    private fun brighten(color: Int, amount: Float): Int =
        androidx.core.graphics.ColorUtils.blendARGB(color, Color.WHITE, amount)

    private fun darken(color: Int, amount: Float): Int =
        androidx.core.graphics.ColorUtils.blendARGB(color, Color.BLACK, amount)

    private fun String?.cleanNameLabel(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}
