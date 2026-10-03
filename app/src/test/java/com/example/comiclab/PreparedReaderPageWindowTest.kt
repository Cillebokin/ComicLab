package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedReaderPageWindowTest {

    @Test
    fun forwardWindowPrioritizesFollowingPagesAndStaysNearVisibleRange() {
        assertEquals(
            listOf(13, 14, 15, 9, 8),
            preparedReaderNearbyPositions(
                firstVisible = 10,
                lastVisible = 12,
                entryCount = 30,
                movingForward = true,
                beforeCount = 2,
                afterCount = 3
            )
        )
    }

    @Test
    fun reverseWindowPrioritizesPreviousPages() {
        assertEquals(
            listOf(9, 8, 13, 14, 15),
            preparedReaderNearbyPositions(
                firstVisible = 10,
                lastVisible = 12,
                entryCount = 30,
                movingForward = false,
                beforeCount = 2,
                afterCount = 3
            )
        )
    }

    @Test
    fun largeArchiveWindowDoesNotIncludeEveryPage() {
        val nearby = preparedReaderNearbyPositions(
            firstVisible = 0,
            lastVisible = 1,
            entryCount = 882,
            movingForward = true,
            beforeCount = 3,
            afterCount = 18
        )

        assertEquals((2..19).toList(), nearby)
        assertTrue(nearby.size < 882)
    }

    @Test
    fun invalidVisibleRangeReturnsNoNearbyPages() {
        assertTrue(
            preparedReaderNearbyPositions(
                firstVisible = 4,
                lastVisible = 3,
                entryCount = 30,
                movingForward = true,
                beforeCount = 2,
                afterCount = 3
            ).isEmpty()
        )
    }
}
