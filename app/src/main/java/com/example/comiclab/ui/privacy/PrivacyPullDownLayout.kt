package com.example.comiclab.ui.privacy

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import com.example.comiclab.R
import kotlin.math.abs

internal class PrivacyPullDownLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var canTriggerPullDown: () -> Boolean = { false }
    var onPullDownTriggered: () -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 72 * resources.displayMetrics.density
    private lateinit var hint: TextView
    private lateinit var list: View
    private var startX = 0f
    private var startY = 0f
    private var eligible = false
    private var pulling = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        hint = findViewById(R.id.tvPrivacyPullHint)
        list = findViewById(R.id.listFiles)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPull()
                startX = event.x
                startY = event.y
                eligible = canPull()
            }
            MotionEvent.ACTION_MOVE -> return startPullIfVertical(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelPull()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!eligible) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (pulling) showHint(event.y - startY)
                else startPullIfVertical(event)
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.y - startY
                val trigger = pulling && dy >= threshold && dy > abs(event.x - startX) && canPull()
                cancelPull()
                if (trigger) onPullDownTriggered()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelPull()
        }
        return true
    }

    private fun canPull(): Boolean = list.isShown && !list.canScrollVertically(-1) && canTriggerPullDown()

    private fun startPullIfVertical(event: MotionEvent): Boolean {
        if (!eligible) return false
        val dx = abs(event.x - startX)
        val dy = event.y - startY
        if (dy < -slop || (dx > slop && dx >= dy) || !canPull()) {
            cancelPull()
            return false
        }
        pulling = dy > slop && dy > dx
        if (pulling) showHint(dy)
        return pulling
    }

    private fun showHint(distance: Float) {
        hint.setText(if (distance >= threshold) R.string.privacy_pull_release else R.string.privacy_pull_hint)
        hint.visibility = VISIBLE
    }

    fun cancelPull() {
        eligible = false
        pulling = false
        if (::hint.isInitialized) hint.visibility = GONE
    }
}
