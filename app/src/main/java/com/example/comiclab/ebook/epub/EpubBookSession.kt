package com.example.comiclab.ebook.epub

import com.example.comiclab.ebook.EbookBook
import com.example.comiclab.ebook.EbookResourceRecord
import com.example.comiclab.ebook.EbookSession
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

class EpubBookSession private constructor(
    override val book: EbookBook,
    private val resourceRecords: Map<String, EbookResourceRecord>,
    private val zipFile: ZipFile
) : EbookSession {

    @Volatile
    private var closed = false

    override fun openResource(resourceId: String): InputStream? {
        if (closed) {
            return null
        }

        val record = resourceRecords[resourceId] ?: return null
        return runCatching {
            val entry = zipFile.getEntry(record.path) ?: return@runCatching null
            zipFile.getInputStream(entry)
        }.getOrNull()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        runCatching { zipFile.close() }
    }

    companion object {
        fun open(file: File): EpubBookSession {
            val parsed = EpubParser().parse(file)
            val zipFile = try {
                ZipFile(parsed.sourceFile)
            } catch (error: IOException) {
                throw EpubParseException(
                    EpubParseError.INVALID_ARCHIVE,
                    "Unable to reopen EPUB archive",
                    error
                )
            }
            return EpubBookSession(
                book = parsed.book,
                resourceRecords = parsed.resourceRecords,
                zipFile = zipFile
            )
        }
    }
}
