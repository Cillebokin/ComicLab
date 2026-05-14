package com.example.comiclab

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.IdentityHashMap

class ReaderPageZoomLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onTap: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var scale = MIN_SCALE
    private var panX = 0f
    private var panY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var isDragging = false
    private var transformAnimator: ValueAnimator? = null
    private val baseChildSizes = IdentityHashMap<View, ChildBaseSize>()

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        if (scale > MIN_SCALE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    captureTouch(event)
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (isDragging || isPanGesture(dx, dy)) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            captureTouch(event)
            isDragging = false
        }

        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            lastTouchX = event.x
            lastTouchY = event.y
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(scale > MIN_SCALE)
                settleTransform()
            }
            return true
        }

        if (scale > MIN_SCALE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    captureTouch(event)
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (isDragging || isPanGesture(dx, dy)) {
                        isDragging = true
                        moveBy(dx, dy)
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val wasDragging = isDragging
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    settleTransform()
                    if (!wasDragging && isTap(event)) {
                        performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    settleTransform()
                    return true
                }
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_UP && isTap(event)) {
            performClick()
            return true
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTransform()
        applyTransform()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        clampTransform()
        applyTransform()
    }

    fun resetZoom() {
        transformAnimator?.cancel()
        scale = MIN_SCALE
        panX = 0f
        panY = 0f
        applyTransform()
    }

    fun refreshContentLayout() {
        baseChildSizes.clear()
        recordBaseChildSizes(this)
        clampTransform()
        applyTransform()
    }

    fun setZoomForDebug(targetScale: Float, targetPanX: Float, targetPanY: Float) {
        transformAnimator?.cancel()
        scale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        panX = targetPanX
        panY = targetPanY
        clampTransform()
        applyTransform()
    }

    private fun captureTouch(event: MotionEvent) {
        transformAnimator?.cancel()
        downTouchX = event.x
        downTouchY = event.y
        lastTouchX = event.x
        lastTouchY = event.y
    }

    private fun isTap(event: MotionEvent): Boolean {
        return abs(event.x - downTouchX) <= touchSlop &&
            abs(event.y - downTouchY) <= touchSlop
    }

    private fun isPanGesture(dx: Float, dy: Float): Boolean {
        return abs(dx) > touchSlop || abs(dy) > touchSlop
    }

    private fun moveBy(dx: Float, dy: Float) {
        panX = applyResistance(panX + dx, maxPanX(), width)
        panY = applyResistance(panY + dy, maxPanY(), height)
        applyTransform()
    }

    private fun applyResistance(value: Float, maxPan: Float, viewportSize: Int): Float {
        if (maxPan <= 0f) {
            return 0f
        }

        val absoluteValue = abs(value)
        if (absoluteValue <= maxPan) {
            return value
        }

        val overflow = absoluteValue - maxPan
        val maxOverscroll = viewportSize * OVERSCROLL_FRACTION
        val resistedValue = maxPan + (overflow * OVERSCROLL_RESISTANCE)
        val cappedValue = resistedValue.coerceAtMost(maxPan + maxOverscroll)
        return if (value < 0f) -cappedValue else cappedValue
    }

    private fun settleTransform() {
        val targetScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val targetPanX = panX.coerceIn(-maxPanX(targetScale), maxPanX(targetScale))
        val targetPanY = panY.coerceIn(-maxPanY(targetScale), maxPanY(targetScale))
        if (targetScale == scale && targetPanX == panX && targetPanY == panY) {
            return
        }

        val startScale = scale
        val startPanX = panX
        val startPanY = panY
        transformAnimator?.cancel()
        transformAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SETTLE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                scale = startScale + ((targetScale - startScale) * fraction)
                panX = startPanX + ((targetPanX - startPanX) * fraction)
                panY = startPanY + ((targetPanY - startPanY) * fraction)
                applyTransform()
            }
            start()
        }
    }

    private fun clampTransform() {
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        panX = panX.coerceIn(-maxPanX(), maxPanX())
        panY = panY.coerceIn(-maxPanY(), maxPanY())
    }

    private fun applyTransform() {
        resizeDescendantViews(this)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.scaleX = 1f
            child.scaleY = 1f
            child.translationX = panX
            child.translationY = panY
        }
    }

    private fun maxPanX(targetScale: Float = scale): Float {
        val content = largestVisibleDirectChildBaseSize() ?: return 0f
        return ((content.width * targetScale - width) / 2f).coerceAtLeast(0f)
    }

    private fun maxPanY(targetScale: Float = scale): Float {
        val content = largestVisibleDirectChildBaseSize() ?: return 0f
        return ((content.height * targetScale - height) / 2f).coerceAtLeast(0f)
    }

    private fun largestVisibleDirectChildBaseSize(): ChildBaseSize? {
        var bestSize: ChildBaseSize? = null
        var bestArea = 0L
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.VISIBLE) {
                continue
            }

            val size = baseChildSizes[child] ?: baseChildSize(child) ?: continue
            val area = size.width.toLong() * size.height.toLong()
            if (area > bestArea) {
                bestArea = area
                bestSize = size
            }
        }
        return bestSize
    }

    private fun recordBaseChildSizes(parent: ViewGroup) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            baseChildSize(child)?.let { baseChildSizes[child] = it }
            if (child is ViewGroup) {
                recordBaseChildSizes(child)
            }
        }
    }

    private fun resizeDescendantViews(parent: ViewGroup) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val baseSize = baseChildSizes[child] ?: baseChildSize(child)?.also {
                baseChildSizes[child] = it
            }
            if (baseSize != null) {
                resizeView(child, baseSize)
            }
            if (child is ViewGroup) {
                resizeDescendantViews(child)
            }
        }
    }

    private fun resizeView(view: View, baseSize: ChildBaseSize) {
        val layoutParams = view.layoutParams ?: return
        val targetWidth = (baseSize.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (baseSize.height * scale).roundToInt().coerceAtLeast(1)
        val targetLeftMargin = (baseSize.leftMargin * scale).roundToInt()
        val targetTopMargin = (baseSize.topMargin * scale).roundToInt()
        var changed = false
        if (layoutParams.width != targetWidth) {
            layoutParams.width = targetWidth
            changed = true
        }
        if (layoutParams.height != targetHeight) {
            layoutParams.height = targetHeight
            changed = true
        }
        if (layoutParams is ViewGroup.MarginLayoutParams) {
            if (layoutParams.leftMargin != targetLeftMargin) {
                layoutParams.leftMargin = targetLeftMargin
                changed = true
            }
            if (layoutParams.topMargin != targetTopMargin) {
                layoutParams.topMargin = targetTopMargin
                changed = true
            }
        }
        if (view.parent === this && layoutParams is FrameLayout.LayoutParams &&
            layoutParams.gravity != Gravity.CENTER
        ) {
            layoutParams.gravity = Gravity.CENTER
            changed = true
        }
        if (changed) {
            view.layoutParams = layoutParams
        }
    }

    private fun baseChildSize(view: View): ChildBaseSize? {
        val layoutParams = view.layoutParams
        val width = layoutParams?.width?.takeIf { it > 0 } ?: view.width
        val height = layoutParams?.height?.takeIf { it > 0 } ?: view.height
        if (width <= 0 || height <= 0) {
            return null
        }
        val marginLayoutParams = layoutParams as? ViewGroup.MarginLayoutParams
        return ChildBaseSize(
            width = width,
            height = height,
            leftMargin = marginLayoutParams?.leftMargin ?: 0,
            topMargin = marginLayoutParams?.topMargin ?: 0
        )
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            transformAnimator?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scale
            val newScale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            if (newScale == oldScale) {
                return true
            }

            val focusX = detector.focusX - width / 2f
            val focusY = detector.focusY - height / 2f
            val scaleFactor = newScale / oldScale
            panX = ((panX - focusX) * scaleFactor) + focusX
            panY = ((panY - focusY) * scaleFactor) + focusY
            scale = newScale
            clampTransform()
            applyTransform()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            settleTransform()
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 3f

        private const val OVERSCROLL_FRACTION = 0.12f
        private const val OVERSCROLL_RESISTANCE = 0.28f
        private const val SETTLE_DURATION_MS = 140L
    }

    private data class ChildBaseSize(
        val width: Int,
        val height: Int,
        val leftMargin: Int,
        val topMargin: Int
    )
}
