package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaPreviewDecodeWindowTest {
    @Test
    fun decodeWindowIncludesVisibleRowsAndOneOverscanRowOnEachSide() {
        assertEquals(0..11, archivePreviewDecodeWindow(4, 8, 97, columnCount = 3))
    }

    @Test
    fun decodeWindowClampsToThePartialLastRow() {
        assertEquals(87..96, archivePreviewDecodeWindow(95, 96, 97, columnCount = 3, overscanRows = 2))
    }

    @Test
    fun invalidOrEmptyVisibleRangeProducesNoDecodeWork() {
        assertNull(archivePreviewDecodeWindow(4, 3, 97, columnCount = 3))
        assertNull(archivePreviewDecodeWindow(0, 0, 0, columnCount = 3))
        assertNull(archivePreviewDecodeWindow(0, 0, 97, columnCount = 0))
    }
}
