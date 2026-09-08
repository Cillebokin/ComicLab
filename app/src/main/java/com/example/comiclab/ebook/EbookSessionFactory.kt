package com.example.comiclab.ebook

import com.example.comiclab.ebook.epub.EpubBookSession
import com.example.comiclab.ebook.mobi.MobiBookSession
import java.io.File

object EbookSessionFactory {

    fun open(file: File): EbookSession {
        return when (ReaderFileDetector.typeOf(file)) {
            ReaderFileType.MOBI -> MobiBookSession.open(file)
            ReaderFileType.EPUB -> EpubBookSession.open(file)
            else -> throw IllegalArgumentException("Unsupported ebook file")
        }
    }
}
