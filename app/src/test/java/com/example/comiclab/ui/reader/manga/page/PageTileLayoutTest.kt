package com.example.comiclab.ui.reader.manga.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PageTileLayoutTest {

    @Test
    fun tilesAreContinuousAndCoverFirstAndLastSourceRows() {
        val tiles = buildPageTiles(
            sourceWidth = 2400,
            sourceHeight = 10_000,
            imageWidth = 1200,
            tileDecodeWidth = 1200,
            isPdfSource = false
        )

        assertTrue(tiles.isNotEmpty())
        assertEquals(0, tiles.first().sourceRect.top)
        assertEquals(10_000, tiles.last().sourceRect.bottom)
        assertTrue(tiles.all { it.sourceRect.left == 0 && it.sourceRect.right == 2400 })
        tiles.zipWithNext().forEach { (current, next) ->
            assertEquals(current.sourceRect.bottom, next.sourceRect.top)
            assertEquals(current.index + 1, next.index)
        }
    }

    @Test
    fun ordinaryImagesRespectMinimumTileHeightExceptAtPageEnd() {
        val tiles = buildPageTiles(
            sourceWidth = 2000,
            sourceHeight = 2000,
            imageWidth = 12_000,
            tileDecodeWidth = 12_000,
            isPdfSource = false
        )

        assertTrue(tiles.dropLast(1).all { it.sourceRect.height() >= 512 })
        assertEquals(512, tiles.first().sourceRect.height())
        assertTrue(tiles.last().sourceRect.height() in 1..512)
    }

    @Test
    fun pdfPagesUseTheirSmallerMinimumTileHeight() {
        val tiles = buildPageTiles(
            sourceWidth = 2400,
            sourceHeight = 1200,
            imageWidth = 12_000,
            tileDecodeWidth = 12_000,
            isPdfSource = true
        )

        assertTrue(tiles.dropLast(1).all { it.sourceRect.height() >= 128 })
        assertEquals(128, tiles.first().sourceRect.height())
        assertTrue(tiles.any { it.sourceRect.height() < 512 })
        assertEquals(1200, tiles.last().sourceRect.bottom)
    }
}
