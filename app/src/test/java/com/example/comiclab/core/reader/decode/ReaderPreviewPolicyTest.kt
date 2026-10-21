package com.example.comiclab.core.reader.decode

import com.example.comiclab.core.reader.decode.calculatePdfPreviewWidth
import com.example.comiclab.core.reader.decode.shouldScheduleImmediateFullDecodeForReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPreviewPolicyTest {

    @Test
    fun pdfPreviewUsesHalfDisplayWidthWithinDecodeLimits() {
        assertEquals(
            360,
            calculatePdfPreviewWidth(
                baseDisplayWidth = 720,
                minimumWidth = 360,
                maximumWidth = 1080
            )
        )
        assertEquals(
            540,
            calculatePdfPreviewWidth(
                baseDisplayWidth = 1080,
                minimumWidth = 360,
                maximumWidth = 1080
            )
        )
        assertEquals(
            1080,
            calculatePdfPreviewWidth(
                baseDisplayWidth = 2160,
                minimumWidth = 360,
                maximumWidth = 1080
            )
        )
    }

    @Test
    fun onlyDiscreteJumpsStartPdfFullDecodeImmediately() {
        assertFalse(
            shouldScheduleImmediateFullDecodeForReader(
                isPdfSource = true
            )
        )
        assertTrue(
            shouldScheduleImmediateFullDecodeForReader(
                isPdfSource = true,
                isDiscreteJump = true
            )
        )
        assertTrue(
            shouldScheduleImmediateFullDecodeForReader(
                isPdfSource = false
            )
        )
    }
}
