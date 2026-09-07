package com.example.comiclab.ebook

import com.example.comiclab.ComicArchive
import java.io.File

enum class ReaderFileType {
    IMAGE_ARCHIVE,
    PDF,
    MOBI
}

object ReaderFileDetector {

    fun typeOf(file: File): ReaderFileType? {
        if (!file.isFile) {
            return null
        }

        return when {
            file.extension.equals("mobi", ignoreCase = true) -> ReaderFileType.MOBI
            ComicArchive.isPdf(file) -> ReaderFileType.PDF
            ComicArchive.isSupportedArchive(file) -> ReaderFileType.IMAGE_ARCHIVE
            else -> null
        }
    }

    fun isSupported(file: File): Boolean = typeOf(file) != null

    fun isMobi(file: File): Boolean = typeOf(file) == ReaderFileType.MOBI
}
