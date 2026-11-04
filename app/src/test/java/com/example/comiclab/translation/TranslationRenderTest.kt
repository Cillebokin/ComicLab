package com.example.comiclab.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.comiclab.core.reader.translation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationRenderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun streamingCompositionPreservesSourceOutsideMaskAcrossStripBoundary() {
        val directory = folder.newFolder()
        val source = java.io.File(directory, "original.png")
        val page = Bitmap.createBitmap(160, 270, Bitmap.Config.ARGB_8888)
        for (y in 0 until 270) for (x in 0 until 160) page.setPixel(x, y, Color.rgb(x, y % 256, 70))
        source.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }; page.recycle()
        val original = source.readBytes()
        val patch = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888)
        patch.setPixel(3, 7, Color.MAGENTA)
        val patchFile = java.io.File(directory, "patch.png")
        patchFile.outputStream().use { patch.compress(Bitmap.CompressFormat.PNG, 100, it) }; patch.recycle()
        val analysis = TranslationAnalysis(source, 160, 270, 1, emptyList(), listOf(TranslationPatch(patchFile, 40, 122, 12, 16)))
        val output = SinglePageTranslation(RuntimeEnvironment.getApplication(), AtomicBoolean(), {}).render(analysis, directory, TranslationStyle())
        val rendered = BitmapFactory.decodeFile(output.path)
        try {
            assertEquals(160, rendered.width); assertEquals(270, rendered.height)
            assertEquals(Color.MAGENTA, rendered.getPixel(43, 129))
            assertEquals(Color.rgb(42, 129, 70), rendered.getPixel(42, 129))
            assertEquals(Color.rgb(140, 4, 70), rendered.getPixel(140, 260))
            assertArrayEquals(original, source.readBytes())
            assertFalse(java.io.File(directory, "render.rgb").exists())
            assertFalse(java.io.File(directory, "render.pending.png").exists())
        } finally { rendered.recycle() }
    }

    @Test fun cancellationDoesNotPublishOutput() {
        val directory = folder.newFolder()
        val source = java.io.File(directory, "source.png").apply { writeText("untouched") }
        assertThrows(CancellationException::class.java) {
            SinglePageTranslation(RuntimeEnvironment.getApplication(), AtomicBoolean(true), {})
                .render(TranslationAnalysis(source, 64, 64, 1, emptyList(), emptyList()), directory, TranslationStyle())
        }
        assertEquals(listOf("source.png"), directory.listFiles()!!.map { it.name })
    }
    @Test fun everySupportedExifRotationDecodesLocalCropsAndStreamsAnUprightPage() {
        for (orientation in listOf(1, 3, 6, 8)) {
            val dir = folder.newFolder()
            val source = java.io.File(dir, "page.png")
            val upright = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888)
            for (y in 0 until 160) for (x in 0 until 80) upright.setPixel(x, y, Color.rgb(x * 3, y, 70))
            val reverse = when (orientation) { 3 -> 180f; 6 -> -90f; 8 -> 90f; else -> 0f }
            val raw = Bitmap.createBitmap(upright, 0, 0, 80, 160, android.graphics.Matrix().apply { setRotate(reverse) }, false)
            source.outputStream().use { raw.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (raw !== upright) raw.recycle()
            android.media.ExifInterface(source.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
            }
            TranslationPageSource(source).use { decoder ->
                assertEquals(80, decoder.width); assertEquals(160, decoder.height)
                val crop = decoder.decodeRegion(android.graphics.Rect(11, 120, 49, 149))
                try {
                    for (y in 0 until crop.height) for (x in 0 until crop.width)
                        assertEquals("EXIF $orientation at $x,$y", upright.getPixel(x + 11, y + 120), crop.getPixel(x, y))
                } finally { crop.recycle() }
            }
            val analysis = TranslationAnalysis(source, 80, 160, orientation, emptyList(), emptyList())
            val output = SinglePageTranslation(RuntimeEnvironment.getApplication(), AtomicBoolean(), {}).render(analysis, dir, TranslationStyle())
            val result = BitmapFactory.decodeFile(output.path)
            try {
                assertEquals(80, result.width); assertEquals(160, result.height)
                assertEquals(upright.getPixel(15, 132), result.getPixel(15, 132))
                assertTrue(android.media.ExifInterface(output.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) in listOf(0, 1))
            } finally { result.recycle(); upright.recycle() }
        }
    }

    @Test fun narrowVerticalCjkAndRotatedTextRenderAcrossStripBoundaries() {
        val dir = folder.newFolder(); val source = java.io.File(dir, "source.png")
        val page = Bitmap.createBitmap(240, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        source.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }; page.recycle()
        fun region(left: Float, top: Float, right: Float, bottom: Float, dir: String, angle: Float) = li.joye.yakuyomi.engine.TextRegion(
            listOf(li.joye.yakuyomi.engine.TextLine(listOf(li.joye.yakuyomi.engine.Pt(left, top), li.joye.yakuyomi.engine.Pt(right, top),
                li.joye.yakuyomi.engine.Pt(right, bottom), li.joye.yakuyomi.engine.Pt(left, bottom)), 1f)), dir, angle,
            (left + right) / 2, (top + bottom) / 2, right - left, bottom - top)
        val blocks = listOf(TranslationBlock(region(20f, 20f, 42f, 250f, "v", 0f), "这是竖排翻译文字"),
            TranslationBlock(region(85f, 116f, 210f, 140f, "h", 35f), "Hello"))
        val analysis = TranslationAnalysis(source, 240, 300, 1, blocks, emptyList())
        val output = SinglePageTranslation(RuntimeEnvironment.getApplication(), AtomicBoolean(), {}).render(analysis, dir, TranslationStyle())
        val result = BitmapFactory.decodeFile(output.path)
        try {
            var verticalInk = 0; var rotatedAbove = 0; var rotatedBelow = 0
            for (y in 0 until result.height) for (x in 0 until result.width) if (Color.red(result.getPixel(x, y)) < 100) {
                if (x in 20..42) verticalInk++
                if (x > 70 && y < 116) rotatedAbove++
                if (x > 70 && y > 140) rotatedBelow++
            }
            assertTrue(verticalInk > 50); assertTrue(rotatedAbove > 10); assertTrue(rotatedBelow > 10)
        } finally { result.recycle() }
    }

    @Test fun unequalLengthLinesStayCentredOnTheirActualBoundingBox() {
        val dir = folder.newFolder(); val source = java.io.File(dir, "source.png")
        val page = Bitmap.createBitmap(400, 260, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        source.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }; page.recycle()
        fun line(left: Float, top: Float, right: Float, bottom: Float) = li.joye.yakuyomi.engine.TextLine(
            listOf(li.joye.yakuyomi.engine.Pt(left, top), li.joye.yakuyomi.engine.Pt(right, top),
                li.joye.yakuyomi.engine.Pt(right, bottom), li.joye.yakuyomi.engine.Pt(left, bottom)), 1f)
        val region = li.joye.yakuyomi.engine.TextRegion(listOf(line(100f, 100f, 300f, 120f), line(100f, 125f, 140f, 145f)),
            "h", 0f, 160f, 122.5f, 200f, 45f)
        val analysis = TranslationAnalysis(source, 400, 260, 1, listOf(TranslationBlock(region, "MMMMMM")), emptyList())
        val output = SinglePageTranslation(RuntimeEnvironment.getApplication(), AtomicBoolean(), {}).render(analysis, dir, TranslationStyle())
        val result = BitmapFactory.decodeFile(output.path)
        try {
            val xs = mutableListOf<Int>()
            for (y in 0 until 260) for (x in 0 until 400) if (Color.red(result.getPixel(x, y)) < 100) xs += x
            assertTrue(xs.isNotEmpty()); assertTrue(xs.min() >= 100); assertTrue(xs.max() > 250)
        } finally { result.recycle() }
    }

    @Test fun cancellationDuringEncodingRemovesIntermediateAndAllowsImmediateRetry() {
        val dir = folder.newFolder(); val source = java.io.File(dir, "source.png")
        val page = Bitmap.createBitmap(64, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        source.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }; page.recycle()
        val analysis = TranslationAnalysis(source, 64, 256, 1, emptyList(), emptyList())
        val cancelled = AtomicBoolean()
        val pipeline = SinglePageTranslation(RuntimeEnvironment.getApplication(), cancelled) { if (it == "encode") cancelled.set(true) }
        assertThrows(CancellationException::class.java) { pipeline.render(analysis, dir, TranslationStyle()) }
        assertEquals(listOf("source.png"), dir.listFiles()!!.map { it.name })
        val output = SinglePageTranslation(RuntimeEnvironment.getApplication(), AtomicBoolean(), {}).render(analysis, dir, TranslationStyle())
        assertTrue(output.isFile)
    }

}
