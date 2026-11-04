package com.example.comiclab.core.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.comiclab.ComicArchive
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class ComicConversionTarget(
    val extension: String,
    val mimeType: String
) {
    PDF("pdf", "application/pdf"),
    CBZ("cbz", "application/vnd.comicbook+zip")
}

/** Converts supported local comic files into page based PDF or CBZ files. */
internal object ComicFormatConverter {
    private const val MAX_PAGE_EDGE = 4096
    private const val MAX_PAGE_PIXELS = 8_000_000L
    private const val TARGET_DPI = 300L

    private sealed interface ArchivePageResult {
        data class Decoded(val pageIndex: Int, val bitmap: Bitmap) : ArchivePageResult
        data class Failed(val failure: Throwable) : ArchivePageResult
        data object Complete : ArchivePageResult
    }

    private data class PendingPngPage(val pageIndex: Int, val encodedFile: Future<File>)

    fun convert(
        source: File,
        output: File,
        target: ComicConversionTarget,
        cancelled: AtomicBoolean,
        onPage: (completed: Int, total: Int) -> Unit
    ) {
        check(source.isFile) { "Source comic is unavailable" }
        check(!output.exists()) { "Conversion output already exists" }
        output.parentFile?.let { check(it.mkdirs() || it.isDirectory) }

        when (target) {
            ComicConversionTarget.PDF -> {
                check(ArchiveFormatDetector.isSupportedArchive(source) &&
                    !ArchiveFormatDetector.isPdf(source)) { "Source is not a supported comic archive" }
                convertArchiveToPdf(source, output, cancelled, onPage)
            }
            ComicConversionTarget.CBZ -> {
                check(ArchiveFormatDetector.isPdf(source)) { "Source is not a PDF comic" }
                convertPdfToCbz(source, output, cancelled, onPage)
            }
        }
    }

    private fun convertArchiveToPdf(
        source: File,
        output: File,
        cancelled: AtomicBoolean,
        onPage: (completed: Int, total: Int) -> Unit
    ) {
        val entries = ComicArchive.imageEntries(source)
        check(entries.isNotEmpty()) { "No readable comic pages" }

        val extractionDirectory = File(output.parentFile, "extracted-images")
        check(extractionDirectory.mkdirs() || extractionDirectory.isDirectory)
        val document = PdfDocument()
        val decodedPages = ArrayBlockingQueue<ArchivePageResult>(1)
        val decoder = Executors.newSingleThreadExecutor { task ->
            Thread(task, "comic-conversion-decode").apply { isDaemon = true }
        }
        try {
            decoder.execute {
                try {
                    ComicArchive.openPreparedImageExtractor(source, extractionDirectory).use { extractor ->
                        entries.forEachIndexed { index, entryName ->
                            checkCancelled(cancelled)
                            val imageFile = extractor.extract(index, entryName)
                                ?: throw IOException("Could not extract page ${index + 1}")
                            val bitmap = try {
                                checkCancelled(cancelled)
                                decodeBoundedImage(imageFile)
                                    ?: throw IOException("Could not decode page ${index + 1}")
                            } finally {
                                ComicArchive.discardPreparedImageFile(imageFile)
                            }
                            offerArchivePage(
                                decodedPages,
                                ArchivePageResult.Decoded(index, bitmap),
                                cancelled
                            )
                        }
                    }
                    offerArchivePage(decodedPages, ArchivePageResult.Complete, cancelled)
                } catch (failure: Throwable) {
                    if (failure !is CancellationException && !cancelled.get()) {
                        offerArchivePage(decodedPages, ArchivePageResult.Failed(failure), cancelled)
                    }
                } finally {
                    extractionDirectory.deleteRecursively()
                }
            }

            while (true) {
                checkCancelled(cancelled)
                when (val next = takeArchivePage(decodedPages, cancelled)) {
                    is ArchivePageResult.Decoded -> {
                        try {
                            checkCancelled(cancelled)
                            appendBitmapPage(document, next.bitmap, next.pageIndex + 1)
                        } finally {
                            next.bitmap.recycle()
                        }
                        onPage(next.pageIndex + 1, entries.size)
                    }
                    is ArchivePageResult.Failed -> throw next.failure
                    ArchivePageResult.Complete -> break
                }
            }

            checkCancelled(cancelled)
            CancellationCheckingOutputStream(
                output.outputStream().buffered(256 * 1024),
                cancelled
            ).use { stream ->
                document.writeTo(stream)
            }
            check(output.isFile && output.length() > 0L) { "PDF output is empty" }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            if (cancelled.get()) decoder.shutdownNow() else decoder.shutdown()
            awaitWorkerExecutor(decoder)
            drainDecodedPages(decodedPages)
            extractionDirectory.deleteRecursively()
            runCatching { document.close() }
        }
    }

