package com.example.comiclab.ui.reader.manga.page

import android.graphics.Rect
import kotlin.math.roundToInt

internal data class PageTile(
    val index: Int,
    val sourceRect: Rect
)

internal fun buildPageTiles(
    sourceWidth: Int,
    sourceHeight: Int,
    imageWidth: Int,
    tileDecodeWidth: Int,
    isPdfSource: Boolean
): List<PageTile> {
    val sourceTileHeightByDisplay = (TILE_MAX_DISPLAY_HEIGHT.toFloat() * sourceWidth / imageWidth)
        .roundToInt()
    val sourceTileHeightByPixels = (TILE_MAX_SOURCE_PIXELS / sourceWidth.coerceAtLeast(1))
        .coerceAtLeast(TILE_MIN_SOURCE_HEIGHT)
    val sourceTileHeightByDecodedPixels = if (tileDecodeWidth > 0 && sourceWidth > 0) {
        (TILE_MAX_DECODED_PIXELS.toFloat() * sourceWidth.toFloat() /
            (tileDecodeWidth.toFloat() * tileDecodeWidth.toFloat()))
            .roundToInt()
    } else {
        sourceTileHeightByPixels
    }
    val minSourceTileHeight = if (isPdfSource) {
        PDF_TILE_MIN_SOURCE_HEIGHT
    } else {
        TILE_MIN_SOURCE_HEIGHT
    }
    val sourceTileHeight = minOf(
        sourceTileHeightByDisplay,
        sourceTileHeightByPixels,
        sourceTileHeightByDecodedPixels
    ).coerceAtLeast(minSourceTileHeight)

    val tiles = mutableListOf<PageTile>()
    var top = 0
    var index = 0
    while (top < sourceHeight) {
        val bottom = (top + sourceTileHeight).coerceAtMost(sourceHeight)
        tiles.add(PageTile(index, Rect(0, top, sourceWidth, bottom)))
        top = bottom
        index++
    }
    return tiles
}

private const val TILE_MAX_DISPLAY_HEIGHT = 2400
private const val TILE_MIN_SOURCE_HEIGHT = 512
private const val PDF_TILE_MIN_SOURCE_HEIGHT = 128
private const val TILE_MAX_SOURCE_PIXELS = 4_000_000
private const val TILE_MAX_DECODED_PIXELS = 4_000_000L
