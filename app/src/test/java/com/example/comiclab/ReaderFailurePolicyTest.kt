package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFailurePolicyTest {

    @Test
    fun oomCancelsOnlyCancelableNonVisiblePreloads() {
        assertTrue(
            shouldCancelPreloadAfterOutOfMemory(
                isCancelable = true,
                isVisible = false
            )
        )
        assertFalse(
            shouldCancelPreloadAfterOutOfMemory(
                isCancelable = true,
                isVisible = true
            )
        )
        assertFalse(
            shouldCancelPreloadAfterOutOfMemory(
                isCancelable = false,
                isVisible = false
            )
        )
    }

    @Test
    fun onlyVisibleFullDecodeRetriesOnce() {
        assertTrue(
            shouldRetryFullPageAfterFailure(
                isPreload = false,
                alreadyRetried = false
            )
        )
        assertFalse(
            shouldRetryFullPageAfterFailure(
                isPreload = false,
                alreadyRetried = true
            )
        )
        assertFalse(
            shouldRetryFullPageAfterFailure(
                isPreload = true,
                alreadyRetried = false
            )
        )
    }

    @Test
    fun retryUsesAtMostHalfOfTheOriginalDecodeDimension() {
        assertEquals(
            1536,
            reducedDecodeDimensionForRetry(currentDimension = 3072, minimumDimension = 720)
        )
        assertEquals(
            720,
            reducedDecodeDimensionForRetry(currentDimension = 1080, minimumDimension = 720)
        )
        assertEquals(
            500,
            reducedDecodeDimensionForRetry(currentDimension = 500, minimumDimension = 720)
        )
    }
}
