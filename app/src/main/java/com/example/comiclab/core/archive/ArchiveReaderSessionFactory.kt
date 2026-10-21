package com.example.comiclab.core.archive

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.comiclab.core.archive.cache.ArchiveEntryFileCache
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.ZipFile
import com.example.comiclab.core.archive.ArchiveImageDecoder.scaleToFitBoundsIfLarger
import com.example.comiclab.core.archive.ArchiveImageDecoder.scaleToWidthIfLarger

internal data class ArchiveReaderSessionBounds(
    val width: Int,
    val height: Int
)

internal interface ArchiveReaderSession : Closeable {
    fun isPdfSource(): Boolean = false
    fun readBounds(entryName: String): ArchiveReaderSessionBounds?
    fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap?
    fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap?
    fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap?
    fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap?
}

internal class SharedArchiveReaderSession private constructor(
    private val delegate: ArchiveReaderSession
) {
    private var activeLeases = 0
    private var sourceClosed = false

    @Synchronized
    fun acquire(): ArchiveReaderSession {
        check(!sourceClosed) { "Shared reader session is already closed" }
        activeLeases++
        return Lease()
    }

    @Synchronized
    private fun release() {
        if (activeLeases <= 0) {
            return
        }
        activeLeases--
        if (activeLeases == 0) {
            sourceClosed = true
            delegate.close()
        }
    }

    private inner class Lease : ArchiveReaderSession {
        private var closed = false

        override fun isPdfSource(): Boolean = delegate.isPdfSource()

        override fun readBounds(entryName: String): ArchiveReaderSessionBounds? =
            delegate.readBounds(entryName)

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? =
            delegate.decodePreviewForWidth(entryName, targetWidth)

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? =
            delegate.decodeImageForWidth(entryName, targetWidth)

        override fun decodeImageForPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? = delegate.decodeImageForPage(entryName, targetWidth, targetHeight)

        override fun decodeRegionForWidth(
            entryName: String,
            sourceRect: Rect,
            targetWidth: Int
        ): Bitmap? = delegate.decodeRegionForWidth(entryName, sourceRect, targetWidth)

        override fun close() {
            synchronized(this@SharedArchiveReaderSession) {
                if (closed) {
                    return
                }
                closed = true
                release()
            }
        }
    }

    companion object {
        fun wrap(session: ArchiveReaderSession): SharedArchiveReaderSession =
            SharedArchiveReaderSession(session)
    }
}

internal object ArchiveReaderSessionFactory {
    fun open(
        file: File,
        cacheRoot: File,
        extractSevenZipEntryToFile: (archive: IInArchive, entryIndex: Int, targetFile: File) -> Boolean
    ): ArchiveReaderSession {
        ArchiveReplacementRecovery.recover(file)
        return ReaderSession(file, cacheRoot, extractSevenZipEntryToFile)
    }

