package com.example.comiclab.ebook

import com.example.comiclab.ebook.epub.EpubParser
import com.example.comiclab.ebook.mobi.MobiParser
import com.example.comiclab.ebook.mobi.PalmDocDecompressor
import com.example.comiclab.ebook.txt.TxtBookSession
import com.example.comiclab.ebook.html.EbookHtmlRenderer
import com.example.comiclab.ebook.html.EbookStyle
import java.util.concurrent.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EbookCancellationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun cancelled(action: () -> Unit) {
        Thread.currentThread().interrupt()
        try { assertThrows(CancellationException::class.java, action) }
        finally { Thread.interrupted() }
    }

    @Test fun cancelledEpubDoesNotContinueToOpenTheArchive() {
        val file = temporaryFolder.newFile("cancel.epub").apply { writeText("fixture") }
        cancelled { EpubParser().parse(file) }
    }
    @Test fun cancelledMobiDoesNotContinueToReadRecords() {
        val file = temporaryFolder.newFile("cancel.mobi").apply { writeText("fixture") }
        cancelled { MobiParser().parse(file) }
    }
    @Test fun cancelledTxtDoesNotBuildTheWholeHtml() {
        val file = temporaryFolder.newFile("cancel.txt").apply { writeText("content") }
        cancelled { TxtBookSession.open(file, "book", "chapter") }
    }
    @Test fun cancelledPalmDocDoesNotDecompressRecords() {
        cancelled { PalmDocDecompressor.decompress(byteArrayOf(65, 66, 67)) }
    }
    @Test fun cancelledRendererDoesNotBuildTheWholeDocument() {
        val book = EbookBook("book", null, listOf(EbookChapter(0, "chapter", "<p>content</p>")), emptyList())
        cancelled { EbookHtmlRenderer().render(book, EbookStyle()) }
    }

    @Test fun cancellationDuringAChapterReadStopsBeforeTheNextChunkAndClosesTheStream() {
        var reads = 0
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(ByteArray(100000)) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                reads++
                return super.read(buffer, off, len).also { Thread.currentThread().interrupt() }
            }
            override fun close() { closed = true; super.close() }
        }
        try { assertThrows(CancellationException::class.java) { stream.use { it.readEbookBytes() } } }
        finally { Thread.interrupted() }
        org.junit.Assert.assertEquals(1, reads)
        org.junit.Assert.assertTrue(closed)
    }
}
