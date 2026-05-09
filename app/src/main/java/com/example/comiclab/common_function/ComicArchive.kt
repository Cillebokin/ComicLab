package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ComicArchive {
    private const val ZIP_PREVIEW_DECODE_THREAD_COUNT = 2

    data class ImageBounds(
        val width: Int,
        val height: Int
    )

    private val zipArchiveExtensions = setOf("zip", "cbz")
    private val sevenZipArchiveExtensions = setOf("rar", "cbr", "7z", "cb7")
    private val supportedArchiveExtensions = zipArchiveExtensions + sevenZipArchiveExtensions
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

    fun imageEntries(file: File): List<String> {
        return when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> zipImageEntries(file)
            in sevenZipArchiveExtensions -> sevenZipImageEntries(file)
            else -> emptyList()
        }
    }

    fun decodeImage(file: File, entryName: String, maxSize: Int): Bitmap? {
        val bytes = imageBytes(file, entryName) ?: return null
        return decodeBitmap(
            bytes = bytes,
            preferredConfig = Bitmap.Config.RGB_565,
            sampleSize = { width, height -> calculateInSampleSize(width, height, maxSize) }
        )
    }

    fun decodeImageForWidth(file: File, entryName: String, targetWidth: Int): Bitmap? {
        val bytes = imageBytes(file, entryName) ?: return null
        return decodeBitmap(
            bytes = bytes,
            preferredConfig = Bitmap.Config.ARGB_8888,
            sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
        )
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
            else -> Unit
        }
    }

    fun openReaderSession(file: File, cacheRoot: File): ReaderSession {
        return ReaderSession(file, cacheRoot)
    }

    class ReaderSession internal constructor(
        private val file: File,
        cacheRoot: File
    ) : Closeable {

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

        fun readBounds(entryName: String): ImageBounds? {
            val bytes = imageBytes(entryName) ?: return null
            return decodeBounds(bytes)
        }

        fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            val bytes = imageBytes(entryName) ?: return null
            return decodeBitmap(
                bytes = bytes,
                preferredConfig = Bitmap.Config.RGB_565,
                sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
            )
        }

        fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? {
            val bytes = imageBytes(entryName) ?: return null
            return decodeBitmap(
                bytes = bytes,
                preferredConfig = Bitmap.Config.ARGB_8888,
                sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
            )
        }

        fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? {
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
                decoder.decodeRegion(boundedRect, options)
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

    private fun zipImageEntries(file: File): List<String> {
        ZipFile(file).use { zipFile ->
            return zipFile.entries().asSequence()
                .filter { !it.isDirectory && it.isImageEntry() }
                .map { it.name }
                .sortedWith(naturalEntryNameComparator)
                .toList()
        }
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
        )
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

    private fun ZipEntry.isImageEntry(): Boolean {
        return name.isImageEntryName()
    }

    private fun String.isImageEntryName(): Boolean {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in imageExtensions
    }

    private fun IInArchive.isFolder(index: Int): Boolean {
        return getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
    }

    private fun IInArchive.entryPath(index: Int): String? {
        return getProperty(index, PropID.PATH) as? String
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

        while (sampledWidth / 2 >= maxSize && sampledHeight / 2 >= maxSize) {
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
}
