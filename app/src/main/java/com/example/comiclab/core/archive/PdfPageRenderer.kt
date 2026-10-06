package com.example.comiclab.core.archive

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.Locale

internal class PdfRendererLifecycleGate {
    private val lock = Any()
    private var closed = false

    fun <T> withOpen(block: () -> T): T? {
        return synchronized(lock) {
            if (closed) {
                null
            } else {
                block()
            }
        }
    }

    fun close(closeAction: () -> Unit) {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            closeAction()
        }
    }
}

internal fun <T> createPdfRendererOrCloseDescriptor(
    createRenderer: () -> T,
    closeDescriptor: () -> Unit
): T {
    return try {
        createRenderer()
    } catch (throwable: Throwable) {
        runCatching { closeDescriptor() }
        throw throwable
    }
}

internal object PdfPageRenderer {
    private const val PDF_PAGE_ENTRY_PREFIX = "pdf_page_"

    fun isPageEntry(entryName: String): Boolean = pdfPageIndex(entryName) != null

    fun imageEntries(file: File): List<String> {
        return runCatching {
            withPdfRenderer(file) { renderer ->
                (0 until renderer.pageCount).map(::pdfPageEntryName)
            }
        }.getOrDefault(emptyList())
    }

    fun decodeImages(
        file: File,
        entryNames: List<String>,
        maxSize: Int,
        onDecoded: (entryName: String, bitmap: Bitmap?) -> Boolean
    ) {
        runCatching {
            withPdfRenderer(file) { renderer ->
                for (entryName in entryNames) {
                    val pageIndex = pdfPageIndex(entryName)
                    val bitmap = if (pageIndex != null && pageIndex in 0 until renderer.pageCount) {
                        val page = renderer.openPage(pageIndex)
                        try {
                            renderPageFitMaxSize(page, maxSize)
                        } finally {
                            page.close()
                        }
                    } else {
                        null
                    }

                    if (!onDecoded(entryName, bitmap)) {
                        break
                    }
                }
            }
        }
    }

