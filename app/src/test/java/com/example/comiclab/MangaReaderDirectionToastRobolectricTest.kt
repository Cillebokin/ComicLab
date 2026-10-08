package com.example.comiclab

import android.widget.ImageButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class MangaReaderDirectionToastRobolectricTest {

    @Before
    fun resetReadingDirection() {
        AppSettings.setReadingDirection(
            RuntimeEnvironment.getApplication(),
            AppSettings.READING_DIRECTION_TOP_TO_BOTTOM
        )
        ShadowToast.reset()
    }

    @Test
    fun changingReadingDirectionShowsCurrentDirectionHint() {
        val activity = Robolectric.buildActivity(MangaReaderActivity::class.java)
            .setup()
            .get()
        ShadowToast.reset()
        val expectedMessage = activity.getString(
            R.string.reading_direction_current,
            activity.getString(R.string.reading_direction_left_to_right)
        )

        activity.findViewById<ImageButton>(R.id.btnReaderDirectionLeftToRight).performClick()

        assertTrue(ShadowToast.showedToast(expectedMessage))
    }

    @Test
    fun selectingAlreadyActiveReadingDirectionDoesNotShowSwitchHint() {
        val activity = Robolectric.buildActivity(MangaReaderActivity::class.java)
            .setup()
            .get()
        ShadowToast.reset()

        activity.findViewById<ImageButton>(R.id.btnReaderDirectionTopToBottom).performClick()

        assertEquals(0, ShadowToast.shownToastCount())
    }
}
