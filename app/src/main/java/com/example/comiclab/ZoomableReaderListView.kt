package com.example.comiclab

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ListView

class ZoomableReaderListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ListView(context, attrs, defStyleAttr) {

    var onZoomChanged: ((Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var zoomScale = MIN_ZOOM

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scaleDetector.onTouchEvent(event)

            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
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
        onZoomChanged?.invoke(zoomScale)
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
