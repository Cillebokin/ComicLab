package com.example.comiclab.core.archive

import java.io.File
import java.util.Locale

internal object ArchiveFormatDetector {
    val zipArchiveExtensions = setOf("zip", "cbz")
    val sevenZipArchiveExtensions = setOf("rar", "cbr", "7z", "cb7")
    val pdfExtensions = setOf("pdf")
    private val supportedArchiveExtensions = zipArchiveExtensions + sevenZipArchiveExtensions + pdfExtensions
    private val archiveExtensions = supportedArchiveExtensions + setOf("tar", "gz")
    fun isArchive(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.ROOT) in archiveExtensions
    }

    fun isSupportedArchive(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.ROOT) in supportedArchiveExtensions
    }

    fun isPdf(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.ROOT) in pdfExtensions
    }

}
