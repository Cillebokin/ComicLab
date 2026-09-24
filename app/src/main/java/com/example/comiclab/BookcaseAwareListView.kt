package com.example.comiclab

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ListView

class BookcaseAwareListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.listViewStyle
) : ListView(context, attrs, defStyleAttr) {

    private val touchTracker = BookcaseListTouchTracker()

    internal val hasActiveTouchGesture: Boolean
        get() = touchTracker.isTouchActive

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            touchTracker.onDown()
        }

        var handled = false
        try {
            handled = super.dispatchTouchEvent(event)
            return handled
        } finally {
            when (action) {
                MotionEvent.ACTION_DOWN -> touchTracker.onDownDispatchResult(handled)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> touchTracker.onEnd()
            }
        }
    }

    override fun onDetachedFromWindow() {
        touchTracker.cancel()
        super.onDetachedFromWindow()
    }
}

internal class BookcaseListTouchTracker {

    private var touchActive = false

    val isTouchActive: Boolean
        get() = touchActive

    fun onDown() {
        touchActive = true
    }

    fun onDownDispatchResult(handled: Boolean) {
        if (!handled) {
            cancel()
        }
    }

    fun onEnd() {
        touchActive = false
    }

    fun cancel() {
        onEnd()
    }
}
