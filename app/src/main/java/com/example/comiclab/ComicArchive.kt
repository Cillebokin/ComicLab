package com.example.comiclab

import android.graphics.Bitmap
import com.example.comiclab.core.archive.ArchiveImageDecoder
import com.example.comiclab.core.archive.ArchiveImageDecoder.decodeZipImages
import com.example.comiclab.core.archive.ArchiveImageDecoder.decodeFileBitmap
import com.example.comiclab.core.archive.ArchiveImageDecoder.calculateInSampleSize
import com.example.comiclab.core.archive.ArchiveImageDecoder.calculateInSampleSizeForWidth
import com.example.comiclab.core.archive.ArchiveImageDecoder.scaleToFitMaxSize
import com.example.comiclab.core.archive.ArchiveImageDecoder.scaleToWidthIfLarger
import com.example.comiclab.core.archive.ArchiveFormatDetector
import com.example.comiclab.core.archive.ArchiveEntryDeleter
import com.example.comiclab.core.archive.ArchiveEntryLister
import com.example.comiclab.core.archive.ArchiveReaderSession
import com.example.comiclab.core.archive.ArchiveReaderSessionBounds
import com.example.comiclab.core.archive.ArchiveReaderSessionFactory
import com.example.comiclab.core.archive.PreparedArchiveExtractor
import com.example.comiclab.core.archive.PdfPageRenderer
import com.example.comiclab.core.archive.SharedArchiveReaderSession
import android.graphics.Rect
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import com.example.comiclab.core.archive.cache.ArchiveEntryFileCache
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

object ComicArchive {

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

    private class ArchiveSessionFacadeAdapter(
        private val delegate: ArchiveReaderSession
    ) : ImageReaderSession {
        override fun isPdfSource(): Boolean = delegate.isPdfSource()

        override fun readBounds(entryName: String): ImageBounds? =
            delegate.readBounds(entryName)?.let { ImageBounds(it.width, it.height) }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? =
            delegate.decodePreviewForWidth(entryName, targetWidth)

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? =
            delegate.decodeImageForWidth(entryName, targetWidth)

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? =
            delegate.decodeImageForPage(entryName, targetWidth, targetHeight)

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? =
            delegate.decodeRegionForWidth(entryName, sourceRect, targetWidth)

        override fun close() = delegate.close()
    }

    private class ImageSessionCoreAdapter(
        private val delegate: ImageReaderSession
    ) : ArchiveReaderSession {
        override fun isPdfSource(): Boolean = delegate.isPdfSource()

        override fun readBounds(entryName: String): ArchiveReaderSessionBounds? =
            delegate.readBounds(entryName)?.let { ArchiveReaderSessionBounds(it.width, it.height) }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? =
            delegate.decodePreviewForWidth(entryName, targetWidth)

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? =
            delegate.decodeImageForWidth(entryName, targetWidth)

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? =
            delegate.decodeImageForPage(entryName, targetWidth, targetHeight)

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? =
            delegate.decodeRegionForWidth(entryName, sourceRect, targetWidth)

        override fun close() = delegate.close()
    }

    class SharedImageReaderSession private constructor(
        private val delegate: SharedArchiveReaderSession
    ) {
        @Synchronized
        fun acquire(): ImageReaderSession {
            return ArchiveSessionFacadeAdapter(delegate.acquire())
        }

        companion object {
            fun wrap(session: ImageReaderSession): SharedImageReaderSession {
                return SharedImageReaderSession(
                    SharedArchiveReaderSession.wrap(ImageSessionCoreAdapter(session))
                )
            }
        }
    }

    data class PreparedImageEntry(
        val index: Int,
        val entryName: String
    )

    private val zipArchiveExtensions get() = ArchiveFormatDetector.zipArchiveExtensions
    private val sevenZipArchiveExtensions get() = ArchiveFormatDetector.sevenZipArchiveExtensions
    private val pdfExtensions get() = ArchiveFormatDetector.pdfExtensions

    fun isArchive(file: File): Boolean = ArchiveFormatDetector.isArchive(file)

    fun isSupportedArchive(file: File): Boolean = ArchiveFormatDetector.isSupportedArchive(file)

    fun isPdf(file: File): Boolean = ArchiveFormatDetector.isPdf(file)

    fun imageEntries(file: File): List<String> =
        ArchiveEntryLister.imageEntries(file, ::sevenZipImageEntries, PdfPageRenderer::imageEntries)

