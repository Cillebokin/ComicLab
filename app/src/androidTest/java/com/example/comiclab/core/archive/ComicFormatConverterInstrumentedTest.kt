package com.example.comiclab.core.archive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComicFormatConverterInstrumentedTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun firstPageWriteFailureReturnsTheOriginalErrorAndAllowsRetry() {
        verifyPageWriteFailure(pageNumber = 1)
    }

    @Test
    fun middlePageWriteFailureReturnsTheOriginalErrorAndAllowsRetry() {
        verifyPageWriteFailure(pageNumber = 6)
    }

    @Test
    fun macOsMetadataDoesNotCreatePagesOrBreakPdfConversion() {
        val source = createComic(
            pageCount = 2,
            metadataEntries = listOf("._page1.png", "__MACOSX/._page2.png")
        )
        assertSuccessfulConversion(source, File(temporaryFolder.root, "metadata-filtered.pdf"), pageCount = 2)
    }

    @Test
    fun jpegExifOrientationControlsPdfDimensionsAndRenderedPixelsInNaturalOrder() {
        val red = Color.RED
        val green = Color.GREEN
        val blue = Color.BLUE
        val yellow = Color.YELLOW
        val cases = listOf(
            ExifCase(ExifInterface.ORIENTATION_NORMAL, false, listOf(red, green, blue, yellow)),
            ExifCase(ExifInterface.ORIENTATION_ROTATE_90, true, listOf(blue, red, yellow, green)),
            ExifCase(ExifInterface.ORIENTATION_ROTATE_180, false, listOf(yellow, blue, green, red)),
            ExifCase(ExifInterface.ORIENTATION_ROTATE_270, true, listOf(green, yellow, red, blue)),
            ExifCase(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, false, listOf(green, red, yellow, blue)),
            ExifCase(ExifInterface.ORIENTATION_FLIP_VERTICAL, false, listOf(blue, yellow, red, green)),
            ExifCase(ExifInterface.ORIENTATION_TRANSPOSE, true, listOf(red, blue, green, yellow)),
            ExifCase(ExifInterface.ORIENTATION_TRANSVERSE, true, listOf(yellow, green, blue, red)),
            ExifCase(null, false, listOf(red, green, blue, yellow))
        )
        val source = File(temporaryFolder.root, "exif.cbz")
        ZipOutputStream(source.outputStream()).use { zip ->
            for (index in cases.indices.reversed()) {
                val name = "page${index + 2}.jpg"
                val image = createQuadrantJpeg(name, cases[index].orientation)
                zip.putNextEntry(ZipEntry(name))
                image.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        val output = File(temporaryFolder.root, "exif.pdf")
        val progress = mutableListOf<Pair<Int, Int>>()
        ComicFormatConverter.convert(source, output, ComicConversionTarget.PDF, AtomicBoolean()) { page, total ->
            progress += page to total
        }
        assertEquals((1..cases.size).map { it to cases.size }, progress)
        PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            assertEquals(cases.size, pdf.pageCount)
            cases.forEachIndexed { index, case ->
                pdf.openPage(index).use { page ->
                    val label = "page ${index + 1}, EXIF orientation ${case.orientation}"
                    assertEquals(label, if (case.swapsAxes) 48 else 72, page.width)
                    assertEquals(label, if (case.swapsAxes) 72 else 48, page.height)
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val left = page.width / 4
                        val right = page.width - left
                        val top = page.height / 4
                        val bottom = page.height - top
                        val actual = listOf(
                            rendered.getPixel(left, top),
                            rendered.getPixel(right, top),
                            rendered.getPixel(left, bottom),
                            rendered.getPixel(right, bottom)
                        )
                        case.corners.zip(actual).forEachIndexed { corner, (expected, pixel) ->
                            assertTrue(
                                "$label corner $corner should be ${Integer.toHexString(expected)}, " +
                                    "got ${Integer.toHexString(pixel)}",
                                abs(Color.red(expected) - Color.red(pixel)) < 24 &&
                                    abs(Color.green(expected) - Color.green(pixel)) < 24 &&
                                    abs(Color.blue(expected) - Color.blue(pixel)) < 24
                            )
                        }
                    } finally {
                        rendered.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun userCancellationWhileDecoderIsBlockedRemovesTemporaryFiles() {
        val source = createComic()
        val output = File(temporaryFolder.root, "cancelled.pdf")
        val cancelled = AtomicBoolean()
        val reachedCancellation = CountDownLatch(1)

        val result = runConversion(cancelled, reachedCancellation) {
            ComicFormatConverter.convert(source, output, ComicConversionTarget.PDF, cancelled) { _, _ ->
                awaitFullDecodeQueue()
                cancelled.set(true)
                reachedCancellation.countDown()
            }
        }

        assertTrue("expected cancellation, got $result", result is CancellationException)
        assertCleaned(output)
        assertSuccessfulConversion(source, output)
    }

    @Test
    fun interruptedConversionWaitsForDecoderCleanupAndPreservesInterrupt() {
        val source = createComic()
        val output = File(temporaryFolder.root, "interrupted.pdf")
        val cancelled = AtomicBoolean()
        val reachedInterrupt = CountDownLatch(1)
        val stillInterrupted = AtomicBoolean()

        val result = runConversion(cancelled, reachedInterrupt) {
            try {
                ComicFormatConverter.convert(source, output, ComicConversionTarget.PDF, cancelled) { _, _ ->
                    awaitFullDecodeQueue()
                    Thread.currentThread().interrupt()
                    reachedInterrupt.countDown()
                }
            } finally {
                stillInterrupted.set(Thread.currentThread().isInterrupted)
            }
        }

        assertTrue("expected cancellation after interrupt, got $result", result is CancellationException)
        assertTrue(stillInterrupted.get())
        assertCleaned(output)
    }

    private fun verifyPageWriteFailure(pageNumber: Int) {
        val source = createComic()
        val output = File(temporaryFolder.root, "converted.pdf")
        val cancelled = AtomicBoolean()
        val reachedFailure = CountDownLatch(1)
        val writeFailure = IOException("Injected PDF write failure on page $pageNumber")
        val progress = mutableListOf<Int>()

        val result = runConversion(cancelled, reachedFailure) {
            ComicFormatConverter.convertArchiveToPdf(
                source, output, cancelled,
                onPage = { completed, _ -> progress += completed },
                appendPage = { document, bitmap, page, _ ->
                    if (page == pageNumber) {
                        awaitFullDecodeQueue()
                        reachedFailure.countDown()
                        throw writeFailure
                    }
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, page).create()
                    document.finishPage(document.startPage(pageInfo))
                }
            )
        }

        assertSame(writeFailure, result)
        assertFalse("write failure must not set the user's cancellation flag", cancelled.get())
        assertEquals((1 until pageNumber).toList(), progress)
        assertCleaned(output)
        assertSuccessfulConversion(source, output)
    }

    private fun runConversion(
        cancelled: AtomicBoolean,
        trigger: CountDownLatch,
        convert: () -> Unit
    ): Throwable? {
        val job = FutureTask { runCatching(convert).exceptionOrNull() }
        Thread(job, "test-pdf-conversion").apply { isDaemon = true }.start()
        try {
            if (!trigger.await(10, TimeUnit.SECONDS)) {
                throw AssertionError("conversion did not reach the test trigger", if (job.isDone) job.get() else null)
            }
            return try {
                job.get(3, TimeUnit.SECONDS)
            } catch (failure: TimeoutException) {
                throw AssertionError("conversion waited for the blocked decoder after the test trigger", failure)
            }
        } finally {
            // Recover the baseline hang after recording it, so the next case starts with an empty queue.
            if (!job.isDone) {
                cancelled.set(true)
                job.get(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun awaitFullDecodeQueue() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.name == "comic-conversion-decode" && thread.state == Thread.State.TIMED_WAITING &&
                        stack.any { it.className == "java.util.concurrent.ArrayBlockingQueue" && it.methodName == "offer" }
                }
            ) return
            SystemClock.sleep(5)
        }
        error("decoder did not block on the full page queue")
    }

    private fun assertCleaned(output: File) {
        assertFalse("unfinished PDF remains", output.exists())
        assertFalse("temporary extraction directory remains", File(output.parentFile, "extracted-images").exists())
        Thread.getAllStackTraces().keys.filter { it.name == "comic-conversion-decode" }.forEach { worker ->
            worker.join(1_000)
            assertFalse("decode worker is still alive", worker.isAlive)
        }
    }

    private fun assertSuccessfulConversion(source: File, output: File, pageCount: Int = PAGE_COUNT) {
        val progress = mutableListOf<Pair<Int, Int>>()
        ComicFormatConverter.convert(source, output, ComicConversionTarget.PDF, AtomicBoolean()) { page, total ->
            progress += page to total
        }
        assertEquals((1..pageCount).map { it to pageCount }, progress)
        assertFalse(File(output.parentFile, "extracted-images").exists())
        val descriptor = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { pdf ->
            assertEquals(pageCount, pdf.pageCount)
            for (index in 0 until pdf.pageCount) {
                pdf.openPage(index).use { page ->
                    assertEquals("unexpected page at index $index", 48 + 6 * (index + 1), page.width)
                }
            }
        }
    }

    private data class ExifCase(val orientation: Int?, val swapsAxes: Boolean, val corners: List<Int>)

    private fun createQuadrantJpeg(name: String, orientation: Int?): File {
        val image = File(temporaryFolder.root, name)
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
                paint.color = color
                val left = (index % 2) * 150f
                val top = (index / 2) * 100f
                canvas.drawRect(left, top, left + 150f, top + 100f, paint)
            }
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        if (orientation != null) {
            ExifInterface(image.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return image
    }

    private fun createComic(pageCount: Int = PAGE_COUNT, metadataEntries: List<String> = emptyList()): File {
        val file = File(temporaryFolder.root, "source.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (entry in metadataEntries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(byteArrayOf(0, 5, 22, 7))
                zip.closeEntry()
            }
            for (page in pageCount downTo 1) {
                zip.putNextEntry(ZipEntry("page$page.png"))
                val bitmap = Bitmap.createBitmap(200 + 25 * page, 300, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.BLUE)
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                } finally {
                    bitmap.recycle()
                }
                zip.closeEntry()
            }
        }
        return file
    }

    private companion object {
        const val PAGE_COUNT = 12
    }
}
