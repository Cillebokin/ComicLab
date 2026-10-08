package com.example.comiclab.core.reader.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoublePageModeAvailabilityTest {

    @Test
    fun topToBottomReadingCannotEnableDoublePageEvenInLandscape() {
        assertEquals(
            DoublePageModeUnavailableReason.VERTICAL_READING,
            doublePageModeUnavailableReason(
                isHorizontalReading = false,
                isLandscape = true
            )
        )
    }

    @Test
    fun horizontalReadingRequiresDeviceLandscape() {
        assertEquals(
            DoublePageModeUnavailableReason.LANDSCAPE_REQUIRED,
            doublePageModeUnavailableReason(
                isHorizontalReading = true,
                isLandscape = false
            )
        )
    }

    @Test
    fun eitherHorizontalDirectionAllowsDoublePageInLandscape() {
        assertNull(
            doublePageModeUnavailableReason(
                isHorizontalReading = true,
                isLandscape = true
            )
        )
        assertNull(
            doublePageModeUnavailableReason(
                isHorizontalReading = true,
                isLandscape = true
            )
        )
    }
}
