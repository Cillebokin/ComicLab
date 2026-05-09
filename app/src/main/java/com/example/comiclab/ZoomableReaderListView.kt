package com.example.comiclab

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.ListView
import kotlin.math.abs

class ZoomableReaderListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ListView(context, attrs, defStyleAttr) {

    var onTransformChanged: ((scale: Float, horizontalPanX: Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var zoomScale = MIN_ZOOM
    private var horizontalPanX = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isHorizontalPanning = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scaleDetector.onTouchEvent(event)

            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        if (zoomScale > MIN_ZOOM) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isHorizontalPanning = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (isHorizontalPanning || abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        isHorizontalPanning = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        setHorizontalPanX(horizontalPanX + dx)
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (isHorizontalPanning) {
                        isHorizontalPanning = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
            }
        }

        return super.onTouchEvent(event)
    }

    fun resetZoom() {
        setZoomScale(MIN_ZOOM)
    }

    private fun setZoomScale(value: Float) {
        val newScale = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newScale == zoomScale) {
            return
        }

        zoomScale = newScale
        horizontalPanX = if (zoomScale == MIN_ZOOM) {
            0f
        } else {
            horizontalPanX.coerceIn(-maxHorizontalPanX(), maxHorizontalPanX())
        }
        dispatchTransformChanged()
    }

    private fun setHorizontalPanX(value: Float) {
        val newPanX = value.coerceIn(-maxHorizontalPanX(), maxHorizontalPanX())
        if (newPanX == horizontalPanX) {
            return
        }

        horizontalPanX = newPanX
        dispatchTransformChanged()
    }

    private fun maxHorizontalPanX(): Float {
        return ((width * zoomScale - width) / 2f).coerceAtLeast(0f)
    }

    private fun dispatchTransformChanged() {
        onTransformChanged?.invoke(zoomScale, horizontalPanX)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoomScale(zoomScale * detector.scaleFactor)
            return true
        }
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 3f
    }
}
