package com.example.comiclab.core.reader.decode

import com.example.comiclab.core.reader.decode.shouldPreloadBackgroundFullPagesForReader
import com.example.comiclab.core.reader.decode.shouldRefreshBoundReaderPageImmediately
import com.example.comiclab.core.reader.decode.shouldScheduleImmediateFullDecodeForReader
import com.example.comiclab.core.reader.decode.visiblePreviewPriorityForReader
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
    fun pdfFullDecodeStartsImmediatelyOnlyForDiscreteJump() {
        assertEquals(
            false,
            shouldScheduleImmediateFullDecodeForReader(
                isPdfSource = true
            )
        )
        assertEquals(
            true,
            shouldScheduleImmediateFullDecodeForReader(
                isPdfSource = true,
                isDiscreteJump = true
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
