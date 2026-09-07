package com.musicconverter.miui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.musicconverter.miui.ui.UiKit
import kotlin.math.abs
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
    private val selectionEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiKit.ACCENT
        style = Paint.Style.FILL
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

    private var selectionListener: ((Long, Long) -> Unit)? = null
    private var dragMode = DragMode.NONE
    private var downMs = 0L
    private var anchorMs = 0L
    private var initialStartMs = 0L
    private var initialEndMs = 0L

    private enum class DragMode {
        NONE, START, END, RANGE, NEW_RANGE
    }

    fun setLoading(value: Boolean) {
        loading = value
        invalidate()
    }

    fun setSamples(values: List<Float>) {
        samples = values.map { it.coerceIn(0.03f, 1f) }
        invalidate()
    }

    fun setOnSelectionChangeListener(listener: ((Long, Long) -> Unit)?) {
        selectionListener = listener
    }

    fun updateSelection(startMs: Long, endMs: Long, durationMs: Long) {
        this.durationMs = durationMs.coerceAtLeast(0L)
        this.startMs = startMs.coerceIn(0L, this.durationMs)
        this.endMs = endMs.coerceIn(this.startMs, this.durationMs)
        invalidate()
    }

    fun updatePlayback(positionMs: Long?) {
        playbackMs = positionMs
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0L || loading) return false

        val bounds = contentBounds()
        if (bounds.width() <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downMs = xToMs(event.x, bounds)
                anchorMs = downMs
                initialStartMs = startMs
                initialEndMs = endMs

                val handleHitMs = max(
                    100L,
                    (durationMs * (dp(26).toFloat() / bounds.width())).toLong()
                )

                dragMode = when {
                    abs(downMs - startMs) <= handleHitMs -> DragMode.START
                    abs(downMs - endMs) <= handleHitMs -> DragMode.END
                    downMs in startMs..endMs && endMs > startMs -> DragMode.RANGE
                    else -> DragMode.NEW_RANGE
                }

                if (dragMode == DragMode.NEW_RANGE) {
                    setSelectionFromTouch(anchorMs, anchorMs)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val touchMs = xToMs(event.x, bounds)
                val minRange = minRangeMs()

                when (dragMode) {
                    DragMode.START -> {
                        startMs = touchMs.coerceIn(
                            0L,
                            (endMs - minRange).coerceAtLeast(0L)
                        )
                    }

                    DragMode.END -> {
                        endMs = touchMs.coerceIn(
                            (startMs + minRange).coerceAtMost(durationMs),
                            durationMs
                        )
                    }

                    DragMode.RANGE -> {
                        val length = (initialEndMs - initialStartMs).coerceAtLeast(minRange)
                        val delta = touchMs - downMs
                        var newStart = initialStartMs + delta
                        var newEnd = initialEndMs + delta

                        if (newStart < 0L) {
                            newEnd -= newStart
                            newStart = 0L
                        }
                        if (newEnd > durationMs) {
                            val overflow = newEnd - durationMs
                            newStart -= overflow
                            newEnd = durationMs
                        }

                        startMs = newStart.coerceIn(0L, (durationMs - length).coerceAtLeast(0L))
                        endMs = (startMs + length).coerceAtMost(durationMs)
                    }

                    DragMode.NEW_RANGE -> setSelectionFromTouch(anchorMs, touchMs)
                    DragMode.NONE -> Unit
                }

                notifySelectionChanged()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode == DragMode.NEW_RANGE && endMs - startMs < minRangeMs()) {
                    val center = xToMs(event.x, bounds)
                    val half = minRangeMs() / 2L
                    startMs = (center - half).coerceAtLeast(0L)
                    endMs = (startMs + minRangeMs()).coerceAtMost(durationMs)
                    if (endMs - startMs < minRangeMs()) {
                        startMs = (endMs - minRangeMs()).coerceAtLeast(0L)
                    }
                }
                notifySelectionChanged()
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
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
            canvas.drawText(
                if (durationMs > 0) "暂无波形数据" else "选择音频后显示波形",
                width / 2f,
                height / 2f,
                textPaint
            )
            return
        }

        val bounds = contentBounds()
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
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
            val normalizedIndex = if (drawSamples.size <= 1) {
                0f
            } else {
                index.toFloat() / (drawSamples.size - 1).toFloat()
            }
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

        if (durationMs > 0L) {
            val startX = left + innerWidth * selectionStartRatio.coerceIn(0f, 1f)
            val endX = left + innerWidth * selectionEndRatio.coerceIn(0f, 1f)
            canvas.drawLine(startX, top, startX, bottom, selectionEdgePaint)
            canvas.drawLine(endX, top, endX, bottom, selectionEdgePaint)
            canvas.drawCircle(startX, centerY, dp(5).toFloat(), selectionHandlePaint)
            canvas.drawCircle(endX, centerY, dp(5).toFloat(), selectionHandlePaint)
        }

        if (playbackRatio != null) {
            val playheadX = left + innerWidth * playbackRatio
            canvas.drawLine(playheadX, top, playheadX, bottom, playheadPaint)
        }
    }

    private fun setSelectionFromTouch(a: Long, b: Long) {
        startMs = min(a, b).coerceIn(0L, durationMs)
        endMs = max(a, b).coerceIn(0L, durationMs)
    }

    private fun notifySelectionChanged() {
        invalidate()
        selectionListener?.invoke(startMs, endMs)
    }

    private fun minRangeMs(): Long = min(100L, durationMs.coerceAtLeast(1L))

    private fun xToMs(x: Float, bounds: RectF): Long {
        val ratio = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        return (ratio * durationMs).toLong().coerceIn(0L, durationMs)
    }

    private fun contentBounds(): RectF = RectF(
        dp(12).toFloat(),
        dp(12).toFloat(),
        width - dp(12).toFloat(),
        height - dp(12).toFloat()
    )

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

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
