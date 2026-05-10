package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

class ReaderSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SubsamplingScaleImageView(context, attrs) {

    var onReaderTap: (() -> Unit)? = null

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onReaderTap?.invoke()
                return true
            }
        }
    )

    init {
        setBackgroundColor(Color.BLACK)
        setMinimumScaleType(SCALE_TYPE_CENTER_INSIDE)
        setPanLimit(PAN_LIMIT_INSIDE)
        setMaxScale(MAX_READER_SCALE)
        setDoubleTapZoomScale(DOUBLE_TAP_READER_SCALE)
        setDoubleTapZoomDuration(DOUBLE_TAP_ZOOM_DURATION_MS)
        setDoubleTapZoomStyle(ZOOM_FOCUS_CENTER)
        setOrientation(ORIENTATION_USE_EXIF)
        setPreferredBitmapConfig(Bitmap.Config.ARGB_8888)
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(shouldKeepTouchInPage(event))
        return super.onTouchEvent(event)
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        return isZoomedPastMinimum() && super.canScrollHorizontally(direction)
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return isZoomedPastMinimum() && super.canScrollVertically(direction)
    }

    private fun shouldKeepTouchInPage(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            return true
        }
        return isZoomedPastMinimum()
    }

    private fun isZoomedPastMinimum(): Boolean {
        return scale > minScale + MIN_SCALE_EPSILON
    }

    companion object {
        private const val MAX_READER_SCALE = 6f
        private const val DOUBLE_TAP_READER_SCALE = 2.5f
        private const val DOUBLE_TAP_ZOOM_DURATION_MS = 160
        private const val MIN_SCALE_EPSILON = 0.01f
    }
}
