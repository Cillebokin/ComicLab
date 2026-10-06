package com.example.comiclab.core.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveImageDecoderSamplingTest {
    @Test(timeout = 500)
    fun nonpositiveMaxSizeUsesUnitSamplingWithoutHanging() {
        assertEquals(1, ArchiveImageDecoder.calculateInSampleSize(120, 240, 0))
        assertEquals(1, ArchiveImageDecoder.calculateInSampleSize(120, 240, -1))
    }
}
