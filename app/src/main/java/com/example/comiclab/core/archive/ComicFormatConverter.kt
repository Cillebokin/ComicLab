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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
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
        try {
            ComicArchive.openPreparedImageExtractor(source, extractionDirectory).use { extractor ->
                entries.forEachIndexed { index, entryName ->
                    checkCancelled(cancelled)
                    val imageFile = extractor.extract(index, entryName)
                        ?: throw IOException("Could not extract page ${index + 1}")
                    try {
                        checkCancelled(cancelled)
                        val bitmap = decodeBoundedImage(imageFile)
                            ?: throw IOException("Could not decode page ${index + 1}")
                        try {
                            checkCancelled(cancelled)
                            val pageWidth = pixelsToPdfPoints(bitmap.width)
                            val pageHeight = pixelsToPdfPoints(bitmap.height)
                            val pageInfo = PdfDocument.PageInfo.Builder(
                                pageWidth,
                                pageHeight,
                                index + 1
                            ).create()
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
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        ComicArchive.discardPreparedImageFile(imageFile)
                    }
                    onPage(index + 1, entries.size)
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
            runCatching { document.close() }
            extractionDirectory.deleteRecursively()
        }
    }

    private fun convertPdfToCbz(
        source: File,
        output: File,
        cancelled: AtomicBoolean,
        onPage: (completed: Int, total: Int) -> Unit
    ) {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(descriptor)
            val total = renderer.pageCount
            check(total > 0) { "PDF has no pages" }

            ZipOutputStream(
                CancellationCheckingOutputStream(
                    output.outputStream().buffered(256 * 1024),
                    cancelled
                )
            ).use { zip ->
                for (index in 0 until total) {
                    checkCancelled(cancelled)
                    val page = renderer.openPage(index)
                    val bitmap = try {
                        renderBoundedPage(page)
                    } finally {
                        page.close()
                    }
                    try {
                        checkCancelled(cancelled)
                        zip.putNextEntry(
                            ZipEntry(String.format(Locale.ROOT, "%06d.png", index + 1))
                        )
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) {
                            "Could not encode page ${index + 1}"
                        }
                        zip.closeEntry()
                    } finally {
                        bitmap.recycle()
                    }
                    onPage(index + 1, total)
                }
                checkCancelled(cancelled)
            }
            check(output.isFile && output.length() > 0L) { "CBZ output is empty" }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor.close() }
        }
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
        if (cancelled.get()) throw CancellationException()
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
