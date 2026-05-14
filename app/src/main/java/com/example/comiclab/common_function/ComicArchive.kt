package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.json.JSONArray
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ComicArchive {
    private const val ZIP_PREVIEW_DECODE_THREAD_COUNT = 2
    private const val PREPARED_READER_MANIFEST_FILE = "reader_manifest.json"
    private const val PDF_PAGE_ENTRY_PREFIX = "pdf_page_"
    private const val PDF_MAX_RENDER_WIDTH = 4096

    data class ImageBounds(
        val width: Int,
        val height: Int
    )

    interface ImageReaderSession : Closeable {
        fun isPdfSource(): Boolean = false
        fun readBounds(entryName: String): ImageBounds?
        fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap?
        fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap?
        fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap?
        fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap?
    }

    data class PreparedImageEntry(
        val index: Int,
        val entryName: String
    )

    private val zipArchiveExtensions = setOf("zip", "cbz")
    private val sevenZipArchiveExtensions = setOf("rar", "cbr", "7z", "cb7")
    private val pdfExtensions = setOf("pdf")
    private val supportedArchiveExtensions = zipArchiveExtensions + sevenZipArchiveExtensions + pdfExtensions
    private val archiveExtensions = supportedArchiveExtensions + setOf("tar", "gz")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val naturalEntryNameComparator = Comparator<String> { left, right ->
        compareNaturalEntryNames(left, right)
    }

    fun isArchive(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.ROOT) in archiveExtensions
    }

    fun isSupportedArchive(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.ROOT) in supportedArchiveExtensions
    }

    fun isPdf(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.ROOT) in pdfExtensions
    }

    fun imageEntries(file: File): List<String> {
        if (!file.isFile) {
            return emptyList()
        }

        return runCatching {
            when (file.extension.lowercase(Locale.ROOT)) {
                in zipArchiveExtensions -> zipImageEntries(file)
                in sevenZipArchiveExtensions -> sevenZipImageEntries(file)
                in pdfExtensions -> pdfImageEntries(file)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    fun firstImageEntryIfFirstFileIsImage(file: File): String? {
        if (!file.isFile) {
            return null
        }

        return runCatching {
            when (file.extension.lowercase(Locale.ROOT)) {
                in zipArchiveExtensions -> zipFileEntries(file)
                in sevenZipArchiveExtensions -> sevenZipFileEntries(file)
                in pdfExtensions -> pdfImageEntries(file)
                else -> emptyList()
            }.firstOrNull()
                ?.takeIf { it.isImageEntryName() || it.isPdfPageEntryName() }
        }.getOrNull()
    }

    fun canDeleteEntry(file: File): Boolean {
        return isSupportedArchive(file) && !isPdf(file)
    }

    fun deleteEntry(file: File, entryName: String): Boolean {
        if (!canDeleteEntry(file) || entryName.isBlank()) {
            return false
        }

        return when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> deleteZipEntry(file, entryName)
            in sevenZipArchiveExtensions -> rebuildSevenZipReadableArchiveWithoutEntry(file, entryName)
            else -> false
        }
    }

    fun decodeImage(file: File, entryName: String, maxSize: Int): Bitmap? {
        if (isPdf(file)) {
            return renderPdfPageFitMaxSize(file, entryName, maxSize)
        }

        val bytes = imageBytes(file, entryName) ?: return null
        return decodeBitmap(
            bytes = bytes,
            preferredConfig = Bitmap.Config.RGB_565,
            sampleSize = { width, height -> calculateInSampleSize(width, height, maxSize) }
        )?.scaleToFitMaxSize(maxSize)
    }

    fun decodeImageForWidth(file: File, entryName: String, targetWidth: Int): Bitmap? {
        if (isPdf(file)) {
            return renderPdfPageForWidth(file, entryName, targetWidth)
        }

        val bytes = imageBytes(file, entryName) ?: return null
        return decodeBitmap(
            bytes = bytes,
            preferredConfig = Bitmap.Config.ARGB_8888,
            sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
        )?.scaleToWidthIfLarger(targetWidth)
    }

    fun decodeImages(
        file: File,
        entryNames: List<String>,
        maxSize: Int,
        onDecoded: (entryName: String, bitmap: Bitmap?) -> Boolean
    ) {
        when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> decodeZipImages(file, entryNames, maxSize, onDecoded)
            in sevenZipArchiveExtensions -> decodeSevenZipImages(file, entryNames, maxSize, onDecoded)
            in pdfExtensions -> decodePdfImages(file, entryNames, maxSize, onDecoded)
            else -> Unit
        }
    }

    fun openReaderSession(file: File, cacheRoot: File): ImageReaderSession {
        return if (isPdf(file)) {
            PdfReaderSession(file)
        } else {
            ReaderSession(file, cacheRoot)
        }
    }

    fun openPreparedReaderSession(directory: File, deleteOnClose: Boolean): ImageReaderSession {
        return PreparedReaderSession(directory, deleteOnClose)
    }

    fun openPreparedImageExtractor(file: File, outputDir: File): PreparedImageExtractor {
        return PreparedImageExtractor(file, outputDir)
    }

    fun preparedImageEntries(directory: File): List<String> {
        readPreparedReaderManifest(directory)?.let { return it }
        return preparedImageFiles(directory).map { it.name }
    }

    fun writePreparedReaderManifest(directory: File, entryNames: List<String>) {
        directory.mkdirs()
        val array = JSONArray()
        entryNames.forEach { array.put(it) }
        File(directory, PREPARED_READER_MANIFEST_FILE).writeText(array.toString())
    }

    fun preparedImageFile(directory: File, index: Int, entryName: String): File {
        return File(directory, preparedImageFileName(index, entryName))
    }

    fun imageFileBounds(file: File): ImageBounds? {
        return decodeFileBounds(file)
    }

    fun extractPreparedImagesToDirectory(
        file: File,
        entries: List<PreparedImageEntry>,
        outputDir: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> {
        if (entries.isEmpty()) {
            onProgress(0, 0)
            return emptyList()
        }

        outputDir.mkdirs()
        onProgress(0, entries.size)
        val extractedFiles = mutableListOf<File>()
        openPreparedImageExtractor(file, outputDir).use { extractor ->
            entries.forEachIndexed { progressIndex, entry ->
                if (!shouldContinue()) {
                    return extractedFiles
                }

                extractor.extract(entry.index, entry.entryName)?.let { extractedFiles.add(it) }
                onProgress(progressIndex + 1, entries.size)
            }
        }
        return extractedFiles
    }

    fun extractImagesToDirectory(
        file: File,
        outputDir: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> {
        val entries = imageEntries(file)
        if (entries.isEmpty()) {
            onProgress(0, 0)
            return emptyList()
        }

        outputDir.deleteRecursively()
        outputDir.mkdirs()
        writePreparedReaderManifest(outputDir, entries)
        onProgress(0, entries.size)

        val extractedFiles = when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> extractZipImagesToDirectory(
                file,
                entries,
                outputDir,
                shouldContinue,
                onProgress
            )
            in sevenZipArchiveExtensions -> extractSevenZipImagesToDirectory(
                file,
                entries,
                outputDir,
                shouldContinue,
                onProgress
            )
            else -> emptyList()
        }

        if (shouldContinue() && extractedFiles.size != entries.size) {
            outputDir.deleteRecursively()
            return emptyList()
        }

        return extractedFiles.sortedBy { it.name }
    }

    class ReaderSession internal constructor(
        private val file: File,
        cacheRoot: File
    ) : ImageReaderSession {

        private val extension = file.extension.lowercase(Locale.ROOT)
        private val zipFile = if (extension in zipArchiveExtensions) ZipFile(file) else null
        private val zipLock = Any()
        private val sevenZipLock = Any()
        private val sevenZipCacheDir = File(
            cacheRoot,
            "reader_${Integer.toHexString(file.absolutePath.hashCode())}_${file.lastModified()}_${file.length()}"
        )
        private val sevenZipEntryIndexes by lazy {
            if (extension in sevenZipArchiveExtensions) {
                readSevenZipEntryIndexes(file)
            } else {
                emptyMap()
            }
        }

        override fun readBounds(entryName: String): ImageBounds? {
            if (extension in zipArchiveExtensions) {
                return decodeZipBounds(entryName)
            }

            val bytes = imageBytes(entryName) ?: return null
            return decodeBounds(bytes)
        }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            if (extension in zipArchiveExtensions) {
                return decodeZipBitmap(
                    entryName = entryName,
                    preferredConfig = Bitmap.Config.RGB_565,
                    sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
                )?.scaleToWidthIfLarger(targetWidth)
            }

            val bytes = imageBytes(entryName) ?: return null
            return decodeBitmap(
                bytes = bytes,
                preferredConfig = Bitmap.Config.RGB_565,
                sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
            )?.scaleToWidthIfLarger(targetWidth)
        }

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? {
            if (extension in zipArchiveExtensions) {
                return decodeZipBitmap(
                    entryName = entryName,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                    sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
                )?.scaleToWidthIfLarger(targetWidth)
            }

            val bytes = imageBytes(entryName) ?: return null
            return decodeBitmap(
                bytes = bytes,
                preferredConfig = Bitmap.Config.ARGB_8888,
                sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
            )?.scaleToWidthIfLarger(targetWidth)
        }

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? {
            if (extension in zipArchiveExtensions) {
                return decodeZipBitmap(
                    entryName = entryName,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                    sampleSize = { width, height ->
                        maxOf(
                            calculateInSampleSizeForWidth(width, targetWidth),
                            calculateInSampleSizeForHeight(height, targetHeight)
                        )
                    }
                )?.scaleToFitBoundsIfLarger(targetWidth, targetHeight)
            }

            val bytes = imageBytes(entryName) ?: return null
            return decodeBitmap(
                bytes = bytes,
                preferredConfig = Bitmap.Config.ARGB_8888,
                sampleSize = { width, height ->
                    maxOf(
                        calculateInSampleSizeForWidth(width, targetWidth),
                        calculateInSampleSizeForHeight(height, targetHeight)
                    )
                }
            )?.scaleToFitBoundsIfLarger(targetWidth, targetHeight)
        }

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? {
            if (extension in zipArchiveExtensions) {
                return decodeZipRegionForWidth(entryName, sourceRect, targetWidth)
            }

            val bytes = imageBytes(entryName) ?: return null
            val decoder = runCatching {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            }.getOrNull() ?: return null

            return try {
                val boundedRect = Rect(sourceRect).apply {
                    left = left.coerceIn(0, decoder.width)
                    top = top.coerceIn(0, decoder.height)
                    right = right.coerceIn(left, decoder.width)
                    bottom = bottom.coerceIn(top, decoder.height)
                }
                if (boundedRect.width() <= 0 || boundedRect.height() <= 0) {
                    return null
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSizeForWidth(boundedRect.width(), targetWidth)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                decoder.decodeRegion(boundedRect, options)?.scaleToWidthIfLarger(targetWidth)
            } finally {
                decoder.recycle()
            }
        }

        override fun close() {
            zipFile?.close()
            if (extension in sevenZipArchiveExtensions) {
                sevenZipCacheDir.deleteRecursively()
            }
        }

        private fun imageBytes(entryName: String): ByteArray? {
            return when (extension) {
                in zipArchiveExtensions -> zipImageBytes(entryName)
                in sevenZipArchiveExtensions -> sevenZipCachedImageBytes(entryName)
                else -> null
            }
        }

        private fun zipImageBytes(entryName: String): ByteArray? {
            val zip = zipFile ?: return null
            synchronized(zipLock) {
                val entry = zip.getEntry(entryName) ?: return null
                return zip.getInputStream(entry).use { it.readBytes() }
            }
        }

        private fun decodeZipBounds(entryName: String): ImageBounds? {
            val zip = zipFile ?: return null
            synchronized(zipLock) {
                val entry = zip.getEntry(entryName) ?: return null
                return zip.getInputStream(entry).use { stream ->
                    decodeBounds(stream)
                }
            }
        }

        private fun decodeZipBitmap(
            entryName: String,
            preferredConfig: Bitmap.Config,
            sampleSize: (width: Int, height: Int) -> Int
        ): Bitmap? {
            val zip = zipFile ?: return null
            synchronized(zipLock) {
                val entry = zip.getEntry(entryName) ?: return null
                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                zip.getInputStream(entry).use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = preferredConfig
                }
                return zip.getInputStream(entry).use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            }
        }

        private fun decodeZipRegionForWidth(
            entryName: String,
            sourceRect: Rect,
            targetWidth: Int
        ): Bitmap? {
            val zip = zipFile ?: return null
            synchronized(zipLock) {
                val entry = zip.getEntry(entryName) ?: return null
                return zip.getInputStream(entry).use { stream ->
                    decodeRegionForWidth(stream, sourceRect, targetWidth)
                }
            }
        }

        private fun sevenZipCachedImageBytes(entryName: String): ByteArray? {
            synchronized(sevenZipLock) {
                val cacheFile = File(sevenZipCacheDir, cacheFileNameForEntry(entryName))
                if (cacheFile.isFile && cacheFile.length() > 0L) {
                    return cacheFile.readBytes()
                }

                val entryIndex = sevenZipEntryIndexes[entryName] ?: return null
                val bytes = withSevenZipArchive(file) { archive ->
                    extractSevenZipEntryBytes(archive, entryIndex)
                } ?: return null

                sevenZipCacheDir.mkdirs()
                runCatching {
                    cacheFile.writeBytes(bytes)
                }
                return bytes
            }
        }

        private fun cacheFileNameForEntry(entryName: String): String {
            val extension = entryName.substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .takeIf { it.isNotBlank() }
                ?: "img"
            return "${Integer.toHexString(entryName.hashCode())}_${entryName.length}.$extension"
        }
    }

    private class PdfReaderSession(
        file: File
    ) : ImageReaderSession {

        private val pdfLock = Any()
        private val parcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val pdfRenderer = PdfRenderer(parcelFileDescriptor)

        override fun isPdfSource(): Boolean = true

        override fun readBounds(entryName: String): ImageBounds? {
            val pageIndex = pdfPageIndex(entryName) ?: return null
            synchronized(pdfLock) {
                if (pageIndex !in 0 until pdfRenderer.pageCount) {
                    return null
                }

                val page = pdfRenderer.openPage(pageIndex)
                return try {
                    ImageBounds(page.width, page.height)
                } finally {
                    page.close()
                }
            }
        }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            return renderPage(entryName, targetWidth, 0, RenderMode.WIDTH)
        }

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? {
            return renderPage(entryName, targetWidth, 0, RenderMode.WIDTH)
        }

        override fun decodeImageForPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? {
            return renderPage(entryName, targetWidth, targetHeight, RenderMode.FIT_BOUNDS)
        }

        override fun decodeRegionForWidth(
            entryName: String,
            sourceRect: Rect,
            targetWidth: Int
        ): Bitmap? {
            val pageIndex = pdfPageIndex(entryName) ?: return null
            synchronized(pdfLock) {
                if (pageIndex !in 0 until pdfRenderer.pageCount) {
                    return null
                }

                val page = pdfRenderer.openPage(pageIndex)
                return try {
                    renderPdfPageRegion(page, sourceRect, targetWidth)
                } finally {
                    page.close()
                }
            }
        }

        override fun close() {
            pdfRenderer.close()
            parcelFileDescriptor.close()
        }

        private fun renderPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int,
            mode: RenderMode
        ): Bitmap? {
            val pageIndex = pdfPageIndex(entryName) ?: return null
            synchronized(pdfLock) {
                if (pageIndex !in 0 until pdfRenderer.pageCount) {
                    return null
                }

                val page = pdfRenderer.openPage(pageIndex)
                return try {
                    when (mode) {
                        RenderMode.WIDTH -> renderPdfPageForWidth(page, targetWidth)
                        RenderMode.FIT_BOUNDS -> renderPdfPageFitBounds(page, targetWidth, targetHeight)
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

    class PreparedImageExtractor internal constructor(
        private val file: File,
        private val outputDir: File
    ) : Closeable {

        private val extension = file.extension.lowercase(Locale.ROOT)
        private val zipFile = if (extension in zipArchiveExtensions) ZipFile(file) else null
        private val randomAccessFile =
            if (extension in sevenZipArchiveExtensions) RandomAccessFile(file, "r") else null
        private val sevenZipArchive =
            randomAccessFile?.let { SevenZip.openInArchive(null, RandomAccessFileInStream(it)) }
        private val sevenZipEntryIndexes by lazy {
            sevenZipArchive?.let { archive ->
                (0 until archive.numberOfItems)
                    .asSequence()
                    .filter { !archive.isFolder(it) }
                    .mapNotNull { index -> archive.entryPath(index)?.let { path -> path to index } }
                    .toMap()
            } ?: emptyMap()
        }

        @Synchronized
        fun extract(index: Int, entryName: String): File? {
            val targetFile = preparedImageFile(outputDir, index, entryName)
            if (targetFile.isFile && targetFile.length() > 0L) {
                return targetFile
            }

            return when (extension) {
                in zipArchiveExtensions -> extractZipEntry(entryName, targetFile)
                in sevenZipArchiveExtensions -> extractSevenZipEntry(entryName, targetFile)
                else -> null
            }
        }

        override fun close() {
            zipFile?.close()
            sevenZipArchive?.close()
            randomAccessFile?.close()
        }

        private fun extractZipEntry(entryName: String, targetFile: File): File? {
            val zip = zipFile ?: return null
            val entry = zip.getEntry(entryName) ?: return null
            val copied = runCatching {
                writeAtomic(targetFile) { output ->
                    zip.getInputStream(entry).use { input ->
                        input.copyTo(output)
                    }
                }
            }.getOrDefault(false)
            return targetFile.takeIf { copied && it.isFile && it.length() > 0L }
        }

        private fun extractSevenZipEntry(entryName: String, targetFile: File): File? {
            val archive = sevenZipArchive ?: return null
            val entryIndex = sevenZipEntryIndexes[entryName] ?: return null
            val copied = extractSevenZipEntryToFile(archive, entryIndex, targetFile)
            return targetFile.takeIf { copied && it.isFile && it.length() > 0L }
        }
    }

    private class PreparedReaderSession(
        private val directory: File,
        private val deleteOnClose: Boolean
    ) : ImageReaderSession {

        private val filesByEntryName = readPreparedReaderManifest(directory)
            ?.mapIndexed { index, entryName -> entryName to preparedImageFile(directory, index, entryName) }
            ?.toMap()
            ?: preparedImageFiles(directory).associateBy { it.name }

        override fun readBounds(entryName: String): ImageBounds? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return decodeFileBounds(imageFile)
        }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return decodeFileBitmap(
                file = imageFile,
                preferredConfig = Bitmap.Config.RGB_565,
                sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
            )?.scaleToWidthIfLarger(targetWidth)
        }

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return decodeFileBitmap(
                file = imageFile,
                preferredConfig = Bitmap.Config.ARGB_8888,
                sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
            )?.scaleToWidthIfLarger(targetWidth)
        }

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return decodeFileBitmap(
                file = imageFile,
                preferredConfig = Bitmap.Config.ARGB_8888,
                sampleSize = { width, height ->
                    maxOf(
                        calculateInSampleSizeForWidth(width, targetWidth),
                        calculateInSampleSizeForHeight(height, targetHeight)
                    )
                }
            )?.scaleToFitBoundsIfLarger(targetWidth, targetHeight)
        }

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return imageFile.inputStream().use { stream ->
                decodeRegionForWidth(stream, sourceRect, targetWidth)
            }
        }

        override fun close() {
            if (deleteOnClose) {
                directory.deleteRecursively()
            }
        }
    }

    private fun pdfImageEntries(file: File): List<String> {
        return runCatching {
            withPdfRenderer(file) { renderer ->
                (0 until renderer.pageCount).map(::pdfPageEntryName)
            }
        }.getOrDefault(emptyList())
    }

    private fun decodePdfImages(
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
                            renderPdfPageFitMaxSize(page, maxSize)
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

    private fun renderPdfPageFitMaxSize(file: File, entryName: String, maxSize: Int): Bitmap? {
        val pageIndex = pdfPageIndex(entryName) ?: return null
        return runCatching {
            withPdfRenderer(file) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) {
                    return@withPdfRenderer null
                }

                val page = renderer.openPage(pageIndex)
                try {
                    renderPdfPageFitMaxSize(page, maxSize)
                } finally {
                    page.close()
                }
            }
        }.getOrNull()
    }

    private fun renderPdfPageForWidth(file: File, entryName: String, targetWidth: Int): Bitmap? {
        val pageIndex = pdfPageIndex(entryName) ?: return null
        return runCatching {
            withPdfRenderer(file) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) {
                    return@withPdfRenderer null
                }

                val page = renderer.openPage(pageIndex)
                try {
                    renderPdfPageForWidth(page, targetWidth)
                } finally {
                    page.close()
                }
            }
        }.getOrNull()
    }

    private fun renderPdfPageFitMaxSize(page: PdfRenderer.Page, maxSize: Int): Bitmap? {
        if (maxSize <= 0 || page.width <= 0 || page.height <= 0) {
            return null
        }

        return renderPdfPageFitBounds(page, maxSize, maxSize)
    }

    private fun renderPdfPageForWidth(page: PdfRenderer.Page, targetWidth: Int): Bitmap? {
        if (targetWidth <= 0 || page.width <= 0 || page.height <= 0) {
            return null
        }

        val boundedTargetWidth = targetWidth.coerceAtMost(PDF_MAX_RENDER_WIDTH)
        val scale = boundedTargetWidth.toFloat() / page.width.toFloat()
        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
        return renderPdfPage(page, boundedTargetWidth.coerceAtLeast(1), targetHeight, scale)
    }

    private fun renderPdfPageFitBounds(
        page: PdfRenderer.Page,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0 || page.width <= 0 || page.height <= 0) {
            return null
        }

        val boundedTargetWidth = targetWidth.coerceAtMost(PDF_MAX_RENDER_WIDTH)
        val scale = minOf(
            boundedTargetWidth.toFloat() / page.width.toFloat(),
            targetHeight.toFloat() / page.height.toFloat()
        )
        val bitmapWidth = (page.width * scale).toInt().coerceAtLeast(1)
        val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
        return renderPdfPage(page, bitmapWidth, bitmapHeight, scale)
    }

    private fun renderPdfPageRegion(
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

    private fun renderPdfPage(
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

    private fun zipImageEntries(file: File): List<String> {
        ZipFile(file).use { zipFile ->
            return zipFile.entries().asSequence()
                .filter { !it.isDirectory && it.isImageEntry() }
                .map { it.name }
                .sortedWith(naturalEntryNameComparator)
                .toList()
        }
    }

    private fun zipFileEntries(file: File): List<String> {
        ZipFile(file).use { zipFile ->
            return zipFile.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .sortedWith(naturalEntryNameComparator)
                .toList()
        }
    }

    private fun deleteZipEntry(file: File, entryName: String): Boolean {
        val parent = file.parentFile ?: return false
        val tempFile = File(parent, "${file.name}.delete_${System.currentTimeMillis()}.tmp")
        val backupFile = File(parent, "${file.name}.delete_${System.currentTimeMillis()}.bak")
        tempFile.delete()
        backupFile.delete()

        val wroteReplacement = runCatching {
            var foundEntry = false
            ZipFile(file).use { zipFile ->
                ZipOutputStream(tempFile.outputStream().buffered()).use { zipOutput ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val sourceEntry = entries.nextElement()
                        if (sourceEntry.name == entryName) {
                            foundEntry = true
                            continue
                        }

                        if (!sourceEntry.isDirectory && sourceEntry.isImageEntry()) {
                            val stored = writeStoredZipEntry(zipFile, sourceEntry, zipOutput)
                            if (stored) {
                                continue
                            }
                        }

                        val targetEntry = sourceEntry.copyForZipOutput()
                        zipOutput.putNextEntry(targetEntry)
                        if (!sourceEntry.isDirectory) {
                            zipFile.getInputStream(sourceEntry).use { input ->
                                input.copyTo(zipOutput)
                            }
                        }
                        zipOutput.closeEntry()
                    }
                }
            }

            foundEntry && tempFile.isFile && tempFile.length() > 0L
        }.getOrDefault(false)

        if (!wroteReplacement) {
            tempFile.delete()
            return false
        }

        return replaceOriginalFileWithTemp(file, tempFile, backupFile)
    }

    private fun rebuildSevenZipReadableArchiveWithoutEntry(file: File, entryName: String): Boolean {
        val parent = file.parentFile ?: return false
        val timestamp = System.currentTimeMillis()
        val tempFile = File(parent, "${file.name}.delete_${timestamp}.tmp")
        val backupFile = File(parent, "${file.name}.delete_${timestamp}.bak")
        val workDir = File(parent, "${file.name}.delete_${timestamp}_work")
        tempFile.delete()
        backupFile.delete()
        workDir.deleteRecursively()

        val wroteReplacement = try {
            runCatching {
                var foundEntry = false
                withSevenZipArchive(file) { archive ->
                    ZipOutputStream(tempFile.outputStream().buffered()).use { zipOutput ->
                        for (index in 0 until archive.numberOfItems) {
                            val path = archive.entryPath(index) ?: continue
                            if (path == entryName) {
                                foundEntry = true
                                continue
                            }

                            val isFolder = archive.isFolder(index)
                            val lastModified =
                                archive.getProperty(index, PropID.LAST_MODIFICATION_TIME) as? java.util.Date
                            val zipEntry = ZipEntry(path.toZipEntryPath(isFolder)).apply {
                                lastModified?.let { time = it.time }
                            }

                            if (!isFolder && path.isImageEntryName()) {
                                val stored = writeStoredSevenZipEntry(
                                    archive = archive,
                                    entryIndex = index,
                                    entryPath = path,
                                    lastModified = lastModified,
                                    zipOutput = zipOutput,
                                    workDir = workDir
                                )
                                if (stored) {
                                    continue
                                }
                            }

                            zipOutput.putNextEntry(zipEntry)
                            if (!isFolder) {
                                val extracted = extractSevenZipEntryToStream(archive, index, zipOutput)
                                if (!extracted) {
                                    throw IOException("Failed to extract archive entry: $path")
                                }
                            }
                            zipOutput.closeEntry()
                        }
                    }
                }

                foundEntry && tempFile.isFile && tempFile.length() > 0L
            }.getOrDefault(false)
        } finally {
            workDir.deleteRecursively()
        }

        if (!wroteReplacement) {
            tempFile.delete()
            return false
        }

        return replaceOriginalFileWithTemp(file, tempFile, backupFile)
    }

    private fun replaceOriginalFileWithTemp(
        originalFile: File,
        tempFile: File,
        backupFile: File
    ): Boolean {
        if (!originalFile.renameTo(backupFile)) {
            tempFile.delete()
            return false
        }

        if (tempFile.renameTo(originalFile)) {
            backupFile.delete()
            return true
        }

        tempFile.delete()
        backupFile.renameTo(originalFile)
        return false
    }

    private fun writeStoredZipEntry(
        zipFile: ZipFile,
        sourceEntry: ZipEntry,
        zipOutput: ZipOutputStream
    ): Boolean {
        if (sourceEntry.size < 0L || sourceEntry.crc < 0L) {
            return false
        }

        val targetEntry = sourceEntry.copyForZipOutput().apply {
            method = ZipEntry.STORED
            size = sourceEntry.size
            compressedSize = sourceEntry.size
            crc = sourceEntry.crc
        }

        zipOutput.putNextEntry(targetEntry)
        zipFile.getInputStream(sourceEntry).use { input ->
            input.copyTo(zipOutput)
        }
        zipOutput.closeEntry()
        return true
    }

    private fun writeStoredSevenZipEntry(
        archive: IInArchive,
        entryIndex: Int,
        entryPath: String,
        lastModified: java.util.Date?,
        zipOutput: ZipOutputStream,
        workDir: File
    ): Boolean {
        workDir.mkdirs()
        val extractedFile = File(workDir, String.format(Locale.ROOT, "%06d.entry", entryIndex))
        extractedFile.delete()

        val extracted = runCatching {
            extractedFile.outputStream().buffered().use { output ->
                extractSevenZipEntryToStream(archive, entryIndex, output)
            }
        }.getOrDefault(false)
        if (!extracted || !extractedFile.isFile) {
            extractedFile.delete()
            return false
        }

        val entrySize = extractedFile.length()
        val entry = ZipEntry(entryPath).apply {
            method = ZipEntry.STORED
            size = entrySize
            compressedSize = entrySize
            crc = crc32(extractedFile)
            lastModified?.let { time = it.time }
        }

        zipOutput.putNextEntry(entry)
        extractedFile.inputStream().buffered().use { input ->
            input.copyTo(zipOutput)
        }
        zipOutput.closeEntry()
        extractedFile.delete()
        return true
    }

    private fun sevenZipImageEntries(file: File): List<String> {
        return withSevenZipArchive(file) { archive ->
            (0 until archive.numberOfItems)
                .asSequence()
                .filter { !archive.isFolder(it) }
                .mapNotNull { archive.entryPath(it) }
                .filter { it.isImageEntryName() }
                .sortedWith(naturalEntryNameComparator)
                .toList()
        }
    }

    private fun sevenZipFileEntries(file: File): List<String> {
        return withSevenZipArchive(file) { archive ->
            (0 until archive.numberOfItems)
                .asSequence()
                .filter { !archive.isFolder(it) }
                .mapNotNull { archive.entryPath(it) }
                .sortedWith(naturalEntryNameComparator)
                .toList()
        }
    }

    private fun readSevenZipEntryIndexes(file: File): Map<String, Int> {
        return withSevenZipArchive(file) { archive ->
            (0 until archive.numberOfItems)
                .asSequence()
                .filter { !archive.isFolder(it) }
                .mapNotNull { index -> archive.entryPath(index)?.let { path -> path to index } }
                .toMap()
        }
    }

    private fun imageBytes(file: File, entryName: String): ByteArray? {
        return when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> zipImageBytes(file, entryName)
            in sevenZipArchiveExtensions -> sevenZipImageBytes(file, entryName)
            else -> null
        }
    }

    private fun zipImageBytes(file: File, entryName: String): ByteArray? {
        ZipFile(file).use { zipFile ->
            val entry = zipFile.getEntry(entryName) ?: return null
            return zipFile.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun sevenZipImageBytes(file: File, entryName: String): ByteArray? {
        return withSevenZipArchive(file) { archive ->
            val entryIndex = (0 until archive.numberOfItems).firstOrNull { index ->
                !archive.isFolder(index) && archive.entryPath(index) == entryName
            } ?: return@withSevenZipArchive null

            extractSevenZipEntryBytes(archive, entryIndex)
        }
    }

    private fun decodeZipImages(
        file: File,
        entryNames: List<String>,
        maxSize: Int,
        onDecoded: (entryName: String, bitmap: Bitmap?) -> Boolean
    ) {
        if (entryNames.isEmpty()) {
            return
        }

        val workerCount = minOf(ZIP_PREVIEW_DECODE_THREAD_COUNT, entryNames.size)
        val pendingEntries = ConcurrentLinkedQueue(entryNames)
        val keepDecoding = java.util.concurrent.atomic.AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val futures = (0 until workerCount).map {
                executor.submit {
                    ZipFile(file).use { zipFile ->
                        while (keepDecoding.get()) {
                            val entryName = pendingEntries.poll() ?: break
                            val bitmap = runCatching {
                                val entry = zipFile.getEntry(entryName) ?: return@runCatching null
                                val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                                decodePreviewBitmap(bytes, maxSize)
                            }.getOrNull()

                            if (keepDecoding.get() && !onDecoded(entryName, bitmap)) {
                                keepDecoding.set(false)
                            }
                        }
                    }
                }
            }

            futures.forEach { future ->
                runCatching { future.get() }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun decodeSevenZipImages(
        file: File,
        entryNames: List<String>,
        maxSize: Int,
        onDecoded: (entryName: String, bitmap: Bitmap?) -> Boolean
    ) {
        withSevenZipArchive(file) { archive ->
            val entryIndexesByPath = (0 until archive.numberOfItems)
                .asSequence()
                .filter { !archive.isFolder(it) }
                .mapNotNull { index -> archive.entryPath(index)?.let { path -> path to index } }
                .toMap()

            for (entryName in entryNames) {
                val bitmap = runCatching {
                    val entryIndex = entryIndexesByPath[entryName] ?: return@runCatching null
                    val bytes = extractSevenZipEntryBytes(archive, entryIndex) ?: return@runCatching null
                    decodePreviewBitmap(bytes, maxSize)
                }.getOrNull()

                if (!onDecoded(entryName, bitmap)) {
                    return@withSevenZipArchive
                }
            }
        }
    }

    private fun extractZipImagesToDirectory(
        file: File,
        entryNames: List<String>,
        outputDir: File,
        shouldContinue: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> {
        val extractedFiles = mutableListOf<File>()
        ZipFile(file).use { zipFile ->
            entryNames.forEachIndexed { index, entryName ->
                if (!shouldContinue()) {
                    return extractedFiles
                }

                val targetFile = preparedImageFile(outputDir, index, entryName)
                val entry = zipFile.getEntry(entryName)
                if (entry != null) {
                    val copied = runCatching {
                        writeAtomic(targetFile) { output ->
                            zipFile.getInputStream(entry).use { input ->
                                input.copyTo(output)
                            }
                        }
                    }.getOrDefault(false)
                    if (copied && targetFile.isFile && targetFile.length() > 0L) {
                        extractedFiles.add(targetFile)
                    } else {
                        targetFile.delete()
                    }
                }
                onProgress(index + 1, entryNames.size)
            }
        }
        return extractedFiles
    }

    private fun extractSevenZipImagesToDirectory(
        file: File,
        entryNames: List<String>,
        outputDir: File,
        shouldContinue: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> {
        return withSevenZipArchive(file) { archive ->
            val entryIndexesByPath = (0 until archive.numberOfItems)
                .asSequence()
                .filter { !archive.isFolder(it) }
                .mapNotNull { index -> archive.entryPath(index)?.let { path -> path to index } }
                .toMap()
            val extractedFiles = mutableListOf<File>()

            entryNames.forEachIndexed { index, entryName ->
                if (!shouldContinue()) {
                    return@withSevenZipArchive extractedFiles
                }

                val targetFile = preparedImageFile(outputDir, index, entryName)
                val entryIndex = entryIndexesByPath[entryName]
                if (entryIndex != null && extractSevenZipEntryToFile(archive, entryIndex, targetFile)) {
                    extractedFiles.add(targetFile)
                } else {
                    targetFile.delete()
                }
                onProgress(index + 1, entryNames.size)
            }

            extractedFiles
        }
    }

    private fun extractSevenZipEntryToFile(
        archive: IInArchive,
        entryIndex: Int,
        targetFile: File
    ): Boolean {
        var extractResult = ExtractOperationResult.OK
        val wroteFile = writeAtomic(targetFile) { output ->
            archive.extract(intArrayOf(entryIndex), false, object : IArchiveExtractCallback {
                override fun setTotal(total: Long) = Unit

                override fun setCompleted(completeValue: Long) = Unit

                override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                    if (index != entryIndex || extractAskMode != ExtractAskMode.EXTRACT) {
                        return null
                    }

                    return object : ISequentialOutStream {
                        override fun write(data: ByteArray): Int {
                            output.write(data)
                            return data.size
                        }
                    }
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    extractResult = extractOperationResult
                }
            })
        }

        val success = wroteFile &&
            extractResult == ExtractOperationResult.OK &&
            targetFile.isFile &&
            targetFile.length() > 0L
        if (!success) {
            targetFile.delete()
        }
        return success
    }

    private fun extractSevenZipEntryToStream(
        archive: IInArchive,
        entryIndex: Int,
        output: OutputStream
    ): Boolean {
        var extractResult = ExtractOperationResult.OK
        archive.extract(intArrayOf(entryIndex), false, object : IArchiveExtractCallback {
            override fun setTotal(total: Long) = Unit

            override fun setCompleted(completeValue: Long) = Unit

            override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                if (index != entryIndex || extractAskMode != ExtractAskMode.EXTRACT) {
                    return null
                }

                return object : ISequentialOutStream {
                    override fun write(data: ByteArray): Int {
                        output.write(data)
                        return data.size
                    }
                }
            }

            override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

            override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                extractResult = extractOperationResult
            }
        })

        return extractResult == ExtractOperationResult.OK
    }

    private fun extractSevenZipEntryBytes(archive: IInArchive, entryIndex: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        var extractResult = ExtractOperationResult.OK
        archive.extract(intArrayOf(entryIndex), false, object : IArchiveExtractCallback {
            override fun setTotal(total: Long) = Unit

            override fun setCompleted(completeValue: Long) = Unit

            override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                if (index != entryIndex || extractAskMode != ExtractAskMode.EXTRACT) {
                    return null
                }

                return object : ISequentialOutStream {
                    override fun write(data: ByteArray): Int {
                        output.write(data)
                        return data.size
                    }
                }
            }

            override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

            override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                extractResult = extractOperationResult
            }
        })

        return if (extractResult == ExtractOperationResult.OK) {
            output.toByteArray()
        } else {
            null
        }
    }

    private fun decodePreviewBitmap(bytes: ByteArray, maxSize: Int): Bitmap? {
        return decodeBitmap(
            bytes = bytes,
            preferredConfig = Bitmap.Config.RGB_565,
            sampleSize = { width, height -> calculateInSampleSize(width, height, maxSize) }
        )?.scaleToFitMaxSize(maxSize)
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        preferredConfig: Bitmap.Config,
        sampleSize: (width: Int, height: Int) -> Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = preferredConfig
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun decodeFileBitmap(
        file: File,
        preferredConfig: Bitmap.Config,
        sampleSize: (width: Int, height: Int) -> Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = preferredConfig
        }

        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }

    private fun decodeBounds(bytes: ByteArray): ImageBounds? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        return ImageBounds(bounds.outWidth, bounds.outHeight)
    }

    private fun decodeBounds(stream: InputStream): ImageBounds? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(stream, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        return ImageBounds(bounds.outWidth, bounds.outHeight)
    }

    private fun decodeFileBounds(file: File): ImageBounds? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        return ImageBounds(bounds.outWidth, bounds.outHeight)
    }

    private fun decodeRegionForWidth(
        stream: InputStream,
        sourceRect: Rect,
        targetWidth: Int
    ): Bitmap? {
        val decoder = runCatching {
            BitmapRegionDecoder.newInstance(stream, false)
        }.getOrNull() ?: return null

        return try {
            val boundedRect = Rect(sourceRect).apply {
                left = left.coerceIn(0, decoder.width)
                top = top.coerceIn(0, decoder.height)
                right = right.coerceIn(left, decoder.width)
                bottom = bottom.coerceIn(top, decoder.height)
            }
            if (boundedRect.width() <= 0 || boundedRect.height() <= 0) {
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSizeForWidth(boundedRect.width(), targetWidth)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoder.decodeRegion(boundedRect, options)?.scaleToWidthIfLarger(targetWidth)
        } finally {
            decoder.recycle()
        }
    }

    private fun ZipEntry.isImageEntry(): Boolean {
        return name.isImageEntryName()
    }

    private fun ZipEntry.copyForZipOutput(): ZipEntry {
        return ZipEntry(name).also { target ->
            target.comment = comment
            target.extra = extra
            if (time >= 0L) {
                target.time = time
            }

            if (method == ZipEntry.STORED && size >= 0L && crc >= 0L) {
                target.method = ZipEntry.STORED
                target.size = size
                target.compressedSize = compressedSize.takeIf { it >= 0L } ?: size
                target.crc = crc
            } else {
                target.method = ZipEntry.DEFLATED
            }
        }
    }

    private fun String.isImageEntryName(): Boolean {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in imageExtensions
    }

    private fun String.isPdfPageEntryName(): Boolean {
        return pdfPageIndex(this) != null
    }

    private fun String.toZipEntryPath(isFolder: Boolean): String {
        return if (isFolder && !endsWith("/")) {
            "$this/"
        } else {
            this
        }
    }

    private fun crc32(file: File): Long {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun IInArchive.isFolder(index: Int): Boolean {
        return getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
    }

    private fun IInArchive.entryPath(index: Int): String? {
        return getProperty(index, PropID.PATH) as? String
    }

    private fun readPreparedReaderManifest(directory: File): List<String>? {
        val manifestFile = File(directory, PREPARED_READER_MANIFEST_FILE)
        if (!manifestFile.isFile) {
            return null
        }

        return runCatching {
            val array = JSONArray(manifestFile.readText())
            (0 until array.length()).map { index -> array.getString(index) }
        }.getOrNull()
    }

    private fun preparedImageFiles(directory: File): List<File> {
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.isImageEntryName() }
            ?.sortedBy { it.name }
            ?.toList()
            ?: emptyList()
    }

    private fun preparedImageFileName(index: Int, entryName: String): String {
        val extension = entryName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in imageExtensions }
            ?: "img"
        val hash = Integer.toHexString(entryName.hashCode())
        return String.format(Locale.ROOT, "%06d_%s.%s", index, hash, extension)
    }

    private fun writeAtomic(targetFile: File, writeBlock: (OutputStream) -> Unit): Boolean {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        tempFile.delete()
        val wrote = runCatching {
            tempFile.outputStream().use { output ->
                writeBlock(output)
            }
            tempFile.isFile && tempFile.length() > 0L
        }.getOrDefault(false)

        if (!wrote) {
            tempFile.delete()
            return false
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }

        val renamed = tempFile.renameTo(targetFile)
        if (!renamed) {
            tempFile.delete()
        }
        return renamed
    }

    private fun <T> withSevenZipArchive(file: File, block: (IInArchive) -> T): T {
        val randomAccessFile = RandomAccessFile(file, "r")
        var archive: IInArchive? = null

        try {
            archive = SevenZip.openInArchive(null, RandomAccessFileInStream(randomAccessFile))
            return block(archive)
        } finally {
            archive?.close()
            randomAccessFile.close()
        }
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

    private fun pdfPageEntryName(index: Int): String {
        return String.format(Locale.ROOT, "%s%06d", PDF_PAGE_ENTRY_PREFIX, index)
    }

    private fun pdfPageIndex(entryName: String): Int? {
        if (!entryName.startsWith(PDF_PAGE_ENTRY_PREFIX)) {
            return null
        }

        return entryName
            .removePrefix(PDF_PAGE_ENTRY_PREFIX)
            .toIntOrNull()
            ?.takeIf { it >= 0 }
    }

    private fun compareNaturalEntryNames(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]

            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftEnd = findNumberEnd(left, leftIndex)
                val rightEnd = findNumberEnd(right, rightIndex)
                val numberComparison = compareNumberRuns(left, leftIndex, leftEnd, right, rightIndex, rightEnd)
                if (numberComparison != 0) {
                    return numberComparison
                }
                leftIndex = leftEnd
                rightIndex = rightEnd
                continue
            }

            val charComparison = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
            if (charComparison != 0) {
                return charComparison
            }

            leftIndex++
            rightIndex++
        }

        if (leftIndex != left.length || rightIndex != right.length) {
            return (left.length - leftIndex).compareTo(right.length - rightIndex)
        }

        return left.compareTo(right)
    }

    private fun findNumberEnd(value: String, startIndex: Int): Int {
        var index = startIndex
        while (index < value.length && value[index].isDigit()) {
            index++
        }
        return index
    }

    private fun compareNumberRuns(
        left: String,
        leftStart: Int,
        leftEnd: Int,
        right: String,
        rightStart: Int,
        rightEnd: Int
    ): Int {
        val leftSignificantStart = findSignificantNumberStart(left, leftStart, leftEnd)
        val rightSignificantStart = findSignificantNumberStart(right, rightStart, rightEnd)
        val leftSignificantLength = leftEnd - leftSignificantStart
        val rightSignificantLength = rightEnd - rightSignificantStart

        if (leftSignificantLength != rightSignificantLength) {
            return leftSignificantLength.compareTo(rightSignificantLength)
        }

        for (offset in 0 until leftSignificantLength) {
            val digitComparison = left[leftSignificantStart + offset].compareTo(right[rightSignificantStart + offset])
            if (digitComparison != 0) {
                return digitComparison
            }
        }

        return 0
    }

    private fun findSignificantNumberStart(value: String, startIndex: Int, endIndex: Int): Int {
        var index = startIndex
        while (index < endIndex - 1 && value[index] == '0') {
            index++
        }
        return index
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        if (width <= 0 || height <= 0) {
            return 1
        }

        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height

        while (sampledWidth / 2 >= maxSize || sampledHeight / 2 >= maxSize) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }

        return sampleSize
    }

    private fun calculateInSampleSizeForWidth(width: Int, targetWidth: Int): Int {
        if (width <= 0 || targetWidth <= 0) {
            return 1
        }

        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun calculateInSampleSizeForHeight(height: Int, targetHeight: Int): Int {
        if (height <= 0 || targetHeight <= 0) {
            return 1
        }

        var sampleSize = 1
        while (height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun Bitmap.scaleToFitMaxSize(maxSize: Int): Bitmap {
        if (maxSize <= 0 || width <= 0 || height <= 0) {
            return this
        }

        val maxDimension = maxOf(width, height)
        if (maxDimension <= maxSize) {
            return this
        }

        val scale = maxSize.toFloat() / maxDimension.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return createScaledBitmapSafely(targetWidth, targetHeight)
    }

    private fun Bitmap.scaleToWidthIfLarger(targetWidth: Int): Bitmap {
        if (targetWidth <= 0 || width <= 0 || height <= 0 || width <= targetWidth) {
            return this
        }

        val targetHeight = (targetWidth.toFloat() / width.toFloat() * height.toFloat())
            .toInt()
            .coerceAtLeast(1)
        return createScaledBitmapSafely(targetWidth, targetHeight)
    }

    private fun Bitmap.scaleToFitBoundsIfLarger(targetWidth: Int, targetHeight: Int): Bitmap {
        if (targetWidth <= 0 || targetHeight <= 0 || width <= 0 || height <= 0) {
            return this
        }

        if (width <= targetWidth && height <= targetHeight) {
            return this
        }

        val scale = minOf(
            targetWidth.toFloat() / width.toFloat(),
            targetHeight.toFloat() / height.toFloat()
        )
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return createScaledBitmapSafely(scaledWidth, scaledHeight)
    }

    private fun Bitmap.createScaledBitmapSafely(targetWidth: Int, targetHeight: Int): Bitmap {
        if (targetWidth == width && targetHeight == height) {
            return this
        }

        val scaledBitmap = runCatching {
            Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        }.getOrNull() ?: return this
        if (scaledBitmap != this) {
            recycle()
        }
        return scaledBitmap
    }
}
