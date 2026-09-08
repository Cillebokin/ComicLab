package com.example.comiclab.ebook

import com.example.comiclab.ComicArchive
import java.io.File

enum class ReaderFileType {
    IMAGE_ARCHIVE,
    PDF,
    MOBI,
    EPUB
}

object ReaderFileDetector {

    fun typeOf(file: File): ReaderFileType? {
        if (!file.isFile) {
            return null
        }

        return when {
            file.extension.equals("mobi", ignoreCase = true) ||
                file.extension.equals("azw", ignoreCase = true) ||
                file.extension.equals("azw3", ignoreCase = true) -> ReaderFileType.MOBI
            file.extension.equals("epub", ignoreCase = true) -> ReaderFileType.EPUB
            ComicArchive.isPdf(file) -> ReaderFileType.PDF
            ComicArchive.isSupportedArchive(file) -> ReaderFileType.IMAGE_ARCHIVE
            else -> null
        }
    }

    fun isSupported(file: File): Boolean = typeOf(file) != null

    fun isMobi(file: File): Boolean = typeOf(file) == ReaderFileType.MOBI

    fun isEpub(file: File): Boolean = typeOf(file) == ReaderFileType.EPUB

    fun isEbook(file: File): Boolean {
        return when (typeOf(file)) {
            ReaderFileType.MOBI,
            ReaderFileType.EPUB -> true
            else -> false
        }
    }
}