    fun firstImageEntryIfFirstFileIsImage(file: File): String? =
        ArchiveEntryLister.firstImageEntryIfFirstFileIsImage(
            file, ::sevenZipFileEntries, PdfPageRenderer::imageEntries
        ) { PdfPageRenderer.isPageEntry(it) }

    fun canDeleteEntry(file: File): Boolean = ArchiveEntryDeleter.canDeleteEntry(file)

    fun deleteEntry(file: File, entryName: String): Boolean = ArchiveEntryDeleter.deleteEntry(
        file = file,
        entryName = entryName,
        withSevenZipArchive = { archiveFile, block -> withSevenZipArchive(archiveFile, block) },
        extractSevenZipEntryToStream = ::extractSevenZipEntryToStream
    )

    fun decodeImage(file: File, entryName: String, maxSize: Int, cacheRoot: File): Bitmap? {
        if (isPdf(file)) {
            return PdfPageRenderer.renderPageFitMaxSize(file, entryName, maxSize)
        }

        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension in sevenZipArchiveExtensions) {
            return withSevenZipEntryFile(file, entryName, cacheRoot) { imageFile ->
                decodeFileBitmap(
                    file = imageFile,
                    preferredConfig = Bitmap.Config.RGB_565,
                    sampleSize = { width, height -> calculateInSampleSize(width, height, maxSize) }
                )?.scaleToFitMaxSize(maxSize)
            }
        }
        if (extension !in zipArchiveExtensions) return null