    fun renderPageFitMaxSize(file: File, entryName: String, maxSize: Int): Bitmap? {
        val pageIndex = pdfPageIndex(entryName) ?: return null
        return runCatching {
            withPdfRenderer(file) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) {
                    return@withPdfRenderer null
                }

                val page = renderer.openPage(pageIndex)
                try {
                    renderPageFitMaxSize(page, maxSize)
                } finally {
                    page.close()
                }
            }
        }.getOrNull()
    }

    fun renderPageForWidth(file: File, entryName: String, targetWidth: Int): Bitmap? {
        val pageIndex = pdfPageIndex(entryName) ?: return null
        return runCatching {
            withPdfRenderer(file) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) {
                    return@withPdfRenderer null
                }

                val page = renderer.openPage(pageIndex)
                try {
                    renderPageForWidth(page, targetWidth)
                } finally {
                    page.close()
                }
            }
        }.getOrNull()
    }

    fun openReaderSession(file: File): ArchiveReaderSession = PdfReaderSession(file)

    private class PdfReaderSession(
        file: File
    ) : ArchiveReaderSession {

        private val pdfLifecycleGate = PdfRendererLifecycleGate()
        private val parcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val pdfRenderer = createPdfRendererOrCloseDescriptor(
            createRenderer = { PdfRenderer(parcelFileDescriptor) },
            closeDescriptor = { parcelFileDescriptor.close() }
        )

        override fun isPdfSource(): Boolean = true

        override fun readBounds(entryName: String): ArchiveReaderSessionBounds? {
            val pageIndex = pdfPageIndex(entryName) ?: return null
            return pdfLifecycleGate.withOpen {
                if (pageIndex !in 0 until pdfRenderer.pageCount) {
                    return@withOpen null
                }

                val page = pdfRenderer.openPage(pageIndex)
                try {
                    ArchiveReaderSessionBounds(page.width, page.height)
                } finally {
                    page.close()
                }
            }
        }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? =
            renderPage(entryName, targetWidth, 0, RenderMode.WIDTH)

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? =
            renderPage(entryName, targetWidth, 0, RenderMode.WIDTH)

        override fun decodeImageForPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? = renderPage(entryName, targetWidth, targetHeight, RenderMode.FIT_BOUNDS)

        override fun decodeRegionForWidth(
            entryName: String,
            sourceRect: Rect,
            targetWidth: Int
        ): Bitmap? {
            val pageIndex = pdfPageIndex(entryName) ?: return null
            return pdfLifecycleGate.withOpen {
                if (pageIndex !in 0 until pdfRenderer.pageCount) {
                    return@withOpen null
                }

                val page = pdfRenderer.openPage(pageIndex)
                try {
                    renderPageRegion(page, sourceRect, targetWidth)
                } finally {
                    page.close()
                }
            }
        }

        override fun close() {
            pdfLifecycleGate.close {
                try {
                    pdfRenderer.close()
                } finally {
                    parcelFileDescriptor.close()
                }
            }
        }

        private fun renderPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int,
            mode: RenderMode
        ): Bitmap? {
            val pageIndex = pdfPageIndex(entryName) ?: return null
            return pdfLifecycleGate.withOpen {
                if (pageIndex !in 0 until pdfRenderer.pageCount) {
                    return@withOpen null
                }

                val page = pdfRenderer.openPage(pageIndex)
                try {
                    when (mode) {
                        RenderMode.WIDTH -> renderPageForWidth(page, targetWidth)
                        RenderMode.FIT_BOUNDS -> renderPageFitBounds(page, targetWidth, targetHeight)
                    }
                } finally {
                    page.close()
                }
            }
        }

        private enum class RenderMode {
            WIDTH,
            FIT_BOUNDS
        }
    }

    private fun renderPageFitMaxSize(page: PdfRenderer.Page, maxSize: Int): Bitmap? {
        if (maxSize <= 0 || page.width <= 0 || page.height <= 0) {
            return null
        }

        return renderPageFitBounds(page, maxSize, maxSize)
    }

    private fun renderPageForWidth(page: PdfRenderer.Page, targetWidth: Int): Bitmap? {
        if (targetWidth <= 0 || page.width <= 0 || page.height <= 0) {
            return null
        }

        val size = PdfRenderSizePolicy.calculatePreviewSize(
            pageWidth = page.width,
            pageHeight = page.height,
            targetWidth = targetWidth,
            maxWidth = PdfRenderSizePolicy.MAX_RENDER_WIDTH,
            maxHeight = PdfRenderSizePolicy.MAX_PREVIEW_HEIGHT,
            maxPixels = PdfRenderSizePolicy.MAX_PREVIEW_PIXELS
        ) ?: return null
        val scale = size.width.toFloat() / page.width.toFloat()
        return renderPage(page, size.width, size.height, scale)
    }

    private fun renderPageFitBounds(
        page: PdfRenderer.Page,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0 || page.width <= 0 || page.height <= 0) {
            return null
        }

        val boundedTargetWidth = targetWidth.coerceAtMost(PdfRenderSizePolicy.MAX_RENDER_WIDTH)
        val scale = minOf(
            boundedTargetWidth.toFloat() / page.width.toFloat(),
            targetHeight.toFloat() / page.height.toFloat()
        )
        val bitmapWidth = (page.width * scale).toInt().coerceAtLeast(1)
        val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
        return renderPage(page, bitmapWidth, bitmapHeight, scale)
    }

    private fun renderPageRegion(
        page: PdfRenderer.Page,
        sourceRect: Rect,
        targetWidth: Int
    ): Bitmap? {
        if (targetWidth <= 0 || page.width <= 0 || page.height <= 0) {
            return null
        }

        val boundedRect = Rect(sourceRect).apply {
            left = left.coerceIn(0, page.width)
            top = top.coerceIn(0, page.height)
            right = right.coerceIn(left, page.width)
            bottom = bottom.coerceIn(top, page.height)
        }
        if (boundedRect.width() <= 0 || boundedRect.height() <= 0) {
            return null
        }

        val scale = targetWidth.toFloat() / boundedRect.width().toFloat()
        val bitmapHeight = (boundedRect.height() * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(-boundedRect.left * scale, -boundedRect.top * scale)
        }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun renderPage(
        page: PdfRenderer.Page,
        bitmapWidth: Int,
        bitmapHeight: Int,
        scale: Float
    ): Bitmap? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || scale <= 0f) {
            return null
        }

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val matrix = Matrix().apply {
            setScale(scale, scale)
        }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun <T> withPdfRenderer(file: File, block: (PdfRenderer) -> T): T {
        val parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(parcelFileDescriptor)
            return block(renderer)
        } finally {
            renderer?.close()
            parcelFileDescriptor.close()
        }
    }

    private fun pdfPageEntryName(index: Int): String =
        String.format(Locale.ROOT, "%s%06d", PDF_PAGE_ENTRY_PREFIX, index)

    private fun pdfPageIndex(entryName: String): Int? {
        if (!entryName.startsWith(PDF_PAGE_ENTRY_PREFIX)) {
            return null
        }

        return entryName
            .removePrefix(PDF_PAGE_ENTRY_PREFIX)
            .toIntOrNull()
            ?.takeIf { it >= 0 }
    }
}
