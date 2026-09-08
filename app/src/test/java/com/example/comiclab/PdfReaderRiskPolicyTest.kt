package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PdfReaderRiskPolicyTest {

    @Test
    fun sharedPdfSessionClosesUnderlyingRendererAfterBothAdaptersReleaseIt() {
        val delegate = FakeReaderSession()
        val shared = ComicArchive.SharedImageReaderSession.wrap(delegate)
        val mainReaderLease = shared.acquire()
        val previewReaderLease = shared.acquire()

        mainReaderLease.close()

        assertFalse(delegate.closed)

        previewReaderLease.close()

        assertTrue(delegate.closed)
    }

    @Test
    fun previewBitmapIsReleasedOnlyForNonTiledFullPages() {
        assertTrue(shouldReleasePreviewAfterFullDecode(isTiled = false))
        assertFalse(shouldReleasePreviewAfterFullDecode(isTiled = true))
    }

    @Test
    fun lowMemoryPdfModeSkipsOnlyBackgroundPreloads() {
        assertTrue(
            shouldSkipPdfPreloadAfterMemoryTrim(
                isPdfSource = true,
                lowMemoryMode = true,
                isPreload = true
            )
        )
        assertFalse(
            shouldSkipPdfPreloadAfterMemoryTrim(
                isPdfSource = true,
                lowMemoryMode = true,
                isPreload = false
            )
        )
        assertFalse(
            shouldSkipPdfPreloadAfterMemoryTrim(
                isPdfSource = false,
                lowMemoryMode = true,
                isPreload = true
            )
        )
    }

    @Test
    fun lowMemoryTiledPdfPreloadDoesNotDecodeItsPreview() {
        assertTrue(
            shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim(
                isPdfSource = true,
                lowMemoryMode = true,
                isPreload = true
            )
        )
        assertFalse(
            shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim(
                isPdfSource = true,
                lowMemoryMode = true,
                isPreload = false
            )
        )
    }

    @Test
    fun pdfFailureAllowsOnlyOneVisibleRetry() {
        assertFalse(
            shouldRetryPdfPageAfterFailure(
                isPdfSource = true,
                isPreload = true,
                retryAlreadyUsed = false
            )
        )
        assertTrue(
            shouldRetryPdfPageAfterFailure(
                isPdfSource = true,
                isPreload = false,
                retryAlreadyUsed = false
            )
        )
        assertFalse(
            shouldRetryPdfPageAfterFailure(
                isPdfSource = true,
                isPreload = false,
                retryAlreadyUsed = true
            )
        )
    }

    @Test
    fun previewThumbnailDecodeStopsAfterMemoryTrim() {
        assertTrue(shouldSkipReaderPreviewDecodeAfterMemoryTrim(lowMemoryMode = true))
        assertFalse(shouldSkipReaderPreviewDecodeAfterMemoryTrim(lowMemoryMode = false))
    }

    @Test
    fun pdfPreviewSizeIsBoundedByHeightAndPixelBudget() {
        val size = calculatePdfPreviewSize(
            pageWidth = 100,
            pageHeight = 10_000,
            targetWidth = 1_080,
            maxWidth = 4_096,
            maxHeight = 4_096,
            maxPixels = 2_000_000L
        )

        assertNotNull(size)
        assertTrue(size!!.width <= 4_096)
        assertTrue(size.height <= 4_096)
        assertTrue(size.width.toLong() * size.height <= 2_000_000L)
    }

    @Test
    fun pdfPreviewSizeKeepsNormalPageAtRequestedWidthWhenWithinBudget() {
        val size = calculatePdfPreviewSize(
            pageWidth = 200,
            pageHeight = 288,
            targetWidth = 1_080,
            maxWidth = 4_096,
            maxHeight = 4_096,
            maxPixels = 2_000_000L
        )

        assertNotNull(size)
        assertTrue(size!!.width == 1_080)
        assertTrue(size.height == 1_555)
    }

    @Test
    fun pdfPreviewSizeNeverExceedsPixelBudgetAfterMinimumDimensionClamp() {
        val size = calculatePdfPreviewSize(
            pageWidth = 1,
            pageHeight = 1_000,
            targetWidth = 1_080,
            maxWidth = 4_096,
            maxHeight = 4_096,
            maxPixels = 1L
        )

        assertNotNull(size)
        assertTrue(size!!.width.toLong() * size.height <= 1L)
    }

    @Test
    fun pdfRendererCloseWaitsForActiveRenderAndRejectsNewRenderAfterClose() {
        val gate = PdfRendererLifecycleGate()
        val renderStarted = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        Thread {
            gate.withOpen {
                renderStarted.countDown()
                releaseRender.await(2, TimeUnit.SECONDS)
            }
        }.start()

        assertTrue(renderStarted.await(2, TimeUnit.SECONDS))

        Thread {
            gate.close {
                closed.set(true)
            }
            closeFinished.countDown()
        }.start()

        assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(closed.get())

        releaseRender.countDown()

        assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
        assertTrue(closed.get())
        assertTrue(gate.withOpen { true } == null)
    }

    @Test
    fun pdfRendererCreationClosesDescriptorWhenConstructorFails() {
        var descriptorClosed = false

        val result = runCatching<String> {
            createPdfRendererOrCloseDescriptor(
                createRenderer = { throw IllegalStateException("renderer failed") },
                closeDescriptor = { descriptorClosed = true }
            )
        }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(descriptorClosed)
    }

    private class FakeReaderSession : ComicArchive.ImageReaderSession {
        var closed = false

        override fun isPdfSource(): Boolean = true

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? = null

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? = null

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? = null

        override fun decodeImageForPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? = null

        override fun decodeRegionForWidth(
            entryName: String,
            sourceRect: Rect,
            targetWidth: Int
        ): Bitmap? = null

        override fun close() {
            closed = true
        }
    }
}
