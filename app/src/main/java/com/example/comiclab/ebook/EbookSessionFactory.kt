package com.example.comiclab.ebook

import com.example.comiclab.ebook.epub.EpubBookSession
import com.example.comiclab.ebook.mobi.MobiBookSession
import java.io.File

object EbookSessionFactory {

    fun open(
        file: File,
        untitledBookTitle: String,
        untitledChapterTitle: String
    ): EbookSession {
        return when (ReaderFileDetector.typeOf(file)) {
            ReaderFileType.MOBI -> MobiBookSession.open(file, untitledBookTitle)
            ReaderFileType.EPUB -> EpubBookSession.open(file, untitledChapterTitle)
            else -> throw IllegalArgumentException("Unsupported ebook file")
        }
    }
}