    private fun appendBitmapPage(document: PdfDocument, bitmap: Bitmap, pageNumber: Int) {
        val pageWidth = pixelsToPdfPoints(bitmap.width)
        val pageHeight = pixelsToPdfPoints(bitmap.height)
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        val page = document.startPage(pageInfo)
        var pageFinished = false
        try {
            page.canvas.drawBitmap(
                bitmap,
                null,
                RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()),
                null
            )
            document.finishPage(page)
            pageFinished = true
        } finally {
            if (!pageFinished) runCatching { document.finishPage(page) }
        }
    }

    private fun offerArchivePage(
        queue: ArrayBlockingQueue<ArchivePageResult>,
        result: ArchivePageResult,
        cancelled: AtomicBoolean
    ) {
        var offered = false
        try {
            while (!cancelled.get() && !Thread.currentThread().isInterrupted) {
                if (queue.offer(result, 100, TimeUnit.MILLISECONDS)) {
                    offered = true
                    return
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (!offered && result is ArchivePageResult.Decoded) {
                result.bitmap.recycle()
            }
        }
    }

    private fun takeArchivePage(
        queue: ArrayBlockingQueue<ArchivePageResult>,
        cancelled: AtomicBoolean
    ): ArchivePageResult {
        while (true) {
            checkCancelled(cancelled)
            try {
                queue.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException()
            }
        }
    }

    private fun drainDecodedPages(queue: ArrayBlockingQueue<ArchivePageResult>) {
        while (true) {
            val result = queue.poll() ?: return
            if (result is ArchivePageResult.Decoded) runCatching { result.bitmap.recycle() }
        }
    }

    private fun awaitWorkerExecutor(executor: java.util.concurrent.ExecutorService) {
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
    }

    private fun convertPdfToCbz(
        source: File,
        output: File,
        cancelled: AtomicBoolean,
        onPage: (completed: Int, total: Int) -> Unit
    ) {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val encodedPagesDirectory = File(output.parentFile, "encoded-pages")
        val encoder = Executors.newSingleThreadExecutor { task ->
            Thread(task, "comic-conversion-png").apply { isDaemon = true }
        }
        try {
            check(encodedPagesDirectory.mkdirs() || encodedPagesDirectory.isDirectory)
            val openedDescriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = openedDescriptor
            renderer = PdfRenderer(openedDescriptor)
            val total = renderer.pageCount
            check(total > 0) { "PDF has no pages" }

            ZipOutputStream(
                CancellationCheckingOutputStream(
                    output.outputStream().buffered(256 * 1024),
                    cancelled
                )
            ).use { zip ->
                // PNG 已压缩过，外层 ZIP 不再重复执行高成本压缩。
                zip.setLevel(Deflater.NO_COMPRESSION)
                var pendingPage: PendingPngPage? = null
                for (index in 0 until total) {
                    checkCancelled(cancelled)
                    val page = renderer.openPage(index)
                    val bitmap = try {
                        renderBoundedPage(page)
                    } finally {
                        page.close()
                    }
                    var encoderOwnsBitmap = false
                    try {
                        checkCancelled(cancelled)
                        pendingPage?.let { pending ->
                            appendEncodedPage(zip, pending, total, cancelled, onPage)
                            pendingPage = null
                        }
                        val encodedFile = File(
                            encodedPagesDirectory,
                            String.format(Locale.ROOT, "%06d.png", index + 1)
                        )
                        pendingPage = PendingPngPage(
                            index,
                            encoder.submit<File> {
                                try {
                                    checkCancelled(cancelled)
                                    CancellationCheckingOutputStream(
                                        encodedFile.outputStream().buffered(256 * 1024),
                                        cancelled
                                    ).use { png ->
                                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                                            "Could not encode page ${index + 1}"
                                        }
                                    }
                                    checkCancelled(cancelled)
                                    encodedFile
                                } catch (failure: Throwable) {
                                    encodedFile.delete()
                                    throw failure
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        )
                        encoderOwnsBitmap = true
                    } finally {
                        if (!encoderOwnsBitmap) bitmap.recycle()
                    }
                }
                pendingPage?.let { pending ->
                    appendEncodedPage(zip, pending, total, cancelled, onPage)
                    pendingPage = null
                }
                checkCancelled(cancelled)
            }
            check(output.isFile && output.length() > 0L) { "CBZ output is empty" }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            encoder.shutdown()
            awaitWorkerExecutor(encoder)
            encodedPagesDirectory.deleteRecursively()
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    private fun appendEncodedPage(
        zip: ZipOutputStream,
        pending: PendingPngPage,
        total: Int,
        cancelled: AtomicBoolean,
        onPage: (completed: Int, total: Int) -> Unit
    ) {
        checkCancelled(cancelled)
        val encodedFile = try {
            pending.encodedFile.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException()
        }
        checkCancelled(cancelled)
        zip.putNextEntry(
            ZipEntry(String.format(Locale.ROOT, "%06d.png", pending.pageIndex + 1))
        )
        encodedFile.inputStream().buffered(256 * 1024).use { input ->
            input.copyTo(zip, 256 * 1024)
        }
        zip.closeEntry()
        encodedFile.delete()
        onPage(pending.pageIndex + 1, total)
    }

    private fun decodeBoundedImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeForBounds(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun sampleSizeForBounds(width: Int, height: Int): Int {
        var sampleSize = 1
        while (true) {
            val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
            val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
            if (sampledWidth <= MAX_PAGE_EDGE &&
                sampledHeight <= MAX_PAGE_EDGE &&
                sampledWidth * sampledHeight <= MAX_PAGE_PIXELS
            ) {
                return sampleSize
            }
            sampleSize *= 2
        }
    }

    private fun renderBoundedPage(page: PdfRenderer.Page): Bitmap {
        val targetWidth = (page.width.toLong() * TARGET_DPI / 72L)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
        val size = checkNotNull(
            PdfRenderSizePolicy.calculatePreviewSize(
                pageWidth = page.width,
                pageHeight = page.height,
                targetWidth = targetWidth,
                maxWidth = MAX_PAGE_EDGE,
                maxHeight = MAX_PAGE_EDGE,
                maxPixels = MAX_PAGE_PIXELS
            )
        ) { "Invalid PDF page dimensions" }
        val scale = minOf(
            size.width.toFloat() / page.width,
            size.height.toFloat() / page.height
        )
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            page.render(
                bitmap,
                null,
                Matrix().apply { setScale(scale, scale) },
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
    }

    private fun pixelsToPdfPoints(pixels: Int): Int =
        (pixels.toLong() * 72L / TARGET_DPI).coerceAtLeast(1L).toInt()

    private class CancellationCheckingOutputStream(
        output: OutputStream,
        private val cancelled: AtomicBoolean
    ) : FilterOutputStream(output) {
        override fun write(value: Int) {
            checkCancelled(cancelled)
            out.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkCancelled(cancelled)
            out.write(bytes, offset, length)
        }
    }
}
