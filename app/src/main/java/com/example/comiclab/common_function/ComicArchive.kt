package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ComicArchive {
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

            if (extractResult == ExtractOperationResult.OK) {
                output.toByteArray()
            } else {
                null
            }
        }
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
