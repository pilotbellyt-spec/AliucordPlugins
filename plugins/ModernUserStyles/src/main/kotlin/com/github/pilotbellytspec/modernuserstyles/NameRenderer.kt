package com.github.pilotbellytspec.modernuserstyles

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import java.util.WeakHashMap

class NameRenderer(private val context: Context) {
    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val originalScaleX = WeakHashMap<TextView, Float>()
    private val originalLetterSpacing = WeakHashMap<TextView, Float>()
    private val originalTextSizes = WeakHashMap<TextView, Float>()
    private val originalTextColors = WeakHashMap<TextView, ColorStateList>()
    private val originalCompoundDrawables = WeakHashMap<TextView, Array<Drawable?>>()
    private val originalCompoundDrawablePadding = WeakHashMap<TextView, Int>()
    private val runningAnimations = WeakHashMap<TextView, ValueAnimator>()
    private val animationKeys = WeakHashMap<TextView, String>()
    private val renderTokens = WeakHashMap<TextView, Int>()
    private val changedFonts = WeakHashMap<TextView, Boolean>()
    private val loadedFonts = mutableMapOf<Int, Typeface?>()
    private val resources = PluginZipResources(context)

    fun renderTextView(
        textView: TextView?,
        label: String?,
        style: DisplayStyleData?,
        roleGradient: RoleGradient?,
        allowDisplayName: Boolean,
        allowNameStyle: Boolean,
        allowRoleGradient: Boolean,
        allowDisplayStyleColors: Boolean = false,
        allowMultiline: Boolean = false,
        allowReplacementEffects: Boolean = true,
        preserveReplacementEffectText: Boolean = false,
        keepPlainColor: Boolean = false,
    ) {
        if (textView == null) return

        val nextLabel = label.cleanNameLabel()?.takeIf { allowDisplayName }
            ?: textView.text?.toString().cleanNameLabel()
            ?: return
        val renderToken = beginRender(textView)
        val plainColor = textView.textColors
        val fontId = if (allowNameStyle) style?.fontId else null
        val styleColors = colorsForDisplayStyle(style, allowNameStyle && allowDisplayStyleColors)
        val colors = styleColors.ifEmpty { colorsFor(roleGradient, allowRoleGradient) }
        val useProfileEffect = allowReplacementEffects &&
            styleColors.isNotEmpty() &&
            DisplayNameWebEffect.isProfileEffect(style?.effectId ?: DisplayNameCatalog.Effect.SOLID)
        val effectId = if (styleColors.isNotEmpty()) {
            style?.effectId ?: effectForRoleColors(styleColors)
        } else {
            effectForRoleColors(colors)
        }

        originalTypefaces.putIfAbsent(textView, textView.typeface)
        originalScaleX.putIfAbsent(textView, textView.textScaleX)
        originalLetterSpacing.putIfAbsent(textView, textView.letterSpacing)
        originalTextColors.putIfAbsent(textView, textView.textColors)
        textView.paint.shader = null
        textView.paint.clearShadowLayer()
        textView.paint.isFakeBoldText = false
        textView.paint.textSkewX = 0f
        textView.textScaleX = originalScaleX[textView] ?: 1f
        if (keepPlainColor && colors.isEmpty()) {
            textView.setTextColor(plainColor)
        } else {
            textView.setTextColor(Color.WHITE)
        }
        setNameFont(textView, fontId)
        if (allowMultiline) {
            textView.setSingleLine(false)
            textView.maxLines = 3
            textView.ellipsize = null
        } else if (useProfileEffect && preserveReplacementEffectText) {
            textView.maxLines = 1
            textView.ellipsize = null
        }
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
                    if (isCurrentRender(textView, renderToken) && textView.text?.toString() == nextLabel) {
                        applyDirectStyle(textView, nextLabel, colors, effectId)
                    }
                }
            }
        } else {
            stopProfileAnimation(textView)
            textView.setLayerType(View.LAYER_TYPE_NONE, null)
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
        if (roleGradient == null) return emptyList()

        val primary = roleGradient.primaryColor.takeIf { it != 0 } ?: return emptyList()
        if (!allowRoleGradient) return listOf(primary)

        val secondary = roleGradient.secondaryColor?.takeIf { it != 0 }
        val tertiary = roleGradient.tertiaryColor?.takeIf { it != 0 }

        return when {
            tertiary != null -> listOf(primary, secondary ?: primary, tertiary)
            secondary != null -> listOf(primary, secondary)
            else -> listOf(primary)
        }
    }

    fun renderTextViewAsDrawable(
        textView: TextView?,
        label: String?,
        style: DisplayStyleData?,
        roleGradient: RoleGradient?,
        allowDisplayName: Boolean,
        allowNameStyle: Boolean,
        allowRoleGradient: Boolean,
        allowDisplayStyleColors: Boolean = false,
        allowMultiline: Boolean = false,
    ) {
        if (textView == null) return

        val nextLabel = label.cleanNameLabel()?.takeIf { allowDisplayName }
            ?: textView.text?.toString().cleanNameLabel()
            ?: return
        val renderToken = beginRender(textView)
        val fontId = if (allowNameStyle) style?.fontId else null
        val styleColors = colorsForDisplayStyle(style, allowNameStyle && allowDisplayStyleColors)
        val roleColors = colorsFor(roleGradient, allowRoleGradient)
        val colors = styleColors.ifEmpty { roleColors }.ifEmpty { listOf(Color.WHITE) }
        val effectId = if (styleColors.isNotEmpty()) {
            style?.effectId ?: effectForRoleColors(styleColors)
        } else {
            effectForRoleColors(roleColors)
        }

        originalTypefaces.putIfAbsent(textView, textView.typeface)
        originalScaleX.putIfAbsent(textView, textView.textScaleX)
        originalLetterSpacing.putIfAbsent(textView, textView.letterSpacing)
        originalTextSizes.putIfAbsent(textView, textView.textSize)
        originalTextColors.putIfAbsent(textView, textView.textColors)
        originalCompoundDrawables.putIfAbsent(textView, textView.compoundDrawables.copyOf())
        originalCompoundDrawablePadding.putIfAbsent(textView, textView.compoundDrawablePadding)

        if (fontId == null && styleColors.isEmpty() && roleColors.isEmpty()) {
            resetTextView(textView)
            textView.text = nextLabel
            return
        }

        textView.paint.shader = null
        textView.paint.clearShadowLayer()
        textView.paint.isFakeBoldText = false
        textView.paint.textSkewX = 0f
        textView.textScaleX = originalScaleX[textView] ?: 1f
        setNameFont(textView, fontId)
        if (allowMultiline) {
            val h1TextSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                20f,
                textView.resources.displayMetrics,
            )
            if (textView.textSize < h1TextSize) {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, h1TextSize)
            }
        }
        textView.setTextColor(Color.TRANSPARENT)
        if (allowMultiline) {
            textView.setSingleLine(false)
            textView.maxLines = 3
        } else {
            textView.maxLines = 1
            textView.ellipsize = null
        }

        val drawable = ProfileNameDrawable(textView, nextLabel, colors, effectId)
        textView.text = ""
        textView.contentDescription = nextLabel
        textView.compoundDrawablePadding = 0
        textView.setCompoundDrawables(drawable, null, null, null)
        textView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        textView.requestLayout()
        textView.invalidate()

        maybeAnimateProfileDrawable(textView, drawable, profileAnimationKey(nextLabel, colors, effectId), effectId, renderToken)
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

    fun resetTextView(textView: TextView?) {
        textView ?: return
        beginRender(textView)
        stopProfileAnimation(textView)
        textView.paint.shader = null
        textView.paint.clearShadowLayer()
        textView.paint.isFakeBoldText = false
        textView.paint.textSkewX = 0f
        textView.textScaleX = originalScaleX[textView] ?: textView.textScaleX
        textView.letterSpacing = originalLetterSpacing[textView] ?: textView.letterSpacing
        originalTextSizes[textView]?.let { textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
        originalTypefaces[textView]?.let { textView.typeface = it }
        originalTextColors[textView]?.let { textView.setTextColor(it) }
        originalCompoundDrawables[textView]?.let { drawables ->
            textView.setCompoundDrawables(drawables[0], drawables[1], drawables[2], drawables[3])
        }
        originalCompoundDrawablePadding[textView]?.let { textView.compoundDrawablePadding = it }
        changedFonts.remove(textView)
        textView.setLayerType(View.LAYER_TYPE_NONE, null)
        textView.invalidate()
    }

    private fun setNameFont(textView: TextView, fontId: Int?) {
        val originalSpacing = originalLetterSpacing[textView] ?: textView.letterSpacing
        val changesFont = fontId != null && fontId != DisplayNameCatalog.Font.DEFAULT
        if (!changesFont) {
            if (changedFonts.remove(textView) == true) {
                textView.letterSpacing = originalSpacing
                originalTypefaces[textView]?.let { textView.typeface = it }
            }
            return
        }

        changedFonts[textView] = true
        textView.letterSpacing = DisplayNameCatalog.letterSpacing(fontId, originalSpacing)
        textView.typeface = exactTypeface(fontId) ?: DisplayNameCatalog.typeface(fontId, originalTypefaces[textView])
    }

    fun effectForRoleColors(colors: List<Int>): Int =
        if (colors.size > 1) DisplayNameCatalog.Effect.GRADIENT else DisplayNameCatalog.Effect.SOLID

    private fun exactTypeface(fontId: Int?): Typeface? {
        fontId ?: return null
        return loadedFonts.getOrPut(fontId) {
            val path = DisplayNameCatalog.zipFontPath(fontId) ?: return@getOrPut null
            runCatching {
                val file = resources.extractEntry(path, "fonts") ?: return@runCatching null
                Typeface.createFromFile(file)
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
                    currentProfileEffectSpans(textView, label).isNotEmpty()
                ) {
                    textView.invalidate()
                    return
                }

                textView.setTextColor(Color.WHITE)
                val styled = SpannableString(label)
                val spans = applyProfileEffectSpans(styled, label, colors, effectId)
                textView.text = styled
                maybeAnimateProfileEffect(textView, spans, key, effectId)
            }
        }
        textView.invalidate()
    }

    private fun applyProfileEffectSpans(
        styled: SpannableString,
        label: String,
        colors: List<Int>,
        effectId: Int,
    ): List<ProfileEffectSpan> {
        val spans = mutableListOf<ProfileEffectSpan>()
        var wordStart = -1
        var charIndex = 0
        while (charIndex <= label.length) {
            val isBoundary = charIndex == label.length || label[charIndex].isWhitespace()
            if (!isBoundary && wordStart == -1) {
                wordStart = charIndex
            } else if (isBoundary && wordStart != -1) {
                val span = ProfileEffectSpan(colors, effectId)
                styled.setSpan(span, wordStart, charIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spans.add(span)
                wordStart = -1
            }
            charIndex++
        }

        if (spans.isEmpty() && label.isNotEmpty()) {
            val span = ProfileEffectSpan(colors, effectId)
            styled.setSpan(span, 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spans.add(span)
        }
        return spans
    }

    private fun maybeAnimateProfileEffect(textView: TextView, spans: List<ProfileEffectSpan>, key: String, effectId: Int) {
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
                val progress = animation.animatedValue as Float
                spans.forEach { span -> span.animationProgress = progress }
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

    private fun maybeAnimateProfileDrawable(
        textView: TextView,
        drawable: ProfileNameDrawable,
        key: String,
        effectId: Int,
        renderToken: Int,
    ) {
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
                if (!isCurrentRender(textView, renderToken)) {
                    animation.cancel()
                    return@addUpdateListener
                }
                drawable.animationProgress = animation.animatedValue as Float
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

    private fun beginRender(textView: TextView): Int {
        val token = ((renderTokens[textView] ?: 0) + 1).let {
            if (it == Int.MAX_VALUE) 1 else it
        }
        renderTokens[textView] = token
        return token
    }

    private fun isCurrentRender(textView: TextView, token: Int): Boolean =
        renderTokens[textView] == token

    private fun currentProfileEffectSpans(textView: TextView, label: String): List<ProfileEffectSpan> {
        val currentText = textView.text
        if (currentText.toString() != label) return emptyList()
        return (currentText as? Spanned)
            ?.getSpans(0, currentText.length, ProfileEffectSpan::class.java)
            ?.toList()
            .orEmpty()
    }

    private fun brighten(color: Int, amount: Float): Int =
        androidx.core.graphics.ColorUtils.blendARGB(color, Color.WHITE, amount)

    private fun darken(color: Int, amount: Float): Int =
        androidx.core.graphics.ColorUtils.blendARGB(color, Color.BLACK, amount)

    private fun String?.cleanNameLabel(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private class ProfileNameDrawable(
        textView: TextView,
        private val label: String,
        colors: List<Int>,
        private val effectId: Int,
    ) : Drawable() {
        private val paint = TextPaint(textView.paint).apply {
            isAntiAlias = true
            isSubpixelText = true
            density = textView.resources.displayMetrics.density
        }
        private val span = ProfileEffectSpan(colors, effectId)
        private val density = textView.resources.displayMetrics.density
        private val inset = effectInset()
        private val textWidth = paint.measureText(label).coerceAtLeast(1f)
        private val fontMetrics = paint.fontMetrics
        private val textHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(textView.textSize)
        var animationProgress: Float = 1f
            set(value) {
                field = value
                invalidateSelf()
            }

        init {
            val width = (textWidth + inset * 2f).toInt().coerceAtLeast(1)
            val height = (textHeight + inset * 2f).toInt().coerceAtLeast(textView.lineHeight)
            setBounds(0, 0, width, height)
        }

        override fun draw(canvas: Canvas) {
            span.animationProgress = animationProgress
            val bounds = bounds
            val x = bounds.left + inset
            val baseline = bounds.top + inset - fontMetrics.ascent
            span.draw(
                canvas,
                label,
                0,
                label.length,
                x,
                bounds.top,
                baseline.toInt(),
                bounds.bottom,
                paint,
            )
        }

        override fun getIntrinsicWidth(): Int = bounds.width()

        override fun getIntrinsicHeight(): Int = bounds.height()

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android framework")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun effectInset(): Float = when (effectId) {
            DisplayNameCatalog.Effect.NEON -> 5f * density + paint.textSize * 0.15f
            DisplayNameCatalog.Effect.TOON -> 2f * density + paint.textSize * 0.08f
            DisplayNameCatalog.Effect.POP,
            DisplayNameCatalog.Effect.TEST_3 -> 2f * density + paint.textSize * 0.08f
            else -> 1f * density
        }
    }
}
