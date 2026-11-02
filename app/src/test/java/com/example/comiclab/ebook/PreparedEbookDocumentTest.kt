package com.example.comiclab.ebook

import com.example.comiclab.ebook.txt.TxtBookSession
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreparedEbookDocumentTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun webViewShellDoesNotContainBookBodiesAndChaptersRemainIndependent() {
        val session = FakeSession(EbookBook("book", null, (0 until 100).map {
            EbookChapter(it, "chapter $it", "<p id='note'>BODY_$it.</p>" +
                "<a href='c99.xhtml#note'>last</a>", "c$it.xhtml")
        }, emptyList()))
        PreparedEbookDocument.prepare(session, folder.root).use { document ->
            val shell = document.openHtml()!!.bufferedReader().use { it.readText() }
            assertFalse("the WebView must not parse all chapter bodies on entry", shell.contains("BODY_"))
            assertTrue("navigation needs the logical book length", shell.contains("data-chapter-count=\"100\""))
            val fragments = document.htmlFile.parentFile!!.listFiles()!!.filter { it.name.contains("chapter-") }
            assertEquals(100, fragments.size)
            val first = fragments.single { it.name.endsWith("chapter-0.html") }.readText()
            assertTrue(first.contains("BODY_0."))
            assertFalse(first.contains("BODY_99."))
            assertTrue("cross-chapter anchors must survive preparation", first.contains("#chapter-99-a-6e6f7465"))
        }
        assertTrue(File(folder.root, "ebook-documents").listFiles()!!.isEmpty())
    }

    @Test fun chapterDocumentsPreserveEscapingAndReleaseBodiesWithoutChangingTheOriginal() {
        val original = "正文 & <text>\nsecond line"
        val file = folder.newFile("book.txt").apply { writeText(original) }
        val session = TxtBookSession.open(file, "book", "chapter")
        val prepared = PreparedEbookDocument.prepare(session, folder.root)
        try {
            val shell = prepared.openHtml()!!.bufferedReader().use { it.readText() }
            val chapter = prepared.openChapter(0)!!.bufferedReader().use { it.readText() }
            assertTrue(chapter.contains("正文 &amp; &lt;text&gt;<br>\nsecond line"))
            assertFalse(shell.contains("正文"))
            assertEquals((shell.length + chapter.length).toLong(), prepared.htmlChars)
            assertTrue(session.book.chapters.single().html.isEmpty())
            assertEquals("book", session.book.title)
            assertEquals(original, file.readText())
        } finally { prepared.close() }
        assertFalse(prepared.htmlFile.exists())
        assertNull(prepared.openHtml())
        assertNull(prepared.openChapter(0))
    }

    @Test fun closingTheDocumentClosesInflightHtmlStreamsAndTheSessionExactlyOnce() {
        val session = FakeSession()
        val prepared = PreparedEbookDocument.prepare(session, folder.root)
        val stream = prepared.openHtml()!!
        val chapter = prepared.openChapter(0)!!
        prepared.close()
        prepared.close()
        assertEquals(1, session.closes)
        assertThrows(java.io.IOException::class.java) { stream.read() }
        assertThrows(java.io.IOException::class.java) { chapter.read() }
        assertFalse(prepared.htmlFile.exists())
    }

    @Test fun aSingleOversizedChapterIsRejectedAndAllEarlierChapterFilesAreDeleted() {
        val session = FakeSession(EbookBook("book", null, listOf(
            EbookChapter(0, "small", "<p>small</p>"),
            EbookChapter(1, "large", "<p>" + "text".repeat(1000) + "</p>")), emptyList()))
        assertThrows(EbookContentLimitException::class.java) {
            PreparedEbookDocument.prepare(session, folder.root, limits = EbookContentLimits(
                100000, 100000, 100000, 10000, maxChapterHtmlChars = 1024))
        }
        assertEquals(1, session.closes)
        assertTrue(File(folder.root, "ebook-documents").listFiles()!!.isEmpty())
    }

    @Test fun aggregateTagLimitStillAppliesAcrossSeparateChapterWriters() {
        val session = FakeSession(EbookBook("book", null, (0 until 10).map {
            EbookChapter(it, "chapter", "<p>x</p>".repeat(20))
        }, emptyList()))
        assertThrows(EbookContentLimitException::class.java) {
            PreparedEbookDocument.prepare(session, folder.root, limits = EbookContentLimits(100000, 100000, 100000, 100))
        }
        assertTrue(File(folder.root, "ebook-documents").listFiles()!!.isEmpty())
    }

    @Test fun exceedingOutputBudgetDeletesPartialDocumentsAndClosesTheSession() {
        val session = FakeSession()
        assertThrows(EbookContentLimitException::class.java) {
            PreparedEbookDocument.prepare(session, folder.root, limits = EbookContentLimits(100, 100, 32, 100))
        }
        assertEquals(1, session.closes)
        assertTrue(File(folder.root, "ebook-documents").listFiles()!!.isEmpty())
    }

    @Test fun cancellationWhileLoadingSvgCleansThePartiallyWrittenDocument() {
        val session = FakeSession(EbookBook("book", null,
            listOf(EbookChapter(0, "chapter", "<img src='pic.svg'>", "chapter.xhtml")),
            listOf(EbookResource("svg", mimeType = "image/svg+xml", path = "pic.svg")))) {
            throw CancellationException("cancel SVG read")
        }
        assertThrows(CancellationException::class.java) { PreparedEbookDocument.prepare(session, folder.root) }
        assertEquals(1, session.closes)
        assertTrue(File(folder.root, "ebook-documents").listFiles()!!.isEmpty())
    }

    @Test fun orphanCleanupPreservesActiveDocumentsAndUnrelatedFiles() {
        val directory = File(folder.root, "ebook-documents").apply { mkdirs() }
        val orphan = File(directory, "reader-orphan.html").apply { writeText("orphan") }
        val unrelated = File(directory, "other.txt").apply { writeText("keep") }
        val first = PreparedEbookDocument.prepare(FakeSession(), folder.root)
        val second = PreparedEbookDocument.prepare(FakeSession(), folder.root)
        try {
            assertFalse(orphan.exists())
            assertTrue(first.htmlFile.isFile)
            assertTrue(second.htmlFile.isFile)
            assertEquals("keep", unrelated.readText())
        } finally { first.close(); second.close() }
    }

    @Test fun aPurgedCacheFileDoesNotThrowOutOfTheWebViewResourceCallback() {
        val session = FakeSession()
        val prepared = PreparedEbookDocument.prepare(session, folder.root)
        try {
            assertTrue(prepared.htmlFile.delete())
            assertNull(prepared.openHtml())
            assertTrue(prepared.readFailed)
        } finally { prepared.close() }
        assertEquals(1, session.closes)
    }

    private class FakeSession(book: EbookBook = EbookBook("book", null,
        listOf(EbookChapter(0, "chapter", "<p>body</p>")), emptyList()),
        private val resource: () -> InputStream? = { null }) : EbookSession {
        override var book = book
        var closes = 0
        override fun openResource(resourceId: String) = resource()
        override fun releaseChapterContent() { book = book.withoutChapterContent() }
        override fun close() { closes++ }
    }
}
