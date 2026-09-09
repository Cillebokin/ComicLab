package com.example.comiclab

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import java.util.IdentityHashMap

class ReaderPageZoomLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onTap: (() -> Unit)? = null
    var onZoomThresholdChanged: ((Boolean) -> Unit)? = null
    var onTransformChanged: (() -> Unit)? = null

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
    private var panAxis = PanGestureAxis.NONE
    private var zoomThresholdActive = false
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
                    resetPanGesture()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (isDragging || isPanGesture(dx, dy)) {
                        val axis = lockPanAxis(dx, dy)
                        val primaryDelta = primaryPanDelta(axis, dx, dy)
                        if (!canConsumePanGesture(axis, primaryDelta)) {
                            releaseParentForPageTurn(event)
                            return false
                        }
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    resetPanGesture()
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
            resetPanGesture()
        }

        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            lastTouchX = event.x
            lastTouchY = event.y
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                resetPanGesture()
                parent?.requestDisallowInterceptTouchEvent(scale > MIN_SCALE)
                settleTransform()
            }
            return true
        }

        if (scale > MIN_SCALE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    captureTouch(event)
                    resetPanGesture()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (isDragging || isPanGesture(dx, dy)) {
                        val axis = lockPanAxis(dx, dy)
                        val primaryDelta = primaryPanDelta(axis, dx, dy)
                        if (!canConsumePanGesture(axis, primaryDelta)) {
                            releaseParentForPageTurn(event)
                            return true
                        }
                        isDragging = true
                        moveBy(
                            dx = if (axis == PanGestureAxis.HORIZONTAL) dx else 0f,
                            dy = if (axis == PanGestureAxis.VERTICAL) dy else 0f
                        )
                        lastTouchX = event.x
                        lastTouchY = event.y
                        if (shouldReleaseParentAfterPan(axis, primaryDelta)) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        } else {
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        return true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val wasDragging = isDragging
                    resetPanGesture()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    settleTransform()
                    if (!wasDragging && isTap(event)) {
                        performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    resetPanGesture()
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
        resetPanGesture()
        applyTransform()
    }

    fun refreshContentLayout() {
        baseChildSizes.clear()
        recordBaseChildSizes(this)
        clampTransform()
        applyTransform()
    }

    /**
     * 将容器坐标系中的视口反向映射为当前内容子 View 的未变换坐标。
     * 分块阅读器使用该区域决定需要补载哪些源图片分块。
     */
    fun contentViewportForContainerRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): RectF? {
        if (scale <= 0f || right <= left || bottom <= top) {
            return null
        }

        val content = largestVisibleDirectChildBaseSize() ?: return null
        val baseBounds = childBaseBounds(content) ?: return null
        val pivotX = childPivotX(content)
        val pivotY = childPivotY(content)
        val originX = baseBounds.left + pivotX
        val originY = baseBounds.top + pivotY

        fun inverseX(value: Float): Float {
            return ((value - panX - originX) / scale) + pivotX
        }

        fun inverseY(value: Float): Float {
            return ((value - panY - originY) / scale) + pivotY
        }

        val contentLeft = inverseX(left)
        val contentRight = inverseX(right)
        val contentTop = inverseY(top)
        val contentBottom = inverseY(bottom)
        return RectF(
            minOf(contentLeft, contentRight),
            minOf(contentTop, contentBottom),
            maxOf(contentLeft, contentRight),
            maxOf(contentTop, contentBottom)
        )
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

    private fun resetPanGesture() {
        isDragging = false
        panAxis = PanGestureAxis.NONE
    }

    private fun lockPanAxis(dx: Float, dy: Float): PanGestureAxis {
        if (panAxis != PanGestureAxis.NONE || !isPanGesture(dx, dy)) {
            return panAxis
        }

        panAxis = if (abs(dx) >= abs(dy)) {
            PanGestureAxis.HORIZONTAL
        } else {
            PanGestureAxis.VERTICAL
        }
        return panAxis
    }

    private fun primaryPanDelta(axis: PanGestureAxis, dx: Float, dy: Float): Float {
        return when (axis) {
            PanGestureAxis.HORIZONTAL -> dx
            PanGestureAxis.VERTICAL -> dy
            PanGestureAxis.NONE -> 0f
        }
    }

    private fun canConsumePanGesture(axis: PanGestureAxis, delta: Float): Boolean {
        if (axis == PanGestureAxis.NONE || abs(delta) <= touchSlop) {
            return true
        }

        val bounds = panBounds()
        return when (axis) {
            PanGestureAxis.HORIZONTAL -> when {
                delta > 0f -> panX < bounds.maxX - PAN_EDGE_EPSILON
                delta < 0f -> panX > bounds.minX + PAN_EDGE_EPSILON
                else -> true
            }
            PanGestureAxis.VERTICAL -> when {
                delta > 0f -> panY < bounds.maxY - PAN_EDGE_EPSILON
                delta < 0f -> panY > bounds.minY + PAN_EDGE_EPSILON
                else -> true
            }
            PanGestureAxis.NONE -> true
        }
    }

    private fun shouldReleaseParentAfterPan(axis: PanGestureAxis, delta: Float): Boolean {
        if (axis == PanGestureAxis.NONE || abs(delta) <= touchSlop) {
            return false
        }

        val bounds = panBounds()
        return when (axis) {
            PanGestureAxis.HORIZONTAL -> when {
                delta > 0f -> panX >= bounds.maxX - PAN_EDGE_EPSILON
                delta < 0f -> panX <= bounds.minX + PAN_EDGE_EPSILON
                else -> false
            }
            PanGestureAxis.VERTICAL -> when {
                delta > 0f -> panY >= bounds.maxY - PAN_EDGE_EPSILON
                delta < 0f -> panY <= bounds.minY + PAN_EDGE_EPSILON
                else -> false
            }
            PanGestureAxis.NONE -> false
        }
    }

    private fun releaseParentForPageTurn(event: MotionEvent) {
        resetPanGesture()
        lastTouchX = event.x
        lastTouchY = event.y
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val bounds = panBounds()
        panX = applyResistance(panX + dx, bounds.minX, bounds.maxX, width)
        panY = applyResistance(panY + dy, bounds.minY, bounds.maxY, height)
        applyTransform()
    }

    private fun applyResistance(
        value: Float,
        minPan: Float,
        maxPan: Float,
        viewportSize: Int
    ): Float {
        if (minPan >= maxPan) {
            return 0f
        }

        if (value in minPan..maxPan) {
            return value
        }

        val maxOverscroll = viewportSize * OVERSCROLL_FRACTION
        if (value < minPan) {
            val overflow = minPan - value
            val resistedValue = minPan - (overflow * OVERSCROLL_RESISTANCE)
            return resistedValue.coerceAtLeast(minPan - maxOverscroll)
        }

        val overflow = value - maxPan
        val resistedValue = maxPan + (overflow * OVERSCROLL_RESISTANCE)
        return resistedValue.coerceAtMost(maxPan + maxOverscroll)
    }

    private fun settleTransform() {
        val targetScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val bounds = panBounds(targetScale)
        val targetPanX = panX.coerceIn(bounds.minX, bounds.maxX)
        val targetPanY = panY.coerceIn(bounds.minY, bounds.maxY)
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
        val bounds = panBounds()
        panX = panX.coerceIn(bounds.minX, bounds.maxX)
        panY = panY.coerceIn(bounds.minY, bounds.maxY)
    }

    private fun applyTransform() {
        // 缩放只改变绘制变换，避免手势过程中反复修改 LayoutParams 触发整棵 View 树重新布局。
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val baseSize = baseChildSizes[child] ?: baseChildSize(child)?.also {
                baseChildSizes[child] = it
            }
            if (baseSize != null) {
                child.pivotX = childPivotX(baseSize)
                child.pivotY = childPivotY(baseSize)
                child.scaleX = scale
                child.scaleY = scale
            } else {
                child.scaleX = 1f
                child.scaleY = 1f
            }
            child.translationX = panX
            child.translationY = panY
        }
        dispatchZoomThresholdChangedIfNeeded()
        onTransformChanged?.invoke()
    }

    private fun dispatchZoomThresholdChangedIfNeeded() {
        val active = scale >= ZOOM_QUALITY_THRESHOLD
        if (active == zoomThresholdActive) {
            return
        }

        zoomThresholdActive = active
        onZoomThresholdChanged?.invoke(active)
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

    /**
     * Calculates the actual panning interval after taking the child's gravity into account.
     * In double-page mode the child is attached to the inner edge, so the interval is not
     * necessarily symmetric around zero.
     */
    private fun panBounds(targetScale: Float = scale): PanBounds {
        if (width <= 0 || height <= 0) {
            return PanBounds.ZERO
        }
        val content = contentBounds(targetScale)
            ?: return PanBounds.ZERO

        val viewportLeft = paddingLeft.toFloat()
        val viewportTop = paddingTop.toFloat()
        val viewportRight = (width - paddingRight).toFloat()
        val viewportBottom = (height - paddingBottom).toFloat()
        val viewportWidth = (viewportRight - viewportLeft).coerceAtLeast(0f)
        val viewportHeight = (viewportBottom - viewportTop).coerceAtLeast(0f)

        val horizontalBounds = if (content.width > viewportWidth) {
            PanRange(
                min = viewportRight - content.right,
                max = viewportLeft - content.left
            )
        } else {
            PanRange.ZERO
        }
        val verticalBounds = if (content.height > viewportHeight) {
            PanRange(
                min = viewportBottom - content.bottom,
                max = viewportTop - content.top
            )
        } else {
            PanRange.ZERO
        }

        return PanBounds(
            minX = horizontalBounds.min.coerceAtMost(horizontalBounds.max),
            maxX = horizontalBounds.max.coerceAtLeast(horizontalBounds.min),
            minY = verticalBounds.min.coerceAtMost(verticalBounds.max),
            maxY = verticalBounds.max.coerceAtLeast(verticalBounds.min)
        )
    }

    private fun contentBounds(targetScale: Float): ContentBounds? {
        val content = largestVisibleDirectChildBaseSize() ?: return null
        val baseBounds = childBaseBounds(content) ?: return null
        val pivotX = childPivotX(content)
        val pivotY = childPivotY(content)
        val originX = baseBounds.left + pivotX
        val originY = baseBounds.top + pivotY
        return ContentBounds(
            left = originX + (0f - pivotX) * targetScale,
            top = originY + (0f - pivotY) * targetScale,
            right = originX + (content.width - pivotX) * targetScale,
            bottom = originY + (content.height - pivotY) * targetScale,
            transformOriginX = originX,
            transformOriginY = originY
        )
    }

    private fun childBaseBounds(content: ChildBaseSize): Rect? {
        if (width <= 0 || height <= 0 || content.width <= 0 || content.height <= 0) {
            return null
        }

        val container = Rect(
            paddingLeft + content.leftMargin,
            paddingTop + content.topMargin,
            width - paddingRight - content.rightMargin,
            height - paddingBottom - content.bottomMargin
        )
        return Rect().also { outBounds ->
            Gravity.apply(
                normalizedGravity(content.gravity),
                content.width,
                content.height,
                container,
                outBounds,
                layoutDirection
            )
        }
    }

    private fun normalizedGravity(gravity: Int): Int {
        return gravity.takeIf { it >= 0 } ?: (Gravity.TOP or Gravity.START)
    }

    private fun childPivotX(content: ChildBaseSize): Float {
        val absoluteGravity = Gravity.getAbsoluteGravity(
            normalizedGravity(content.gravity),
            layoutDirection
        )
        return when (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT -> content.width.toFloat()
            Gravity.CENTER_HORIZONTAL -> content.width / 2f
            else -> 0f
        }
    }

    private fun childPivotY(content: ChildBaseSize): Float {
        return when (normalizedGravity(content.gravity) and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> content.height.toFloat()
            Gravity.CENTER_VERTICAL -> content.height / 2f
            else -> 0f
        }
    }

    private fun recordBaseChildSizes(parent: ViewGroup) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            baseChildSize(child)?.let { baseChildSizes[child] = it }
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
        val frameLayoutParams = layoutParams as? FrameLayout.LayoutParams
        return ChildBaseSize(
            width = width,
            height = height,
            leftMargin = marginLayoutParams?.leftMargin ?: 0,
            topMargin = marginLayoutParams?.topMargin ?: 0,
            rightMargin = marginLayoutParams?.rightMargin ?: 0,
            bottomMargin = marginLayoutParams?.bottomMargin ?: 0,
            gravity = frameLayoutParams?.gravity ?: Gravity.NO_GRAVITY
        )
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            transformAnimator?.cancel()
            resetPanGesture()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scale
            val newScale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            if (newScale == oldScale) {
                return true
            }

            val scaleFactor = newScale / oldScale
            val oldContentBounds = contentBounds(oldScale)
            val newContentBounds = contentBounds(newScale)
            if (oldContentBounds != null && newContentBounds != null) {
                val contentXFromOrigin = (
                    detector.focusX - oldContentBounds.transformOriginX - panX
                    ) / oldScale
                val contentYFromOrigin = (
                    detector.focusY - oldContentBounds.transformOriginY - panY
                    ) / oldScale
                panX = detector.focusX - newContentBounds.transformOriginX -
                    (contentXFromOrigin * newScale)
                panY = detector.focusY - newContentBounds.transformOriginY -
                    (contentYFromOrigin * newScale)
            } else {
                val focusX = detector.focusX - width / 2f
                val focusY = detector.focusY - height / 2f
                panX = ((panX - focusX) * scaleFactor) + focusX
                panY = ((panY - focusY) * scaleFactor) + focusY
            }
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
        const val ZOOM_QUALITY_THRESHOLD = 1.5f

        private const val OVERSCROLL_FRACTION = 0.12f
        private const val OVERSCROLL_RESISTANCE = 0.28f
        private const val SETTLE_DURATION_MS = 140L
        private const val PAN_EDGE_EPSILON = 1f
    }

    private data class ChildBaseSize(
        val width: Int,
        val height: Int,
        val leftMargin: Int,
        val topMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int,
        val gravity: Int
    )

    private data class ContentBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val transformOriginX: Float,
        val transformOriginY: Float
    ) {
        val width: Float
            get() = right - left

        val height: Float
            get() = bottom - top
    }

    private data class PanRange(
        val min: Float,
        val max: Float
    ) {
        companion object {
            val ZERO = PanRange(0f, 0f)
        }
    }

    private data class PanBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    ) {
        companion object {
            val ZERO = PanBounds(0f, 0f, 0f, 0f)
        }
    }

    private enum class PanGestureAxis {
        NONE,
        HORIZONTAL,
        VERTICAL
    }
}
