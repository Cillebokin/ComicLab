package com.example.comiclab

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemBars {

    fun fitContentBelowSystemBars(
        activity: Activity,
        rootView: View,
        statusBarBackground: View? = null,
        statusBarColorResId: Int = R.color.comiclab_blue,
        lightStatusBars: Boolean = false
    ) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val statusBarColor = ContextCompat.getColor(activity, statusBarColorResId)
        activity.window.statusBarColor = if (statusBarBackground == null) statusBarColor else Color.TRANSPARENT
        activity.window.navigationBarColor = ContextCompat.getColor(activity, R.color.white)
        WindowInsetsControllerCompat(activity.window, rootView).apply {
            isAppearanceLightStatusBars = lightStatusBars
            isAppearanceLightNavigationBars = true
        }

        val initialLeft = rootView.paddingLeft
        val initialTop = rootView.paddingTop
        val initialRight = rootView.paddingRight
        val initialBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarBackground?.let { background ->
                background.setBackgroundColor(statusBarColor)
                val layoutParams = background.layoutParams
                if (layoutParams.height != systemBars.top) {
                    layoutParams.height = systemBars.top
                    background.layoutParams = layoutParams
                }
            }

            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + if (statusBarBackground == null) systemBars.top else 0,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }

        ViewCompat.requestApplyInsets(rootView)
    }
}
