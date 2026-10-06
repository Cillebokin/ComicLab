package com.example.comiclab.ui.reader.common

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.SeekBar
import com.example.comiclab.AppSettings

/** Shared custom-brightness behavior for the manga and ebook readers. */
class ReaderBrightnessController(
    context: Context,
    private val window: Window,
    private val slider: SeekBar,
    private val checkbox: CheckBox,
    private val forceSliderVisible: Boolean = false,
    private val onTrackingStarted: () -> Unit = {},
    private val onTrackingStopped: () -> Unit = {},
    private val onBrightnessChanged: () -> Unit = {}
) {

    private val applicationContext = context.applicationContext
    private var isUpdatingControls = false
    private var customBrightnessEnabled = false

    fun configure() {
        slider.min = AppSettings.MIN_READER_BRIGHTNESS
        slider.max = AppSettings.MAX_READER_BRIGHTNESS
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingControls || !customBrightnessEnabled) {
                    return
                }

                saveAndApplyCustomBrightness(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                if (customBrightnessEnabled) {
                    onTrackingStarted()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (customBrightnessEnabled) {
                    saveAndApplyCustomBrightness(seekBar.progress)
                    onTrackingStopped()
                }
            }
        })
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingControls) {
                return@setOnCheckedChangeListener
            }

            customBrightnessEnabled = isChecked
            AppSettings.setCustomReaderBrightnessEnabled(applicationContext, isChecked)
            if (isChecked) {
                saveAndApplyCustomBrightness(slider.progress)
            } else {
                restoreSystemBrightness()
            }
            updateControls()
            onBrightnessChanged()
        }

        updateControls()
        applyReaderBrightnessSetting()
    }

    fun updateControls() {
        isUpdatingControls = true
        try {
            customBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(applicationContext)
            checkbox.isChecked = customBrightnessEnabled
            if (forceSliderVisible) {
                slider.visibility = View.VISIBLE
            }
            slider.alpha = if (customBrightnessEnabled) ENABLED_SLIDER_ALPHA else DISABLED_SLIDER_ALPHA
            slider.isEnabled = customBrightnessEnabled
            slider.isClickable = customBrightnessEnabled
            slider.isFocusable = customBrightnessEnabled
            slider.progress = if (customBrightnessEnabled) {
                AppSettings.getCustomReaderBrightness(applicationContext)
            } else {
                readSystemBrightness()
            }
        } finally {
            isUpdatingControls = false
        }
    }

    fun applyReaderBrightnessSetting() {
        customBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(applicationContext)
        if (customBrightnessEnabled) {
            applyCustomReaderBrightness(AppSettings.getCustomReaderBrightness(applicationContext))
        } else {
            restoreSystemBrightness()
        }
    }

    fun restoreSystemBrightness() {
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    private fun saveAndApplyCustomBrightness(brightness: Int) {
        val normalized = brightness.coerceIn(
            AppSettings.MIN_READER_BRIGHTNESS,
            AppSettings.MAX_READER_BRIGHTNESS
        )
        AppSettings.setCustomReaderBrightness(applicationContext, normalized)
        applyCustomReaderBrightness(normalized)
    }

    private fun applyCustomReaderBrightness(brightness: Int) {
        val normalized = brightness.coerceIn(
            AppSettings.MIN_READER_BRIGHTNESS,
            AppSettings.MAX_READER_BRIGHTNESS
        )
        val params = window.attributes
        params.screenBrightness = (normalized.toFloat() / AppSettings.MAX_READER_BRIGHTNESS)
            .coerceIn(MIN_WINDOW_BRIGHTNESS, MAX_WINDOW_BRIGHTNESS)
        window.attributes = params
    }

    private fun readSystemBrightness(): Int {
        return runCatching {
            Settings.System.getInt(
                applicationContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                AppSettings.DEFAULT_READER_BRIGHTNESS
            )
        }.getOrDefault(AppSettings.DEFAULT_READER_BRIGHTNESS)
            .coerceIn(AppSettings.MIN_READER_BRIGHTNESS, AppSettings.MAX_READER_BRIGHTNESS)
    }

    private companion object {
        const val MIN_WINDOW_BRIGHTNESS = 0.01f
        const val MAX_WINDOW_BRIGHTNESS = 1f
        const val ENABLED_SLIDER_ALPHA = 1f
        const val DISABLED_SLIDER_ALPHA = 0.72f
    }
}
