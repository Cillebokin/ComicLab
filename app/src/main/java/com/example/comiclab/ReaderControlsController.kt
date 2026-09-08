package com.example.comiclab

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Owns the common reader control-bar state shared by manga and ebook readers.
 * Format-specific actions such as preview panels, contents, progress, and brightness stay in the Activity.
 */
class ReaderControlsController(
    private val window: Window,
    private val rootView: View,
    private val toolbar: View,
    private val progressPanel: View,
    private val onControlsShown: () -> Unit = {},
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) : AutoCloseable {

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        setVisible(false)
    }

    private var closed = false
    private var controlsVisible = true
    private var autoHideSystemBarsEnabled = true

    val isVisible: Boolean
        get() = controlsVisible

    fun setVisible(visible: Boolean) {
        if (closed) {
            return
        }

        controlsVisible = visible
        toolbar.visibility = if (visible) View.VISIBLE else View.GONE
        progressPanel.visibility = if (visible) View.VISIBLE else View.GONE
        onVisibilityChanged(visible)
        if (visible) {
            onControlsShown()
        } else {
            handler.removeCallbacks(autoHideRunnable)
        }
        updateSystemBarsVisibility()
    }

    fun showTemporarily() {
        if (closed) {
            return
        }

        setVisible(true)
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    fun cancelAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
    }

    fun setAutoHideSystemBarsEnabled(enabled: Boolean) {
        if (closed) {
            return
        }

        autoHideSystemBarsEnabled = enabled
        updateSystemBarsVisibility()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (closed || !hasFocus || !autoHideSystemBarsEnabled || controlsVisible) {
            return
        }

        setSystemBarsVisible(false)
    }

    override fun close() {
        if (closed) {
            return
        }

        closed = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateSystemBarsVisibility() {
        setSystemBarsVisible(!autoHideSystemBarsEnabled || controlsVisible)
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, rootView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val AUTO_HIDE_DELAY_MS = 2600L
    }
}
