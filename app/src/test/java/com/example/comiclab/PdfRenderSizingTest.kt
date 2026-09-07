package com.example.comiclab

import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderSizingTest {

    @Test
    fun pdfRenderWidthStaysWithinPixelBudgetForCommonDisplayWidths() {
        val pageWidth = 200
        val pageHeight = 288
        val maxPixels = 4_000_000L

        listOf(720, 1080, 1440).forEach { displayWidth ->
            val renderWidth = calculatePdfRenderWidthByPixelBudget(
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                baseDisplayWidth = displayWidth,
                renderScale = 2,
                maxRenderWidth = 3072,
                maxRenderPixels = maxPixels
            )
            val renderHeight = (pageHeight.toDouble() * renderWidth / pageWidth)
                .toInt()
                .coerceAtLeast(1)

            assertTrue(renderWidth >= displayWidth)
            assertTrue(renderWidth * renderHeight <= maxPixels)
        }
    }

    @Test
    fun pdfRenderWidthDoesNotBreakPixelBudgetOnVeryWideDisplay() {
        val renderWidth = calculatePdfRenderWidthByPixelBudget(
            pageWidth = 200,
            pageHeight = 288,
            baseDisplayWidth = 3000,
            renderScale = 2,
            maxRenderWidth = 3072,
            maxRenderPixels = 4_000_000L
        )
        val renderHeight = (288.0 * renderWidth / 200.0).toInt().coerceAtLeast(1)

        assertTrue(renderWidth * renderHeight <= 4_000_000L)
    }
}
