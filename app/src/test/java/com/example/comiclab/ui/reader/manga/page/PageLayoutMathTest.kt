package com.example.comiclab.ui.reader.manga.page

import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutMathTest {

    @Test
    fun fitInsideSizeUsesTheTighterViewportBoundAndPreservesUpscaling() {
        assertEquals(
            DisplaySize(2_400, 600),
            calculateFitInsideSize(4_000, 1_000, 2_400, 1_800)
        )
        assertEquals(
            DisplaySize(450, 1_800),
            calculateFitInsideSize(1_000, 4_000, 2_400, 1_800)
        )
        assertEquals(
            DisplaySize(2_400, 1_200),
            calculateFitInsideSize(400, 200, 2_400, 1_800)
        )
    }

    @Test
    fun fitInsideSizeReturnsTheOriginalViewportForNonPositiveInputs() {
        assertEquals(
            DisplaySize(2_400, 1_800),
            calculateFitInsideSize(0, 1_000, 2_400, 1_800)
        )
        assertEquals(
            DisplaySize(2_400, 1_800),
            calculateFitInsideSize(1_000, -1, 2_400, 1_800)
        )
        assertEquals(
            DisplaySize(0, 1_800),
            calculateFitInsideSize(1_000, 1_000, 0, 1_800)
        )
    }

    @Test
    fun fitInsideSizeKeepsRoundedDimensionsAtLeastOnePixel() {
        assertEquals(
            DisplaySize(100, 1),
            calculateFitInsideSize(1_000_000, 1, 100, 100)
        )
    }

    @Test
    fun sourceToDisplayYMapsEndpointsAndClampsOutsideCoordinates() {
        assertEquals(0, sourceToDisplayY(0, 100, 200))
        assertEquals(100, sourceToDisplayY(50, 100, 200))
        assertEquals(200, sourceToDisplayY(100, 100, 200))
        assertEquals(0, sourceToDisplayY(-1, 100, 200))
        assertEquals(200, sourceToDisplayY(101, 100, 200))
    }

    @Test
    fun sourceToDisplayYRoundsHalfPixelTowardTheNextPixel() {
        assertEquals(1, sourceToDisplayY(1, 2, 1))
    }

    @Test
    fun sourceToDisplayYReturnsZeroForNonPositiveHeights() {
        assertEquals(0, sourceToDisplayY(10, 0, 200))
        assertEquals(0, sourceToDisplayY(10, 100, 0))
    }

    @Test
    fun sampleSizeReturnsOneForInvalidOrAlreadyNarrowInputs() {
        assertEquals(1, calculateSampleSizeForWidth(0, 200))
        assertEquals(1, calculateSampleSizeForWidth(800, 0))
        assertEquals(1, calculateSampleSizeForWidth(100, 200))
    }

    @Test
    fun sampleSizePreservesPowerOfTwoThresholdBoundaries() {
        assertEquals(4, calculateSampleSizeForWidth(800, 200))
        assertEquals(2, calculateSampleSizeForWidth(800, 201))
    }
}
