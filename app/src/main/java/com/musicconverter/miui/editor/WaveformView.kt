package com.musicconverter.miui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.musicconverter.miui.ui.UiKit
import kotlin.math.max
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.SURFACE_2
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.BORDER
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.themedColor(context, "#5F687A", "#B6BEC9")
        style = Paint.Style.FILL
    }
    private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.themedColor(context, "#A99BFF", "#7B61E8")
        style = Paint.Style.FILL
    }
    private val playedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.ACCENT
        style = Paint.Style.FILL
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.themedColor(context, "#F7F8FB", "#171A21")
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.TEXT_3
        textSize = dp(12).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.themedColor(context, "#33FFFFFF", "#22000000")
        strokeWidth = dp(1).toFloat()
    }

    private var samples: List<Float> = emptyList()
    private var durationMs: Long = 0L
    private var startMs: Long = 0L
    private var endMs: Long = 0L
    private var playbackMs: Long? = null
    private var loading = false

    fun setLoading(value: Boolean) {
        loading = value
        invalidate()
    }

    fun setSamples(values: List<Float>) {
        samples = values.map { it.coerceIn(0.03f, 1f) }
        invalidate()
    }

    fun updateSelection(startMs: Long, endMs: Long, durationMs: Long) {
        this.startMs = startMs.coerceAtLeast(0L)
        this.endMs = endMs.coerceAtLeast(this.startMs)
        this.durationMs = durationMs.coerceAtLeast(0L)
        invalidate()
    }

    fun updatePlayback(positionMs: Long?) {
        playbackMs = positionMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(18).toFloat()
        val frame = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(frame, radius, radius, framePaint)
        canvas.drawRoundRect(frame, radius, radius, borderPaint)

        if (loading) {
            canvas.drawText("正在生成波形…", width / 2f, height / 2f, textPaint)
            return
        }

        if (samples.isEmpty()) {
            canvas.drawText("暂无波形数据", width / 2f, height / 2f, textPaint)
            return
        }

        val left = dp(12).toFloat()
        val top = dp(12).toFloat()
        val right = width - dp(12).toFloat()
        val bottom = height - dp(12).toFloat()
        val centerY = (top + bottom) / 2f
        canvas.drawLine(left, centerY, right, centerY, baselinePaint)

        val innerWidth = right - left
        val innerHeight = bottom - top
        val barWidth = dp(3).toFloat()
        val gap = dp(1).toFloat()
        val step = barWidth + gap
        val maxBars = max(1, (innerWidth / step).toInt())
        val drawSamples = compress(samples, maxBars)

        val selectionStartRatio = if (durationMs > 0) startMs.toFloat() / durationMs else 0f
        val selectionEndRatio = if (durationMs > 0) endMs.toFloat() / durationMs else 1f
        val playbackRatio = if (durationMs > 0 && playbackMs != null) {
            (playbackMs!!.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            null
        }

        drawSamples.forEachIndexed { index, value ->
            val x = left + index * step
            val normalizedIndex = if (drawSamples.size <= 1) 0f else index.toFloat() / (drawSamples.size - 1).toFloat()
            val amplitude = max(value, 0.04f)
            val barHeight = max(dp(8).toFloat(), amplitude * innerHeight)
            val topY = centerY - barHeight / 2f
            val bottomY = centerY + barHeight / 2f
            val rect = RectF(x, topY, min(x + barWidth, right), bottomY)

            val paint = when {
                playbackRatio != null && normalizedIndex <= playbackRatio -> playedBarPaint
                normalizedIndex in selectionStartRatio..selectionEndRatio -> selectedBarPaint
                else -> barPaint
            }
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }

        if (playbackRatio != null) {
            val playheadX = left + innerWidth * playbackRatio
            canvas.drawLine(playheadX, top, playheadX, bottom, playheadPaint)
        }
    }

    private fun compress(values: List<Float>, targetCount: Int): List<Float> {
        if (values.size <= targetCount) return values
        val chunk = values.size.toFloat() / targetCount.toFloat()
        val result = ArrayList<Float>(targetCount)
        for (i in 0 until targetCount) {
            val start = (i * chunk).toInt().coerceAtMost(values.lastIndex)
            val end = (((i + 1) * chunk).toInt()).coerceAtMost(values.size)
            if (end <= start) {
                result += values[start]
            } else {
                var sum = 0f
                var count = 0
                for (j in start until end) {
                    sum += values[j]
                    count++
                }
                result += if (count == 0) values[start] else sum / count.toFloat()
            }
        }
        return result
    }

    private fun dp(value: Int): Int =
        UiKit.dp(context, value)
}
