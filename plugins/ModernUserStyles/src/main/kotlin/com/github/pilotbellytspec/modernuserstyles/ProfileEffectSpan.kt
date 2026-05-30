package com.github.pilotbellytspec.modernuserstyles

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.text.style.ReplacementSpan
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.abs

class ProfileEffectSpan(
    colors: List<Int>,
    private val effectId: Int,
) : ReplacementSpan() {
    private val correctedColors = DisplayNameWebEffect.correctedColors(colors, effectId)
    private val main = correctedColors.firstOrNull() ?: Color.WHITE
    private val derived = DisplayNameWebEffect.derive(main)
    var animationProgress: Float = 1f

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val label = text.subSequence(start, end).toString()
        return ceil(paint.measureText(label)).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val label = text.subSequence(start, end).toString()
        val textWidth = paint.measureText(label).coerceAtLeast(1f)
        val drawX = x
        val textTop = y + paint.ascent()
        val textBottom = y + paint.descent()
        val height = (textBottom - textTop).coerceAtLeast(paint.textSize)

        when (effectId) {
            DisplayNameCatalog.Effect.GRADIENT,
            DisplayNameCatalog.Effect.GLOW,
            DisplayNameCatalog.Effect.TEST_2,
            DisplayNameCatalog.Effect.TEST_4 -> drawGradient(canvas, label, drawX, y.toFloat(), textTop, height, textWidth, paint)
            DisplayNameCatalog.Effect.NEON -> drawNeon(canvas, label, drawX, y.toFloat(), paint)
            DisplayNameCatalog.Effect.TOON -> drawToon(canvas, label, drawX, y.toFloat(), textTop, height, paint)
            DisplayNameCatalog.Effect.POP,
            DisplayNameCatalog.Effect.TEST_3 -> drawPop(canvas, label, drawX, y.toFloat(), textTop, height, textWidth, paint)
            else -> drawSolid(canvas, label, drawX, y.toFloat(), paint)
        }
    }

    private fun drawSolid(canvas: Canvas, label: String, x: Float, baseline: Float, source: Paint) {
        val paint = copyPaint(source)
        paint.color = derived.main
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.clearShadowLayer()
        canvas.drawText(label, x, baseline, paint)
    }

    private fun drawGradient(
        canvas: Canvas,
        label: String,
        x: Float,
        baseline: Float,
        textTop: Float,
        height: Float,
        textWidth: Float,
        source: Paint,
    ) {
        val paint = copyPaint(source)
        val gradientColors = if (correctedColors.size > 1) {
            correctedColors
        } else {
            listOf(derived.main, derived.main)
        }
        paint.color = derived.main
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            x,
            textTop,
            x + textWidth,
            textTop + height,
            DiscordRoleGradient.profileShaderColors(gradientColors),
            DiscordRoleGradient.profilePositions(DiscordRoleGradient.profileShaderColors(gradientColors).size),
            Shader.TileMode.CLAMP,
        )
        paint.clearShadowLayer()
        canvas.drawText(label, x, baseline, paint)
    }

    private fun drawNeon(canvas: Canvas, label: String, x: Float, baseline: Float, source: Paint) {
        val stroke = copyPaint(source)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1f * density(source) + source.textSize * 0.04f
        stroke.color = derived.neonStroke
        stroke.shader = null
        stroke.clearShadowLayer()
        canvas.drawText(label, x, baseline, stroke)

        val fill = copyPaint(source)
        fill.style = Paint.Style.FILL
        fill.color = neonFillColor(animationProgress)
        fill.shader = null
        fill.setShadowLayer(4f * density(source) + source.textSize * 0.12f, 0f, 0f, derived.main)
        canvas.drawText(label, x, baseline, fill)
    }

    private fun drawToon(
        canvas: Canvas,
        label: String,
        x: Float,
        baseline: Float,
        textTop: Float,
        height: Float,
        source: Paint,
    ) {
        val strokeWidth = 1.6f * density(source) + source.textSize * 0.04f
        val stroke = copyPaint(source)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = strokeWidth
        stroke.color = derived.toonStroke
        stroke.shader = null
        stroke.clearShadowLayer()
        canvas.drawText(label, x, baseline, stroke)

        val fill = copyPaint(source)
        fill.style = Paint.Style.FILL
        fill.color = derived.main
        val gradientHeight = height * 4f
        val offset = -height * 3f * toonProgress(animationProgress)
        fill.shader = LinearGradient(
            x,
            textTop + offset,
            x,
            textTop + offset + gradientHeight,
            intArrayOf(
                Color.WHITE,
                derived.light2,
                derived.light1,
                derived.main,
                derived.light2,
                derived.main,
                Color.WHITE,
                derived.light2,
                derived.light1,
                derived.main,
            ),
            floatArrayOf(0f, 0.08f, 0.15f, 0.25f, 0.45f, 0.55f, 0.75f, 0.83f, 0.9f, 1f),
            Shader.TileMode.CLAMP,
        )
        fill.clearShadowLayer()
        canvas.drawText(label, x, baseline, fill)
    }

    private fun drawPop(
        canvas: Canvas,
        label: String,
        x: Float,
        baseline: Float,
        textTop: Float,
        height: Float,
        textWidth: Float,
        source: Paint,
    ) {
        val strokeWidth = 1.2f * density(source) + source.textSize * 0.04f
        val rawProgress = animationProgress.coerceIn(0f, 1f)
        val shadowProgress = popShadowGradientProgress(rawProgress)
        val offsetY = source.textSize * 0.08f
        val mainTranslateY = when {
            rawProgress < 0.18f -> lerp(0f, -source.textSize * 0.05f, cssEase(rawProgress / 0.18f, POP_X1, POP_Y1, POP_X2, POP_Y2))
            rawProgress < 0.35f -> lerp(-source.textSize * 0.05f, source.textSize * 0.08f, cssEase((rawProgress - 0.18f) / 0.17f, POP_X1, POP_Y1, POP_X2, POP_Y2))
            rawProgress < 0.5f -> lerp(source.textSize * 0.08f, 0f, cssEase((rawProgress - 0.35f) / 0.15f, POP_X1, POP_Y1, POP_X2, POP_Y2))
            else -> 0f
        }
        val shadowTranslateY = when {
            rawProgress < 0.18f -> lerp(offsetY, source.textSize * 0.13f, cssEase(rawProgress / 0.18f, POP_X1, POP_Y1, POP_X2, POP_Y2))
            rawProgress < 0.35f -> lerp(source.textSize * 0.13f, 0f, cssEase((rawProgress - 0.18f) / 0.17f, POP_X1, POP_Y1, POP_X2, POP_Y2))
            rawProgress < 0.5f -> lerp(0f, offsetY, cssEase((rawProgress - 0.35f) / 0.15f, POP_X1, POP_Y1, POP_X2, POP_Y2))
            else -> offsetY
        }
        val gradientStartX = lerp(x + textWidth, x, shadowProgress)
        val gradientStartY = lerp(textTop, textTop + height, shadowProgress)
        val gradientEndX = lerp(x, x + textWidth, shadowProgress)
        val gradientEndY = lerp(textTop + height, textTop, shadowProgress)

        val back = copyPaint(source)
        back.style = Paint.Style.FILL
        back.shader = LinearGradient(
            gradientStartX,
            gradientStartY,
            gradientEndX,
            gradientEndY,
            intArrayOf(
                derived.light1,
                derived.light1,
                derived.main,
                derived.main,
                derived.light1,
                derived.main,
                derived.main,
            ),
            floatArrayOf(0f, 0.06f, 0.2f, 0.5f, 0.56f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        back.clearShadowLayer()
        canvas.drawText(label, x, baseline + shadowTranslateY, back)

        val stroke = copyPaint(source)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = strokeWidth
        stroke.color = derived.dark2
        stroke.shader = null
        stroke.clearShadowLayer()
        canvas.drawText(label, x, baseline + mainTranslateY, stroke)

        val fill = copyPaint(source)
        fill.style = Paint.Style.FILL
        fill.color = Color.WHITE
        fill.shader = null
        fill.clearShadowLayer()
        canvas.drawText(label, x, baseline + mainTranslateY, fill)
    }

    private fun copyPaint(source: Paint): TextPaint =
        TextPaint(source).apply {
            isAntiAlias = source.isAntiAlias
            isSubpixelText = true
        }

    private fun horizontalInset(paint: Paint): Float = when (effectId) {
        DisplayNameCatalog.Effect.NEON -> 4f * density(paint) + paint.textSize * 0.12f
        DisplayNameCatalog.Effect.TOON -> 1.6f * density(paint) + paint.textSize * 0.04f
        DisplayNameCatalog.Effect.POP,
        DisplayNameCatalog.Effect.TEST_3 -> 1.2f * density(paint) + paint.textSize * 0.04f
        else -> 0f
    }

    private fun density(paint: Paint): Float =
        (paint as? TextPaint)?.density?.takeIf { it > 0f } ?: 1f

    private fun neonFillColor(progress: Float): Int {
        val p = progress.coerceIn(0f, 1f)
        val tint = derived.neonStroke
        return when {
            p < 0.15f -> Color.WHITE
            p < 0.16f -> blend(Color.WHITE, tint, (p - 0.15f) / 0.01f)
            p < 0.18f -> blend(tint, Color.WHITE, (p - 0.16f) / 0.02f)
            p < 0.20f -> Color.WHITE
            p < 0.22f -> blend(Color.WHITE, tint, (p - 0.20f) / 0.02f)
            p < 0.23f -> blend(tint, Color.WHITE, (p - 0.22f) / 0.01f)
            p < 0.25f -> Color.WHITE
            p < 0.28f -> blend(Color.WHITE, tint, (p - 0.25f) / 0.03f)
            p < 0.50f -> blend(tint, Color.WHITE, (p - 0.28f) / 0.22f)
            else -> Color.WHITE
        }
    }

    private fun toonProgress(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return when {
            p <= 0.05f -> 0f
            p >= 0.55f -> 1f
            else -> cssEase((p - 0.05f) / 0.5f, POP_X1, POP_Y1, POP_X2, POP_Y2)
        }
    }

    private fun popShadowGradientProgress(progress: Float): Float =
        if (progress >= 0.5f) 1f else cssEase(progress / 0.5f, POP_X1, POP_Y1, POP_X2, POP_Y2)

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    private fun blend(start: Int, end: Int, progress: Float): Int =
        ColorUtils.blendARGB(start, end, progress.coerceIn(0f, 1f))

    private fun cssEase(progress: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val x = progress.coerceIn(0f, 1f)
        var low = 0f
        var high = 1f
        var t = x
        repeat(10) {
            t = (low + high) * 0.5f
            val estimate = cubic(t, 0f, x1, x2, 1f)
            if (estimate < x) low = t else high = t
            if (abs(estimate - x) < 0.001f) return cubic(t, 0f, y1, y2, 1f)
        }
        return cubic(t, 0f, y1, y2, 1f).coerceIn(0f, 1f)
    }

    private fun cubic(t: Float, p0: Float, p1: Float, p2: Float, p3: Float): Float {
        val oneMinusT = 1f - t
        return oneMinusT * oneMinusT * oneMinusT * p0 +
            3f * oneMinusT * oneMinusT * t * p1 +
            3f * oneMinusT * t * t * p2 +
            t * t * t * p3
    }

    companion object {
        private const val POP_X1 = 0.44f
        private const val POP_Y1 = 0.29f
        private const val POP_X2 = 0.48f
        private const val POP_Y2 = 1f
    }
}
