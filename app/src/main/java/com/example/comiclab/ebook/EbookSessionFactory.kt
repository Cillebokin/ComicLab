package com.example.comiclab.ebook

import com.example.comiclab.ebook.epub.EpubBookSession
import com.example.comiclab.ebook.mobi.MobiBookSession
import com.example.comiclab.ebook.txt.TxtBookSession
import java.io.File

object EbookSessionFactory {

    fun open(
        file: File,
        untitledBookTitle: String,
        untitledChapterTitle: String
    ): EbookSession {
        checkEbookCancellation()
        val session = when (ReaderFileDetector.typeOf(file)) {
            ReaderFileType.MOBI -> MobiBookSession.open(file, untitledBookTitle)
            ReaderFileType.EPUB -> EpubBookSession.open(file, untitledChapterTitle)
            ReaderFileType.TXT -> TxtBookSession.open(file, untitledBookTitle, untitledChapterTitle)
            else -> throw IllegalArgumentException("Unsupported ebook file")
        }
        return try {
            checkEbookCancellation()
            session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }
}
