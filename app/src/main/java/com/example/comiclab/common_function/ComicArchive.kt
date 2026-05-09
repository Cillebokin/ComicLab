package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ComicArchive {
    private val supportedArchiveExtensions = setOf("zip", "cbz")
    private val archiveExtensions = supportedArchiveExtensions + setOf("rar", "cbr", "7z", "cb7", "tar", "gz")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun isArchive(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.ROOT) in archiveExtensions
    }

    fun isSupportedArchive(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.ROOT) in supportedArchiveExtensions
    }

    fun imageEntries(file: File): List<String> {
        ZipFile(file).use { zipFile ->
            return zipFile.entries().asSequence()
                .filter { !it.isDirectory && it.isImageEntry() }
                .map { it.name }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toList()
        }
    }

    fun decodeImage(file: File, entryName: String, maxSize: Int): Bitmap? {
        ZipFile(file).use { zipFile ->
            val entry = zipFile.getEntry(entryName) ?: return null
            val bytes = zipFile.getInputStream(entry).use { it.readBytes() }

            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        }
    }

    private fun ZipEntry.isImageEntry(): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in imageExtensions
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
}
