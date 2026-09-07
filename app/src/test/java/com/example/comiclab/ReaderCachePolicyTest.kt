package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCachePolicyTest {

    @Test
    fun pdfFullBitmapCacheStaysWithinSixteenToTwentyFourMiB() {
        assertEquals(
            16 * 1024,
            pdfFullBitmapCacheSizeKb(maxMemoryKb = 128 * 1024)
        )
        assertEquals(
            24 * 1024,
            pdfFullBitmapCacheSizeKb(maxMemoryKb = 512 * 1024)
        )
    }
}
