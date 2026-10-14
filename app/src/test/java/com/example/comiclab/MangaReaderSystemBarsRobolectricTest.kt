package com.example.comiclab

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "notnight")
class MangaReaderSystemBarsRobolectricTest {

    @Test
    fun readerKeepsSystemBarProtectionBlackInDayMode() {
        val application = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(
            MangaReaderActivity::class.java,
            Intent(application, MangaReaderActivity::class.java).apply {
                putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, "/missing/archive.zip")
            }
        ).setup()

        try {
            val activity = controller.get()
            val systemBars = WindowInsetsControllerCompat(activity.window, activity.window.decorView)

            assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
            assertEquals(Color.BLACK, (activity.window.decorView.background as ColorDrawable).color)
            assertFalse(activity.window.isStatusBarContrastEnforced)
            assertFalse(activity.window.isNavigationBarContrastEnforced)
            assertFalse(systemBars.isAppearanceLightStatusBars)
            assertFalse(systemBars.isAppearanceLightNavigationBars)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