        return ArchiveImageDecoder.decodeImage(file, entryName, maxSize)
    }

    fun decodeImageForWidth(file: File, entryName: String, targetWidth: Int, cacheRoot: File): Bitmap? {
        if (isPdf(file)) {
            return PdfPageRenderer.renderPageForWidth(file, entryName, targetWidth)
        }

        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension in sevenZipArchiveExtensions) {
            return withSevenZipEntryFile(file, entryName, cacheRoot) { imageFile ->
                decodeFileBitmap(
                    file = imageFile,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                    sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
                )?.scaleToWidthIfLarger(targetWidth)
            }
        }
        if (extension !in zipArchiveExtensions) return null

        return ArchiveImageDecoder.decodeImageForWidth(file, entryName, targetWidth)
    }

    fun decodeImages(
        file: File,
        entryNames: List<String>,
        maxSize: Int,
        cacheRoot: File,
        onDecoded: (entryName: String, bitmap: Bitmap?) -> Boolean
    ) {
        when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> decodeZipImages(file, entryNames, maxSize, onDecoded)
            in sevenZipArchiveExtensions -> decodeSevenZipImages(file, entryNames, maxSize, cacheRoot, onDecoded)
            in pdfExtensions -> PdfPageRenderer.decodeImages(file, entryNames, maxSize, onDecoded)
            else -> Unit
        }
    }

    fun extractImageEntryToFile(file: File, entryName: String, targetFile: File): Boolean =
        PreparedArchiveExtractor.extractImageEntryToFile(file, entryName, targetFile)

    fun openReaderSession(file: File, cacheRoot: File): ImageReaderSession {
        return if (isPdf(file)) {
            ArchiveSessionFacadeAdapter(PdfPageRenderer.openReaderSession(file))
        } else {
            ReaderSession(file, cacheRoot)
        }
    }

    fun openPreparedReaderSession(directory: File, deleteOnClose: Boolean): ImageReaderSession =
        ArchiveSessionFacadeAdapter(PreparedArchiveExtractor.openPreparedReaderSession(directory, deleteOnClose))

    fun openPreparedImageExtractor(file: File, outputDir: File): PreparedImageExtractor =
        PreparedImageExtractor(file, outputDir)

    fun preparedImageEntries(directory: File): List<String> =
        PreparedArchiveExtractor.preparedImageEntries(directory)

    fun writePreparedReaderManifest(directory: File, entryNames: List<String>) =
        PreparedArchiveExtractor.writePreparedReaderManifest(directory, entryNames)

    fun preparedImageFile(directory: File, index: Int, entryName: String): File =
        PreparedArchiveExtractor.preparedImageFile(directory, index, entryName)

    fun imageFileBounds(file: File): ImageBounds? =
        PreparedArchiveExtractor.imageFileBounds(file)?.let { ImageBounds(it.width, it.height) }

    fun extractPreparedImagesToDirectory(
        file: File,
        entries: List<PreparedImageEntry>,
        outputDir: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> = PreparedArchiveExtractor.extractPreparedImagesToDirectory(
        file, entries.map { PreparedArchiveExtractor.PreparedImageEntry(it.index, it.entryName) },
        outputDir, shouldContinue, onProgress
    )

    fun extractImagesToDirectory(
        file: File,
        outputDir: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> = PreparedArchiveExtractor.extractImagesToDirectory(
        file, imageEntries(file), outputDir, shouldContinue, onProgress
    )

    class ReaderSession internal constructor(
        file: File,
        cacheRoot: File
    ) : ImageReaderSession by ArchiveSessionFacadeAdapter(
        ArchiveReaderSessionFactory.open(file, cacheRoot) { archive, entryIndex, targetFile ->
            extractSevenZipEntryToFile(archive, entryIndex, targetFile)
        }
    )

    class PreparedImageExtractor internal constructor(
        private val file: File,
        private val outputDir: File
    ) : Closeable {
        private val delegate = PreparedArchiveExtractor.openPreparedImageExtractor(file, outputDir)

        @Synchronized
        fun extract(index: Int, entryName: String): File? = delegate.extract(index, entryName)

        override fun close() = delegate.close()
    }

    private fun sevenZipImageEntries(file: File): List<String> {
        return withSevenZipArchive(file) { archive ->
            ArchiveEntryLister.imageEntryNames(
                (0 until archive.numberOfItems)
                    .asSequence()
                    .filter { !archive.isFolder(it) }
                    .mapNotNull { archive.entryPath(it) }
            )
        }
    }

    private fun sevenZipFileEntries(file: File): List<String> {
        return withSevenZipArchive(file) { archive ->
            ArchiveEntryLister.fileEntryNames(
                (0 until archive.numberOfItems)
                    .asSequence()
                    .filter { !archive.isFolder(it) }
                    .mapNotNull { archive.entryPath(it) }
            )
        }
    }

    private fun decodeSevenZipImages(
        file: File,
        entryNames: List<String>,
        maxSize: Int,
        cacheRoot: File,
        onDecoded: (entryName: String, bitmap: Bitmap?) -> Boolean
    ) {
        val cache = ArchiveEntryFileCache(cacheRoot, maxBytes = 0L)
        try {
            withSevenZipArchive(file) { archive ->
                ArchiveImageDecoder.decodeSevenZipImages(
                    archive, cache, entryNames, maxSize,
                    extractEntry = { index, target -> extractSevenZipEntryToFile(archive, index, target) },
                    onDecoded = onDecoded
                )
            }
        } finally {
            cache.close()
        }
    }

    private fun extractSevenZipEntryToFile(
        archive: IInArchive,
        entryIndex: Int,
        targetFile: File
    ): Boolean = PreparedArchiveExtractor.extractSevenZipEntryToFile(archive, entryIndex, targetFile)

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

    internal fun decodeImageFromStreamFactory(
        openInputStream: () -> InputStream,
        preferredConfig: Bitmap.Config,
        sampleSize: (width: Int, height: Int) -> Int
    ): Bitmap? = ArchiveImageDecoder.decodeImageFromStreamFactory(openInputStream, preferredConfig, sampleSize)

    private fun <T> withSevenZipEntryFile(
        file: File,
        entryName: String,
        cacheRoot: File,
        use: (File) -> T?
    ): T? {
        val cache = ArchiveEntryFileCache(cacheRoot, maxBytes = 0L)
        return try {
            withSevenZipArchive(file) { archive ->
                val entryIndex = (0 until archive.numberOfItems).firstOrNull { index ->
                    !archive.isFolder(index) && archive.entryPath(index) == entryName
                } ?: return@withSevenZipArchive null
                cache.withEntry(
                    entryName = entryName,
                    extract = { target -> extractSevenZipEntryToFile(archive, entryIndex, target) },
                    use = use
                )
            }
        } finally {
            cache.close()
        }
    }

    private fun IInArchive.isFolder(index: Int): Boolean {
        return getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
    }

    private fun IInArchive.entryPath(index: Int): String? {
        return getProperty(index, PropID.PATH) as? String
    }

    private fun <T> withSevenZipArchive(file: File, block: (IInArchive) -> T): T {
        return ArchiveReaderSessionFactory.withSevenZipArchive(file, block)
    }

}
