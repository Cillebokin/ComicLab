package com.example.comiclab.translation

import android.graphics.Bitmap
import android.graphics.Color
import li.joye.yakuyomi.engine.LightTextDetector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LightTextDetectorTest {
    @Test fun fragmentedPanelEdgeDoesNotBecomeLongTextLine() {
        val page = Bitmap.createBitmap(280, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        // Disconnected pieces of a panel border have a nearly continuous straight top edge.
        for (n in 0..8) {
            val left = 6 + n * 30
            for (y in 45..74) for (x in left..left + 27)
                if (y == 45 || x == left || x == left + 27) page.setPixel(x, y, Color.BLACK)
        }
        val result = LightTextDetector.detect(page)
        try {
            assertTrue(result.lines.none { line ->
                line.quad.maxOf { it.x } - line.quad.minOf { it.x } > 200 &&
                    line.quad.minOf { it.y } <= 45 && line.quad.maxOf { it.y } >= 74
            })
            assertEquals(Color.BLACK, result.textMask.getPixel(7, 45))
        } finally { result.textMask.recycle(); page.recycle() }
    }

    @Test fun longTextWithoutContinuousFrameEdgeIsPreserved() {
        val page = Bitmap.createBitmap(280, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (n in 0..8) {
            val left = 6 + n * 30
            for (y in 45..74) for (x in left..left + 27)
                if ((x == left + 5 || x == left + 22) || (y == 50 || y == 68)) page.setPixel(x, y, Color.BLACK)
        }
        val result = LightTextDetector.detect(page)
        try { assertTrue(result.lines.any { it.quad.maxOf { p -> p.x } - it.quad.minOf { p -> p.x } > 200 }) }
        finally { result.textMask.recycle(); page.recycle() }
    }
    @Test fun blankPageProducesNoLinesOrMask() {
        val page = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val result = LightTextDetector.detect(page)
        assertTrue(result.lines.isEmpty())
        assertEquals(Color.BLACK, result.textMask.getPixel(40, 40))
        result.textMask.recycle(); page.recycle()
    }

    @Test fun horizontalAndVerticalCharactersGenerateLinesAndStrokeMask() {
        val page = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (n in 0..3) for (y in 20..31) for (x in 20 + n * 16..27 + n * 16) page.setPixel(x, y, Color.BLACK)
        for (n in 0..3) for (y in 90 + n * 16..101 + n * 16) for (x in 140..147) page.setPixel(x, y, Color.BLACK)
        val result = LightTextDetector.detect(page)
        assertTrue(result.lines.size >= 2)
        assertEquals(Color.WHITE, result.textMask.getPixel(22, 22))
        assertEquals(Color.BLACK, result.textMask.getPixel(110, 60))
        result.textMask.recycle(); page.recycle()
    }

    @Test fun lightCharactersOnDarkBackgroundAreDetected() {
        val page = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        for (n in 0..3) for (y in 30..42) for (x in 15 + n * 15..22 + n * 15) page.setPixel(x, y, Color.WHITE)
        val result = LightTextDetector.detect(page)
        assertTrue(result.lines.isNotEmpty())
        assertEquals(Color.WHITE, result.textMask.getPixel(18, 33))
        result.textMask.recycle(); page.recycle()
    }

    @Test fun cancellationStopsBeforeProducingAResult() {
        val page = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                LightTextDetector.detect(page) { throw java.util.concurrent.CancellationException() }
            }
        } finally { page.recycle() }
    }

    @Test fun crossingTextRowsDoNotMergeIntoOneLargeSquare() {
        val page = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        fun glyph(left: Int, top: Int) { for (y in top until top + 10) for (x in left until left + 8) page.setPixel(x, y, Color.BLACK) }
        for (i in 0..4) glyph(30 + i * 15, 60)
        for (i in 0..4) glyph(60, 30 + i * 15)
        val result = LightTextDetector.detect(page)
        try {
            assertTrue(result.lines.size >= 2)
            assertTrue(result.lines.none { line ->
                val w = line.quad.maxOf { it.x } - line.quad.minOf { it.x }
                val h = line.quad.maxOf { it.y } - line.quad.minOf { it.y }
                w > 40 && h > 40
            })
        } finally { result.textMask.recycle(); page.recycle() }
    }

    @Test fun driftingBackgroundComponentsDoNotFormOneWideTextLine() {
        val page = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (i in 0..7) for (y in 15 + i * 4 until 25 + i * 4) for (x in 15 + i * 15 until 23 + i * 15) page.setPixel(x, y, Color.BLACK)
        val result = LightTextDetector.detect(page)
        try {
            assertTrue(result.lines.none { line ->
                val w = line.quad.maxOf { it.x } - line.quad.minOf { it.x }
                val h = line.quad.maxOf { it.y } - line.quad.minOf { it.y }
                w > 70 && h > 30
            })
        } finally { result.textMask.recycle(); page.recycle() }
    }

    @Test fun backgroundHeuristicChecksRingNotTextInterior() {
        val width = 40; val height = 40
        val gray = IntArray(width * height) { 255 }
        for (y in 12 until 28) for (x in 12 until 28) gray[y * width + x] = 0
        assertTrue(LightTextDetector.hasSimpleSurrounding(gray, width, height, 12, 12, 28, 28, allowMidtones = true))
        gray.fill(128)
        for (y in 12 until 28) for (x in 12 until 28) gray[y * width + x] = 0
        assertTrue(LightTextDetector.hasSimpleSurrounding(gray, width, height, 12, 12, 28, 28, allowMidtones = true))
        assertFalse(LightTextDetector.hasSimpleSurrounding(gray, width, height, 12, 12, 28, 28))
        assertFalse(LightTextDetector.hasSimpleSurrounding(gray, width, height, 12, 12, 28, 28, allowMidtones = false))
        for (y in 0 until height) for (x in 0 until width) gray[y * width + x] = (x * 53 + y * 97) % 256
        for (y in 12 until 28) for (x in 12 until 28) gray[y * width + x] = 0
        assertFalse(LightTextDetector.hasSimpleSurrounding(gray, width, height, 12, 12, 28, 28))
    }

    @Test fun clutteredSurroundingIsNotMistakenForSimpleBackground() {
        val width = 40; val height = 40
        val gray = IntArray(width * height) { 128 }
        for (y in 7 until 33) for (x in 7 until 33) {
            if (x < 12 || y < 8) gray[y * width + x] = 255
        }
        assertTrue(LightTextDetector.hasSimpleSurrounding(gray, width, height, 12, 12, 28, 28))
        assertFalse(LightTextDetector.hasSimpleSurrounding(gray, width, height, 12, 12, 28, 28, strictBackground = true))
    }

    @Test fun darkTextOnUniformGrayBackgroundCanProduceLinesAndMask() {
        val page = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) }
        for (n in 0..3) for (y in 30..42) for (x in 15 + n * 15..22 + n * 15) page.setPixel(x, y, Color.BLACK)
        val result = LightTextDetector.detect(page, allowMidtones = true)
        try {
            assertTrue(result.lines.isNotEmpty())
            assertEquals(Color.WHITE, result.textMask.getPixel(18, 33))
        } finally { result.textMask.recycle(); page.recycle() }
    }

    @Test fun darkTextOnUniformColoredBackgroundCanProduceLines() {
        val page = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220, 170, 175)) }
        for (n in 0..3) for (y in 30..42) for (x in 15 + n * 15..22 + n * 15) page.setPixel(x, y, Color.BLACK)
        val result = LightTextDetector.detect(page, allowMidtones = true)
        try { assertTrue(result.lines.isNotEmpty()) } finally { result.textMask.recycle(); page.recycle() }
    }

    @Test fun shortTextExperimentAddsSquareGlyphWithoutRemovingExistingLines() {
        val page = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (n in 0..3) for (y in 65..76) for (x in 15 + n * 16..22 + n * 16) page.setPixel(x, y, Color.BLACK)
        for (n in 0..2) for (y in 20..32) for (x in 100 + n * 5..102 + n * 5) page.setPixel(x, y, Color.BLACK)
        val baseline = LightTextDetector.detect(page)
        val experimental = LightTextDetector.detect(page, allowShort = true)
        try {
            assertTrue(baseline.lines.isNotEmpty())
            assertTrue(baseline.lines.all { base -> experimental.lines.any { it.quad == base.quad } })
            assertTrue(experimental.lines.any { line ->
                line.quad.minOf { it.x } <= 100 && line.quad.maxOf { it.x } >= 112 &&
                    line.quad.minOf { it.y } <= 20 && line.quad.maxOf { it.y } >= 32
            })
            assertEquals(Color.WHITE, experimental.textMask.getPixel(101, 25))
        } finally { baseline.textMask.recycle(); experimental.textMask.recycle(); page.recycle() }
    }

    @Test fun containedShortDedupKeepsTheExistingLongLine() {
        val page = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (n in 0..2) {
            val left = 20 + n * 11
            for (y in 30..41) for (x in left..left + 8)
                if (x == left || x == left + 8 || y == 30 || y == 41) page.setPixel(x, y, Color.BLACK)
        }
        val base = LightTextDetector.detect(page)
        val short = LightTextDetector.detect(page, allowShort = true)
        val dedup = LightTextDetector.detect(page, allowShort = true, suppressContainedShort = true)
        try {
            assertTrue(base.lines.isNotEmpty())
            assertTrue(short.lines.size > dedup.lines.size)
            assertTrue(base.lines.all { line -> dedup.lines.any { it.quad == line.quad } })
        } finally { base.textMask.recycle(); short.textMask.recycle(); dedup.textMask.recycle(); page.recycle() }
    }
}
