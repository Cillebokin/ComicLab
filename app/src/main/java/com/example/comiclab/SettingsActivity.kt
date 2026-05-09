package com.example.comiclab

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var radioReadingDirection: RadioGroup
    private lateinit var switchVolumeKeyPageTurn: SwitchCompat
    private lateinit var switchAutoHideSystemBars: SwitchCompat
    private lateinit var switchCustomReaderBrightness: SwitchCompat
    private lateinit var sliderSettingsReaderBrightness: SeekBar
    private lateinit var tvSettingsBrightnessValue: TextView
    private var isUpdatingBrightnessControls = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground)
        )

        btnBack = findViewById(R.id.btnBack)
        radioReadingDirection = findViewById(R.id.radioReadingDirection)
        switchVolumeKeyPageTurn = findViewById(R.id.switchVolumeKeyPageTurn)
        switchAutoHideSystemBars = findViewById(R.id.switchAutoHideSystemBars)
        switchCustomReaderBrightness = findViewById(R.id.switchCustomReaderBrightness)
        sliderSettingsReaderBrightness = findViewById(R.id.sliderSettingsReaderBrightness)
        tvSettingsBrightnessValue = findViewById(R.id.tvSettingsBrightnessValue)

        btnBack.setOnClickListener {
            finish()
        }

        bindReadingDirection()
        bindVolumeKeyPageTurn()
        bindAutoHideSystemBars()
        bindCustomReaderBrightness()
    }

    private fun bindReadingDirection() {
        val checkedId = when (AppSettings.getReadingDirection(this)) {
            AppSettings.READING_DIRECTION_RIGHT_TO_LEFT -> R.id.radioRightToLeft
            AppSettings.READING_DIRECTION_LEFT_TO_RIGHT -> R.id.radioLeftToRight
            else -> R.id.radioTopToBottom
        }

        radioReadingDirection.check(checkedId)
        radioReadingDirection.setOnCheckedChangeListener { _, checkedRadioId ->
            val direction = when (checkedRadioId) {
                R.id.radioRightToLeft -> AppSettings.READING_DIRECTION_RIGHT_TO_LEFT
                R.id.radioLeftToRight -> AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
                else -> AppSettings.READING_DIRECTION_TOP_TO_BOTTOM
            }
            AppSettings.setReadingDirection(this, direction)
        }
    }

    private fun bindVolumeKeyPageTurn() {
        switchVolumeKeyPageTurn.isChecked = AppSettings.isVolumeKeyPageTurnEnabled(this)
        switchVolumeKeyPageTurn.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setVolumeKeyPageTurnEnabled(this, isChecked)
        }
    }

    private fun bindAutoHideSystemBars() {
        switchAutoHideSystemBars.isChecked = AppSettings.isAutoHideSystemBarsEnabled(this)
        switchAutoHideSystemBars.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoHideSystemBarsEnabled(this, isChecked)
        }
    }

    private fun bindCustomReaderBrightness() {
        sliderSettingsReaderBrightness.min = AppSettings.MIN_READER_BRIGHTNESS
        sliderSettingsReaderBrightness.max = AppSettings.MAX_READER_BRIGHTNESS
        updateBrightnessControls()

        switchCustomReaderBrightness.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingBrightnessControls) {
                return@setOnCheckedChangeListener
            }

            AppSettings.setCustomReaderBrightnessEnabled(this, isChecked)
            updateBrightnessControls()
        }

        sliderSettingsReaderBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingBrightnessControls) {
                    return
                }

                val brightness = normalizeBrightness(progress)
                AppSettings.setCustomReaderBrightness(this@SettingsActivity, brightness)
                updateBrightnessValueText(brightness)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val brightness = normalizeBrightness(seekBar.progress)
                AppSettings.setCustomReaderBrightness(this@SettingsActivity, brightness)
                updateBrightnessValueText(brightness)
            }
        })
    }

    private fun updateBrightnessControls() {
        isUpdatingBrightnessControls = true
        val isCustomBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(this)
        val brightness = AppSettings.getCustomReaderBrightness(this)

        switchCustomReaderBrightness.isChecked = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.progress = brightness
        sliderSettingsReaderBrightness.isEnabled = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.isClickable = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.isFocusable = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.alpha = if (isCustomBrightnessEnabled) {
            ENABLED_BRIGHTNESS_SLIDER_ALPHA
        } else {
            DISABLED_BRIGHTNESS_SLIDER_ALPHA
        }
        updateBrightnessValueText(brightness)
        isUpdatingBrightnessControls = false
    }

    private fun updateBrightnessValueText(brightness: Int) {
        val percent = (normalizeBrightness(brightness) * 100f / AppSettings.MAX_READER_BRIGHTNESS)
            .toInt()
            .coerceIn(1, 100)
        tvSettingsBrightnessValue.text = getString(R.string.brightness_percent, percent)
    }

    private fun normalizeBrightness(brightness: Int): Int {
        return brightness.coerceIn(
            AppSettings.MIN_READER_BRIGHTNESS,
            AppSettings.MAX_READER_BRIGHTNESS
        )
    }

    companion object {
        private const val ENABLED_BRIGHTNESS_SLIDER_ALPHA = 1f
        private const val DISABLED_BRIGHTNESS_SLIDER_ALPHA = 0.72f
    }
}
