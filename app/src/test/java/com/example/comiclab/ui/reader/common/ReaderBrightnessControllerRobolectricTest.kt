package com.example.comiclab.ui.reader.common

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import com.example.comiclab.AppSettings
import com.example.comiclab.EbookReaderActivity
import com.example.comiclab.MangaReaderActivity
import com.example.comiclab.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderBrightnessControllerRobolectricTest {

    @Before
    fun resetBrightnessSettings() {
        val application = RuntimeEnvironment.getApplication()
        AppSettings.setCustomReaderBrightnessEnabled(application, false)
        AppSettings.setCustomReaderBrightness(application, AppSettings.DEFAULT_READER_BRIGHTNESS)
        Settings.System.putString(
            application.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            null
        )
    }

    @Test
    fun configureUsesSystemBrightnessDefaultAndClampsSystemValues() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (slider, checkbox, controller) = createController(activity)

        Settings.System.putString(
            activity.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            null
        )
        controller.configure()

        assertEquals(0, slider.min)
        assertEquals(AppSettings.MAX_READER_BRIGHTNESS, slider.max)
        assertEquals(AppSettings.DEFAULT_READER_BRIGHTNESS, slider.progress)
        assertFalse(checkbox.isChecked)
        assertFalse(slider.isEnabled)
        assertEquals(0.72f, slider.alpha, 0.0001f)

        Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 300)
        controller.updateControls()
        assertEquals(AppSettings.MAX_READER_BRIGHTNESS, slider.progress)

        Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
        controller.updateControls()
        assertEquals(1, slider.progress)
    }

    @Test
    fun togglingCustomBrightnessSavesCurrentValueAndRestoresSystemWindowSetting() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 176)
        val (slider, checkbox, controller) = createController(activity)
        controller.configure()

        checkbox.isChecked = true

        assertTrue(AppSettings.isCustomReaderBrightnessEnabled(activity))
        assertEquals(176, AppSettings.getCustomReaderBrightness(activity))
        assertEquals(176, slider.progress)
        assertEquals(176f / 255f, activity.window.attributes.screenBrightness, 0.0001f)
        assertTrue(slider.isEnabled)
        assertEquals(1f, slider.alpha, 0.0001f)

        checkbox.isChecked = false

        assertFalse(AppSettings.isCustomReaderBrightnessEnabled(activity))
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            activity.window.attributes.screenBrightness
        )
        assertFalse(slider.isEnabled)
    }

    @Test
    fun onlyUserDraggingSavesAndAppliesCustomBrightness() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppSettings.setCustomReaderBrightnessEnabled(activity, true)
        AppSettings.setCustomReaderBrightness(activity, 128)
        val (slider, _, controller) = createController(activity)
        controller.configure()

        slider.progress = 90
        assertEquals(128, AppSettings.getCustomReaderBrightness(activity))

        slider.dispatchUserProgress(211)
        assertEquals(211, AppSettings.getCustomReaderBrightness(activity))
        assertEquals(211f / 255f, activity.window.attributes.screenBrightness, 0.0001f)

        slider.dispatchUserProgress(0)
        assertEquals(0, AppSettings.getCustomReaderBrightness(activity))
        assertEquals(0f, activity.window.attributes.screenBrightness, 0.0001f)
    }

    @Test
    fun settingsScreenShowsZeroPercentAtMinimumCustomBrightness() {
        val application = RuntimeEnvironment.getApplication()
        AppSettings.setCustomReaderBrightnessEnabled(application, true)
        AppSettings.setCustomReaderBrightness(application, 0)
        val activityController = Robolectric.buildActivity(SettingsActivity::class.java)

        try {
            val activity = activityController.setup().get()
            val slider = activity.findViewById<SeekBar>(com.example.comiclab.R.id.sliderSettingsReaderBrightness)
            val value = activity.findViewById<TextView>(com.example.comiclab.R.id.tvSettingsBrightnessValue)

            assertEquals(0, slider.min)
            assertEquals(0, slider.progress)
            assertEquals("0%", value.text.toString())
        } finally {
            runCatching { activityController.destroy() }
        }
    }

    @Test
    fun trackingCallbacksKeepReaderControlVisibilityBehaviorAndRestoreIsRepeatable() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var trackingStarted = 0
        var trackingStopped = 0
        var brightnessChanged = 0
        val (slider, checkbox, controller) = createController(
            activity,
            onTrackingStarted = { trackingStarted++ },
            onTrackingStopped = { trackingStopped++ },
            onBrightnessChanged = { brightnessChanged++ }
        )
        controller.configure()
        checkbox.isChecked = true

        slider.dispatchStartTracking()
        slider.progress = 192
        slider.dispatchStopTracking()

        assertEquals(1, trackingStarted)
        assertEquals(1, trackingStopped)
        assertEquals(1, brightnessChanged)

        controller.restoreSystemBrightness()
        controller.restoreSystemBrightness()
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            activity.window.attributes.screenBrightness
        )
    }

    @Test
    fun mangaActivityWiresBrightnessControllerAndRestoresBrightnessOnPause() {
        val application = RuntimeEnvironment.getApplication()
        AppSettings.setCustomReaderBrightnessEnabled(application, true)
        AppSettings.setCustomReaderBrightness(application, 204)
        val activityController = Robolectric.buildActivity(
            MangaReaderActivity::class.java,
            Intent(application, MangaReaderActivity::class.java).apply {
                putExtra(
                    MangaReaderActivity.EXTRA_ARCHIVE_PATH,
                    missingInputPath("cbz")
                )
            }
        )
        try {
            val activity = activityController.setup().get()

            val slider = activity.findViewById<SeekBar>(com.example.comiclab.R.id.sliderScreenBrightness)
            assertEquals(204, slider.progress)
            assertEquals(View.VISIBLE, slider.visibility)
            assertTrue(activity.findViewById<CheckBox>(com.example.comiclab.R.id.checkboxCustomBrightness).isChecked)
            assertEquals(204f / 255f, activity.window.attributes.screenBrightness, 0.0001f)

            activityController.pause()

            assertEquals(
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                activity.window.attributes.screenBrightness
            )
        } finally {
            runCatching { activityController.destroy() }
        }
    }

    @Test
    fun ebookActivityWiresBrightnessControllerAndRestoresBrightnessOnPause() {
        val application = RuntimeEnvironment.getApplication()
        AppSettings.setCustomReaderBrightnessEnabled(application, true)
        AppSettings.setCustomReaderBrightness(application, 207)
        val activityController = Robolectric.buildActivity(
            EbookReaderActivity::class.java,
            Intent(application, EbookReaderActivity::class.java).apply {
                putExtra(
                    EbookReaderActivity.EXTRA_BOOK_PATH,
                    missingInputPath("epub")
                )
            }
        )
        var destroyed = false

        try {
            val activity = activityController.setup().get()

            assertEquals(
                207,
                activity.findViewById<SeekBar>(com.example.comiclab.R.id.sliderEbookScreenBrightness).progress
            )
            assertTrue(
                activity.findViewById<CheckBox>(com.example.comiclab.R.id.checkboxEbookCustomBrightness).isChecked
            )
            assertEquals(207f / 255f, activity.window.attributes.screenBrightness, 0.0001f)

            activityController.pause()

            assertEquals(
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                activity.window.attributes.screenBrightness
            )

            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = 0.5f
            }
            activityController.destroy()
            destroyed = true
            assertEquals(
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                activity.window.attributes.screenBrightness
            )
        } finally {
            if (!destroyed) {
                runCatching { activityController.destroy() }
            }
        }
    }

    private fun createController(
        activity: Activity,
        onTrackingStarted: () -> Unit = {},
        onTrackingStopped: () -> Unit = {},
        onBrightnessChanged: () -> Unit = {}
    ): Triple<TrackingTestSeekBar, CheckBox, ReaderBrightnessController> {
        val slider = TrackingTestSeekBar(activity)
        val checkbox = CheckBox(activity)
        return Triple(
            slider,
            checkbox,
            ReaderBrightnessController(
                context = activity.applicationContext,
                window = activity.window,
                slider = slider,
                checkbox = checkbox,
                onTrackingStarted = onTrackingStarted,
                onTrackingStopped = onTrackingStopped,
                onBrightnessChanged = onBrightnessChanged
            )
        )
    }

    private fun missingInputPath(extension: String): String = File(
        RuntimeEnvironment.getApplication().cacheDir,
        "missing-reader-brightness-${UUID.randomUUID()}.$extension"
    ).absolutePath

    private class TrackingTestSeekBar(activity: Activity) : SeekBar(activity) {
        private var changeListener: OnSeekBarChangeListener? = null

        override fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) {
            changeListener = listener
            super.setOnSeekBarChangeListener(listener)
        }

        fun dispatchUserProgress(progress: Int) {
            setProgress(progress)
            changeListener?.onProgressChanged(this, progress, true)
        }

        fun dispatchStartTracking() {
            changeListener?.onStartTrackingTouch(this)
        }

        fun dispatchStopTracking() {
            changeListener?.onStopTrackingTouch(this)
        }
    }
}
