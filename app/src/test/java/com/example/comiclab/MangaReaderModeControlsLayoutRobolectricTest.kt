package com.example.comiclab

import android.app.Activity
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.util.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaReaderModeControlsLayoutRobolectricTest {

    @Test
    fun imageEnhancementToggleIsBesideBrightnessAndStartsDisabled() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.activity_manga_reader, null, false)
        val customBrightness = content.findViewById<CheckBox>(R.id.checkboxCustomBrightness)
        val imageEnhancement = content.findViewById<CheckBox>(R.id.checkboxReaderImageEnhancement)

        assertSame(customBrightness.parent, imageEnhancement.parent)
        assertEquals(false, imageEnhancement.isChecked)
        assertEquals(activity.getString(R.string.reader_image_enhancement), imageEnhancement.text.toString())
    }

    @Test
    fun readingModeButtonsAreFourSeparateButtonsBelowCustomBrightness() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.activity_manga_reader, null, false)
        val progressPanel = content.findViewById<LinearLayout>(R.id.layoutReaderProgress)
        val modeButtonsRow = progressPanel.getChildAt(progressPanel.childCount - 1) as ViewGroup
        val modeButtons = (0 until modeButtonsRow.childCount)
            .map(modeButtonsRow::getChildAt)
            .filterIsInstance<ImageButton>()

        assertSame(progressPanel, modeButtonsRow.parent)
        assertEquals(4, modeButtons.size)
        assertSame(modeButtonsRow, progressPanel.getChildAt(progressPanel.childCount - 1))
    }

    @Test
    fun readingModeButtonsUseEqualWidthSlotsAcrossTheRow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.activity_manga_reader, null, false)
        val progressPanel = content.findViewById<LinearLayout>(R.id.layoutReaderProgress)
        val modeButtonsRow = progressPanel.getChildAt(progressPanel.childCount - 1) as ViewGroup
        val modeButtons = (0 until modeButtonsRow.childCount)
            .map(modeButtonsRow::getChildAt)
            .filterIsInstance<ImageButton>()

        assertEquals(4, modeButtons.size)
        assertTrue(
            modeButtons.all { button ->
                val layoutParams = button.layoutParams as LinearLayout.LayoutParams
                layoutParams.width == 0 && layoutParams.weight == 1f
            }
        )
    }

    @Test
    fun readingModeButtonsFollowRequestedOrderAndUseLargerWhiteUnselectedIcons() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.activity_manga_reader, null, false)
        val progressPanel = content.findViewById<LinearLayout>(R.id.layoutReaderProgress)
        val modeButtonsRow = progressPanel.getChildAt(progressPanel.childCount - 1) as ViewGroup
        val modeButtons = (0 until modeButtonsRow.childCount)
            .map(modeButtonsRow::getChildAt)
            .filterIsInstance<ImageButton>()
        val expectedDescriptions = listOf(
            activity.getString(R.string.reading_direction_right_to_left),
            activity.getString(R.string.reading_direction_left_to_right),
            activity.getString(R.string.reading_direction_top_to_bottom),
            activity.getString(R.string.double_page_reading_disabled)
        )
        val expectedPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            6f,
            activity.resources.displayMetrics
        ).toInt()

        assertEquals(expectedDescriptions, modeButtons.map { it.contentDescription.toString() })
        assertTrue(modeButtons.all { it.imageTintList?.defaultColor == Color.WHITE })
        assertTrue(modeButtons.all { it.paddingLeft == expectedPadding })
    }
}