    internal fun <T> withSevenZipArchive(file: File, block: (IInArchive) -> T): T {
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

    private fun readSevenZipEntryIndexes(file: File): Map<String, Int> =
        withSevenZipArchive(file) { archive ->
            (0 until archive.numberOfItems)
                .asSequence()
                .filter { index -> archive.getProperty(index, PropID.IS_FOLDER) as? Boolean != true }
                .mapNotNull { index ->
                    (archive.getProperty(index, PropID.PATH) as? String)?.let { path -> path to index }
                }
                .toMap()
        }

    private class ReaderSession(
        private val file: File,
        cacheRoot: File,
        private val extractSevenZipEntryToFile: (archive: IInArchive, entryIndex: Int, targetFile: File) -> Boolean
    ) : ArchiveReaderSession {
        private val extension = file.extension.lowercase(Locale.ROOT)
        private val zipFile = if (extension in ArchiveFormatDetector.zipArchiveExtensions) ZipFile(file) else null
        private val zipLock = Any()
        private val sevenZipLock = Any()
        private val sevenZipEntryCache = if (extension in ArchiveFormatDetector.sevenZipArchiveExtensions) {
            ArchiveEntryFileCache(cacheRoot)
        } else {
            null
        }
        private val sevenZipEntryIndexes by lazy {
            if (extension in ArchiveFormatDetector.sevenZipArchiveExtensions) {
                readSevenZipEntryIndexes(file)
            } else {
                emptyMap()
            }
        }

        override fun readBounds(entryName: String): ArchiveReaderSessionBounds? {
            if (extension in ArchiveFormatDetector.zipArchiveExtensions) {
                return decodeZipBounds(entryName)
            }

            return withSevenZipImageFile(entryName) { imageFile ->
                ArchiveImageDecoder.decodeFileBounds(imageFile)?.let {
                    ArchiveReaderSessionBounds(it.width, it.height)
                }
            }
        }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            if (extension in ArchiveFormatDetector.zipArchiveExtensions) {
                return decodeZipBitmap(
                    entryName = entryName,
                    preferredConfig = Bitmap.Config.RGB_565,
                    sampleSize = { width, _ -> ArchiveImageDecoder.calculateInSampleSizeForWidth(width, targetWidth) }
                )?.scaleToWidthIfLarger(targetWidth)
            }

            return withSevenZipImageFile(entryName) { imageFile ->
                ArchiveImageDecoder.decodeFileBitmap(
                    file = imageFile,
                    preferredConfig = Bitmap.Config.RGB_565,
                    sampleSize = { width, _ -> ArchiveImageDecoder.calculateInSampleSizeForWidth(width, targetWidth) }
                )?.scaleToWidthIfLarger(targetWidth)
            }
        }

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? {
            if (extension in ArchiveFormatDetector.zipArchiveExtensions) {
                return decodeZipBitmap(
                    entryName = entryName,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                    sampleSize = { width, _ -> ArchiveImageDecoder.calculateInSampleSizeForWidth(width, targetWidth) }
                )?.scaleToWidthIfLarger(targetWidth)
            }

            return withSevenZipImageFile(entryName) { imageFile ->
                ArchiveImageDecoder.decodeFileBitmap(
                    file = imageFile,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                    sampleSize = { width, _ -> ArchiveImageDecoder.calculateInSampleSizeForWidth(width, targetWidth) }
                )?.scaleToWidthIfLarger(targetWidth)
            }
        }

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? {
            if (extension in ArchiveFormatDetector.zipArchiveExtensions) {
                return decodeZipBitmap(
                    entryName = entryName,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                    sampleSize = { width, height ->
                        maxOf(
                            ArchiveImageDecoder.calculateInSampleSizeForWidth(width, targetWidth),
                            ArchiveImageDecoder.calculateInSampleSizeForHeight(height, targetHeight)
                        )
                    }
                )?.scaleToFitBoundsIfLarger(targetWidth, targetHeight)
            }

            return withSevenZipImageFile(entryName) { imageFile ->
                ArchiveImageDecoder.decodeFileBitmap(
                    file = imageFile,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                    sampleSize = { width, height ->
                        maxOf(
                            ArchiveImageDecoder.calculateInSampleSizeForWidth(width, targetWidth),
                            ArchiveImageDecoder.calculateInSampleSizeForHeight(height, targetHeight)
                        )
                    }
                )?.scaleToFitBoundsIfLarger(targetWidth, targetHeight)
            }
        }

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? {
            if (extension in ArchiveFormatDetector.zipArchiveExtensions) {
                return decodeZipRegionForWidth(entryName, sourceRect, targetWidth)
            }

            return withSevenZipImageFile(entryName) { imageFile ->
                ArchiveImageDecoder.decodeFileRegionForWidth(imageFile, sourceRect, targetWidth)
            }
        }

        override fun close() {
            try {
                zipFile?.close()
            } finally {
                sevenZipEntryCache?.close()
            }
        }

        private fun decodeZipBounds(entryName: String): ArchiveReaderSessionBounds? {
            val zip = zipFile ?: return null
            synchronized(zipLock) {
                val entry = zip.getEntry(entryName) ?: return null
                return zip.getInputStream(entry).use { stream ->
                    ArchiveImageDecoder.decodeBounds(stream)?.let {
                        ArchiveReaderSessionBounds(it.width, it.height)
                    }
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
                return ArchiveImageDecoder.decodeZipBitmap(zip, entryName, preferredConfig, sampleSize)
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
                    ArchiveImageDecoder.decodeRegionForWidth(stream, sourceRect, targetWidth)
                }
            }
        }

        private fun <T> withSevenZipImageFile(entryName: String, use: (File) -> T?): T? {
            synchronized(sevenZipLock) {
                val entryIndex = sevenZipEntryIndexes[entryName] ?: return null
                val cache = sevenZipEntryCache ?: return null
                return cache.withEntry(
                    entryName = entryName,
                    extract = { target ->
                        withSevenZipArchive(file) { archive ->
                            extractSevenZipEntryToFile(archive, entryIndex, target)
                        }
                    },
                    use = use
                )
            }
        }
    }
}
