package com.example.comiclab

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var radioReadingDirection: RadioGroup
    private lateinit var switchVolumeKeyPageTurn: SwitchCompat

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

        btnBack.setOnClickListener {
            finish()
        }

        bindReadingDirection()
        bindVolumeKeyPageTurn()
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
}
