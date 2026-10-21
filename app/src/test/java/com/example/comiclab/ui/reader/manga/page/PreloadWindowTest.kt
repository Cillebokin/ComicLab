package com.example.comiclab.ui.reader.manga.page

import org.junit.Assert.assertEquals
import org.junit.Test

class PreloadWindowTest {

    @Test
    fun normalImageForwardScrollKeepsExistingRangesAndPriorityOrder() {
        val window = PreloadWindow.from(
            firstVisible = 10,
            lastVisible = 11,
            lastIndex = 30,
            scrollDirection = 1,
            isFastScroll = false,
            isIdle = false,
            isPdfSource = false
        )

        assertEquals(9, window.previewStart)
        assertEquals(16, window.previewEnd)
        assertEquals(9, window.protectedStart)
        assertEquals(14, window.protectedEnd)
        assertEquals(listOf(12, 13, 14, 15, 16, 9), window.previewPositions)
        assertEquals(listOf(12, 9), window.fullPositions)
    }

    @Test
    fun fastImageBackwardScrollPrioritizesPagesBehindTheViewport() {
        val window = PreloadWindow.from(
            firstVisible = 10,
            lastVisible = 11,
            lastIndex = 30,
            scrollDirection = -1,
            isFastScroll = true,
            isIdle = false,
            isPdfSource = false
        )

        assertEquals(2, window.previewStart)
        assertEquals(12, window.previewEnd)
        assertEquals(listOf(9, 8, 7, 6, 5, 4, 3, 2, 12), window.previewPositions)
        assertEquals(emptyList<Int>(), window.fullPositions)
    }

    @Test
    fun normalPdfForwardScrollUsesPdfPreviewDistanceWithoutFullPreloads() {
        val window = PreloadWindow.from(
            firstVisible = 10,
            lastVisible = 11,
            lastIndex = 30,
            scrollDirection = 1,
            isFastScroll = false,
            isIdle = false,
            isPdfSource = true
        )

        assertEquals(9, window.previewStart)
        assertEquals(14, window.previewEnd)
        assertEquals(9, window.protectedStart)
        assertEquals(13, window.protectedEnd)
        assertEquals(listOf(12, 13, 14, 9), window.previewPositions)
        assertEquals(emptyList<Int>(), window.fullPositions)
    }

    @Test
    fun fastPdfForwardScrollPreloadsSixteenPagesAheadWithoutFullPreloads() {
        val window = PreloadWindow.from(
            firstVisible = 10,
            lastVisible = 11,
            lastIndex = 30,
            scrollDirection = 1,
            isFastScroll = true,
            isIdle = false,
            isPdfSource = true
        )

        assertEquals(9, window.previewStart)
        assertEquals(27, window.previewEnd)
        assertEquals(
            listOf(12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 9),
            window.previewPositions
        )
        assertEquals(emptyList<Int>(), window.fullPositions)
    }

    @Test
    fun fastPdfBackwardScrollUsesPdfFastWindowAndKeepsFullPreloadsDisabled() {
        val window = PreloadWindow.from(
            firstVisible = 10,
            lastVisible = 11,
            lastIndex = 30,
            scrollDirection = -1,
            isFastScroll = true,
            isIdle = false,
            isPdfSource = true
        )

        assertEquals(0, window.previewStart)
        assertEquals(12, window.previewEnd)
        assertEquals(9, window.protectedStart)
        assertEquals(13, window.protectedEnd)
        assertEquals(listOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 12), window.previewPositions)
        assertEquals(emptyList<Int>(), window.fullPositions)
    }

    @Test
    fun idlePdfPreloadsFullPagesAndUsesPdfCacheProtectionRange() {
        val window = PreloadWindow.from(
            firstVisible = 10,
            lastVisible = 11,
            lastIndex = 30,
            scrollDirection = 1,
            isFastScroll = false,
            isIdle = true,
            isPdfSource = true
        )

        assertEquals(8, window.previewStart)
        assertEquals(27, window.previewEnd)
        assertEquals(9, window.protectedStart)
        assertEquals(13, window.protectedEnd)
        assertEquals(listOf(12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 9, 8), window.previewPositions)
        assertEquals(listOf(12, 9), window.fullPositions)
    }

    @Test
    fun idleImagePreloadsFullPagesAndUsesImageCacheProtectionRange() {
        val window = PreloadWindow.from(
            firstVisible = 10,
            lastVisible = 11,
            lastIndex = 30,
            scrollDirection = -1,
            isFastScroll = false,
            isIdle = true,
            isPdfSource = false
        )

        assertEquals(8, window.previewStart)
        assertEquals(15, window.previewEnd)
        assertEquals(9, window.protectedStart)
        assertEquals(14, window.protectedEnd)
        assertEquals(listOf(9, 8, 12, 13, 14, 15), window.previewPositions)
        assertEquals(listOf(9, 12), window.fullPositions)
    }

    @Test
    fun preloadAndProtectedRangesAreClampedAtBothListEdges() {
        val atStart = PreloadWindow.from(
            firstVisible = 0,
            lastVisible = 1,
            lastIndex = 4,
            scrollDirection = 1,
            isFastScroll = false,
            isIdle = true,
            isPdfSource = false
        )
        assertEquals(0, atStart.previewStart)
        assertEquals(4, atStart.previewEnd)
        assertEquals(0, atStart.protectedStart)
        assertEquals(4, atStart.protectedEnd)
        assertEquals(listOf(2, 3, 4), atStart.previewPositions)
        assertEquals(listOf(2), atStart.fullPositions)

        val atEnd = PreloadWindow.from(
            firstVisible = 3,
            lastVisible = 4,
            lastIndex = 4,
            scrollDirection = -1,
            isFastScroll = false,
            isIdle = true,
            isPdfSource = false
        )
        assertEquals(1, atEnd.previewStart)
        assertEquals(4, atEnd.previewEnd)
        assertEquals(2, atEnd.protectedStart)
        assertEquals(4, atEnd.protectedEnd)
        assertEquals(listOf(2, 1), atEnd.previewPositions)
        assertEquals(listOf(2), atEnd.fullPositions)
    }
}
