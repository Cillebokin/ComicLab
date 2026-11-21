package com.example.comiclab.core.archive

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import com.example.comiclab.core.archive.ArchiveFormatDetector.zipArchiveExtensions
import com.example.comiclab.core.archive.ArchiveFormatDetector.sevenZipArchiveExtensions
import com.example.comiclab.core.archive.ArchiveFormatDetector.pdfExtensions

internal object ArchiveEntryLister {
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    val naturalEntryNameComparator = Comparator<String> { left, right ->
        compareNaturalEntryNames(left, right)
    }

    fun imageEntries(file: File, sevenZipImages: (File) -> List<String>, pdfImages: (File) -> List<String>): List<String> {
        ArchiveReplacementRecovery.recover(file)
        if (!file.isFile) {
            return emptyList()
        }

        return runCatching {
            if (file.extension.lowercase(Locale.ROOT) in pdfExtensions) {
                pdfImages(file)
            } else {
                listArchiveImages(file, sevenZipImages)
            }
        }.getOrDefault(emptyList())
    }

    fun readArchiveImageEntries(file: File, sevenZipImages: (File) -> List<String>): Result<List<String>> =
        runCatching {
            if (!ArchiveReplacementRecovery.recover(file) || !file.isFile) {
                throw IOException("Archive is unavailable")
            }
            listArchiveImages(file, sevenZipImages)
        }

    private fun listArchiveImages(file: File, sevenZipImages: (File) -> List<String>): List<String> =
        when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> zipImageEntries(file)
            in sevenZipArchiveExtensions -> sevenZipImages(file)
            else -> throw IOException("Unsupported comic archive")
        }

    fun firstImageEntryIfFirstFileIsImage(file: File, sevenZipFiles: (File) -> List<String>, pdfImages: (File) -> List<String>, isPdfPage: (String) -> Boolean): String? {
        ArchiveReplacementRecovery.recover(file)
        if (!file.isFile) {
            return null
        }

        return runCatching {
            when (file.extension.lowercase(Locale.ROOT)) {
                in zipArchiveExtensions -> zipFileEntries(file)
                in sevenZipArchiveExtensions -> sevenZipFiles(file)
                in pdfExtensions -> pdfImages(file)
                else -> emptyList()
            }.firstOrNull()
                ?.takeIf { isImageEntryName(it) || isPdfPage(it) }
        }.getOrNull()
    }

    fun imageEntryNames(names: Sequence<String>): List<String> =
        names.filter { isImageEntryName(it) }.sortedWith(naturalEntryNameComparator).toList()

    fun fileEntryNames(names: Sequence<String>): List<String> =
        names.filterNot(::isMacOsMetadataEntry).sortedWith(naturalEntryNameComparator).toList()

    private fun zipImageEntries(file: File): List<String> =
        ZipFile(file).use { imageEntryNames(it.fileNames()) }

    private fun zipFileEntries(file: File): List<String> =
        ZipFile(file).use { fileEntryNames(it.fileNames()) }

    private fun ZipFile.fileNames(): Sequence<String> =
        entries().asSequence().filterNot { it.isDirectory }.map { it.name }

    fun isImageEntryName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in imageExtensions && !isMacOsMetadataEntry(name)
    }

    private fun isMacOsMetadataEntry(name: String): Boolean {
        val pathComponents = name.split('/', '\\')
        val fileName = pathComponents.last()
        return fileName.startsWith("._") ||
            fileName.equals(".DS_Store", ignoreCase = true) ||
            pathComponents.any { it.equals("__MACOSX", ignoreCase = true) }
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

}
