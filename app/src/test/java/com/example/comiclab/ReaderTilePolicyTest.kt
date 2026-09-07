package com.example.comiclab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTilePolicyTest {

    @Test
    fun onlyTilesIntersectingViewportAreVisible() {
        assertTrue(isReaderTileVisible(100, 200, 150, 250))
        assertTrue(isReaderTileVisible(200, 300, 150, 250))
        assertFalse(isReaderTileVisible(0, 100, 100, 200))
        assertFalse(isReaderTileVisible(300, 400, 100, 200))
    }

    @Test
    fun pdfPagesUseTilesOnlyAboveRenderPixelThreshold() {
        assertFalse(
            shouldUsePdfTiledRendering(
                pageWidth = 1600,
                pageHeight = 2400,
                displayWidth = 1080,
                renderPixelThreshold = 8_000_000L
            )
        )
        assertTrue(
            shouldUsePdfTiledRendering(
                pageWidth = 2400,
                pageHeight = 3600,
                displayWidth = 3000,
                renderPixelThreshold = 8_000_000L
            )
        )
    }

    @Test
    fun pdfTileCacheHasAnExplicitUpperBound() {
        assertTrue(pdfTileBitmapCacheSizeKb(512 * 1024) <= 16 * 1024)
        assertTrue(pdfTileBitmapCacheSizeKb(512 * 1024) >= 8 * 1024)
    }
}
