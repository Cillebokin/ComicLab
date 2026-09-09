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
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
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
        // SubsamplingScaleImageView 会在放大页面到达边缘时主动释放父级拦截，
        // 外层不要再次覆盖该状态，否则 RecyclerView 无法接管翻页手势。
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onReaderTap?.invoke()
        return true
    }

    companion object {
        private const val MAX_READER_SCALE = 6f
        private const val DOUBLE_TAP_READER_SCALE = 2.5f
        private const val DOUBLE_TAP_ZOOM_DURATION_MS = 160
    }
}
