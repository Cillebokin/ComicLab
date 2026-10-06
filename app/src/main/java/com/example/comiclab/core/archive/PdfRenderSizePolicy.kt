package com.example.comiclab.core.archive

import kotlin.math.sqrt

internal data class PdfRenderSize(
    val width: Int,
    val height: Int
)

internal object PdfRenderSizePolicy {
    const val MAX_RENDER_WIDTH = 4096
    const val MAX_PREVIEW_HEIGHT = 4096
    const val MAX_PREVIEW_PIXELS = 2_000_000L

    fun calculatePreviewSize(
        pageWidth: Int,
        pageHeight: Int,
        targetWidth: Int,
        maxWidth: Int,
        maxHeight: Int,
        maxPixels: Long
    ): PdfRenderSize? {
        if (pageWidth <= 0 ||
            pageHeight <= 0 ||
            targetWidth <= 0 ||
            maxWidth <= 0 ||
            maxHeight <= 0 ||
            maxPixels <= 0L
        ) {
            return null
        }

        val widthLimit = minOf(targetWidth, maxWidth).coerceAtLeast(1)
        val pagePixels = pageWidth.toDouble() * pageHeight.toDouble()
        val scaleByWidth = widthLimit.toDouble() / pageWidth.toDouble()
        val scaleByHeight = maxHeight.toDouble() / pageHeight.toDouble()
        val scaleByPixels = sqrt(maxPixels.toDouble() / pagePixels)
        val scale = minOf(scaleByWidth, scaleByHeight, scaleByPixels)
        if (!scale.isFinite() || scale <= 0.0) {
            return null
        }

        var width = (pageWidth.toDouble() * scale)
            .toInt()
            .coerceIn(1, widthLimit)
        var height = (pageHeight.toDouble() * scale)
            .toInt()
            .coerceIn(1, maxHeight)

        if (width.toLong() * height.toLong() > maxPixels) {
            height = minOf(
                height,
                (maxPixels / width.toLong()).coerceAtLeast(1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
        }
        if (width.toLong() * height.toLong() > maxPixels) {
            width = minOf(
                width,
                (maxPixels / height.toLong()).coerceAtLeast(1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
        }
        return PdfRenderSize(width, height)
    }
}
