package com.example.comiclab.ui.privacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.comiclab.R

internal class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3 * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val accent = ContextCompat.getColor(context, R.color.comiclab_accent)
    private val idleColor = ContextCompat.getColor(context, R.color.comiclab_icon)
    private val errorColor = ContextCompat.getColor(context, R.color.comiclab_danger)
    private val selected = ArrayList<Int>(9)
    private var tracking = false
    private var error = false
    private var lastX = 0f
    private var lastY = 0f
    private val reset = Runnable { clearPattern() }
    var onPatternComplete: (List<Int>) -> Unit = {}
    var onPatternTooShort: () -> Unit = {}

    init {
        isSaveEnabled = false
        isClickable = true
        contentDescription = context.getString(R.string.privacy_draw_pattern)
    }

    private val spacing: Float get() = minOf(width, height) / 3f
    private fun x(node: Int) = width / 2f + (node % 3 - 1) * spacing
    private fun y(node: Int) = height / 2f + (node / 3 - 1) * spacing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activeColor = if (error) errorColor else accent
        paint.color = activeColor
        for (index in 1 until selected.size) {
            val start = selected[index - 1]
            val end = selected[index]
            canvas.drawLine(x(start), y(start), x(end), y(end), paint)
        }
        if (tracking && selected.isNotEmpty()) {
            val end = selected.last()
            canvas.drawLine(x(end), y(end), lastX, lastY, paint)
        }
        for (node in 0..8) {
            val active = node in selected
            paint.color = if (active) activeColor else idleColor
            canvas.drawCircle(x(node), y(node), spacing * if (active) .105f else .065f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || spacing <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPattern()
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                sample(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                for (index in 0 until event.historySize) {
                    sample(event.getHistoricalX(index), event.getHistoricalY(index))
                }
                sample(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                sample(event.x, event.y)
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (selected.size >= 4) {
                    performClick()
                    onPatternComplete(selected.toList())
                } else {
                    showErrorState()
                    onPatternTooShort()
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clearPattern()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun sample(nextX: Float, nextY: Float) {
        val dx = nextX - lastX
        val dy = nextY - lastY
        val lengthSquared = dx * dx + dy * dy
        val radius = spacing * .24f
        val hits = (0..8).mapNotNull { node ->
            if (node in selected) return@mapNotNull null
            val ox = x(node) - lastX
            val oy = y(node) - lastY
            val time = if (lengthSquared == 0f) 0f else ((ox * dx + oy * dy) / lengthSquared).coerceIn(0f, 1f)
            val rx = ox - time * dx
            val ry = oy - time * dy
            if (rx * rx + ry * ry <= radius * radius) node to time else null
        }
        hits.sortedBy { it.second }.forEach { selected.add(it.first) }
        lastX = nextX
        lastY = nextY
        invalidate()
    }

    fun showErrorState() {
        error = true
        invalidate()
        removeCallbacks(reset)
        postDelayed(reset, 450)
    }

    fun clearPattern() {
        removeCallbacks(reset)
        selected.clear()
        error = false
        if (tracking) parent?.requestDisallowInterceptTouchEvent(false)
        tracking = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        clearPattern()
        super.onDetachedFromWindow()
    }
}
