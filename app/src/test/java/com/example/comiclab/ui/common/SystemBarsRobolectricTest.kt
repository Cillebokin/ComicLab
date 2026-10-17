package com.example.comiclab.ui.common

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.comiclab.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "notnight")
class SystemBarsRobolectricTest {
    @Test
    fun defaultBackgroundSetsWindowColorsAndDisablesDecorFitting() = withActivity { activity, root ->
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        activity.window.statusBarColor = Color.MAGENTA
        activity.window.navigationBarColor = Color.MAGENTA
        val controller = WindowInsetsControllerCompat(activity.window, root)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = false

        SystemBars.fitContentBelowSystemBars(activity, root)

        assertEquals(ContextCompat.getColor(activity, R.color.comiclab_accent), activity.window.statusBarColor)
        assertEquals(ContextCompat.getColor(activity, R.color.comiclab_file_picker_background), activity.window.navigationBarColor)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun requestedLightStatusIconsAreEnabledInDayMode() = withActivity { activity, root ->
        val controller = WindowInsetsControllerCompat(activity.window, root)
        controller.isAppearanceLightStatusBars = false
        SystemBars.fitContentBelowSystemBars(activity, root, lightStatusBars = true)
        assertTrue(controller.isAppearanceLightStatusBars)
    }

    @Test
    @Config(qualifiers = "night")
    fun nightModeSuppressesRequestedLightStatusAndNavigationIcons() = withActivity { activity, root ->
        val controller = WindowInsetsControllerCompat(activity.window, root)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        SystemBars.fitContentBelowSystemBars(activity, root, lightStatusBars = true)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
        assertEquals(ContextCompat.getColor(activity, R.color.comiclab_file_picker_background), activity.window.navigationBarColor)
    }

    @Test
    fun allInsetsUseOriginalPaddingAndRemainAvailableToConsumers() = withActivity { activity, root ->
        SystemBars.fitContentBelowSystemBars(activity, root)
        val first = insets(11, 13, 17, 19)
        val returned = ViewCompat.dispatchApplyWindowInsets(root, first)
        assertEquals(Insets.of(11, 13, 17, 19), returned.getInsets(WindowInsetsCompat.Type.systemBars()))
        assertFalse(returned.isConsumed)
        assertPadding(root, 13, 16, 22, 26)
        ViewCompat.dispatchApplyWindowInsets(root, first)
        assertPadding(root, 13, 16, 22, 26)
        ViewCompat.dispatchApplyWindowInsets(root, insets(23, 29, 31, 37))
        assertPadding(root, 25, 32, 36, 44)
        ViewCompat.dispatchApplyWindowInsets(root, insets(0, 0, 0, 0))
        assertPadding(root, 2, 3, 5, 7)
    }

    @Test
    fun backgroundOwnsTopInsetAndUpdatesHeightAndCustomColor() = withActivity { activity, root ->
        val background = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(10, 99)
            setBackgroundColor(Color.MAGENTA)
        }
        root.addView(background)
        activity.window.statusBarColor = Color.MAGENTA
        SystemBars.fitContentBelowSystemBars(activity, root, background, R.color.comiclab_file_picker_background, true)
        assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
        for (top in listOf(13, 13, 29, 0)) {
            val returned = ViewCompat.dispatchApplyWindowInsets(root, insets(11, top, 17, 19))
            assertPadding(root, 13, 3, 22, 26)
            assertEquals(top, background.layoutParams.height)
            assertEquals(ContextCompat.getColor(activity, R.color.comiclab_file_picker_background), (background.background as ColorDrawable).color)
            assertEquals(Insets.of(11, top, 17, 19), returned.getInsets(WindowInsetsCompat.Type.systemBars()))
            assertFalse(returned.isConsumed)
        }
    }

    private fun withActivity(block: (Activity, FrameLayout) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity).apply { setPadding(2, 3, 5, 7) }
            activity.setContentView(root)
            block(activity, root)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun insets(left: Int, top: Int, right: Int, bottom: Int) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(left, top, right, bottom))
        .build()

    private fun assertPadding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(listOf(left, top, right, bottom), listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
    }
}
