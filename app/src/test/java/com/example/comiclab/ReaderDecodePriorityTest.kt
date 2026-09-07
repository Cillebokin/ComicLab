package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDecodePriorityTest {

    @Test
    fun boundPageCanRefreshBeforeScrollingBecomesIdle() {
        assertEquals(
            true,
            shouldRefreshBoundReaderPageImmediately(
                isBoundToPosition = true,
                isComputingLayout = false
            )
        )
        assertEquals(
            false,
            shouldRefreshBoundReaderPageImmediately(
                isBoundToPosition = true,
                isComputingLayout = true
            )
        )
    }

    @Test
    fun pdfFullDecodeWaitsUntilReaderIsIdle() {
        assertEquals(
            false,
            shouldScheduleImmediateFullDecodeForReader(
                isPdfSource = true,
                isReaderIdle = false
            )
        )
        assertEquals(
            true,
            shouldScheduleImmediateFullDecodeForReader(
                isPdfSource = true,
                isReaderIdle = true
            )
        )
    }

    @Test
    fun pdfDoesNotPreloadBackgroundFullPages() {
        assertEquals(
            false,
            shouldPreloadBackgroundFullPagesForReader(
                isPdfSource = true,
                isFastScroll = false,
                isIdle = true
            )
        )
    }

    @Test
    fun pdfVisiblePreviewRunsBeforeFullPageDecode() {
        val previewPriority = visiblePreviewPriorityForReader(
            isPdfSource = true,
            previewPriority = 90,
            fullPriority = 100
        )

        assertTrue(previewPriority > 100)
    }

    @Test
    fun nonPdfVisiblePreviewKeepsExistingPriority() {
        assertEquals(
            90,
            visiblePreviewPriorityForReader(
                isPdfSource = false,
                previewPriority = 90,
                fullPriority = 100
            )
        )
    }
}
