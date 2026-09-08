package com.example.comiclab.ebook.html

import com.example.comiclab.ebook.EbookBook
import com.example.comiclab.ebook.EbookChapter
import com.example.comiclab.ebook.EbookResource
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookHtmlRendererTest {

    @Test
    fun mobiRecindexUsesOneBasedResourcePosition() {
        val html = render(
            "<p><img recindex=\"0001\"></p>"
        )

        assertTrue(html.contains("ebook-resource://image-0001"))
        assertTrue(!html.contains("ebook-resource://image-0002"))
    }

    @Test
    fun kindleEmbedReferenceUsesOneBasedResourcePosition() {
        val html = render(
            "<p><img src=\"kindle:embed:0001?mime=image/jpg\"></p>"
        )

        assertTrue(html.contains("ebook-resource://image-0001"))
        assertTrue(!html.contains("ebook-resource://image-0002"))
    }

    @Test
    fun kindleEmbedReferenceUsesKindleBase32ResourceIndex() {
        val book = EbookBook(
            title = "Test",
            author = null,
            chapters = listOf(
                EbookChapter(0, "Chapter", "<p><img src=\"kindle:embed:000A?mime=image/jpg\"></p>")
            ),
            resources = listOf(
                EbookResource(
                    id = "image-0010",
                    recordIndex = 19,
                    mimeType = "image/jpeg",
                    resourceIndex = 9
                )
            )
        )

        val html = EbookHtmlRenderer().render(book, EbookStyle())

        assertTrue(html.contains("ebook-resource://image-0010"))
    }

    @Test
    fun mobiRecindexUsesRelativeSlotWhenNonImageRecordsAreSkipped() {
        val book = EbookBook(
            title = "Test",
            author = null,
            chapters = listOf(EbookChapter(0, "Chapter", "<img recindex=\"0002\">")),
            resources = listOf(
                EbookResource(
                    id = "image-0002",
                    recordIndex = 12,
                    mimeType = "image/jpeg",
                    resourceIndex = 1
                )
            )
        )

        val html = EbookHtmlRenderer().render(book, EbookStyle())

        assertTrue(html.contains("ebook-resource://image-0002"))
    }

    private fun render(body: String): String {
        val book = EbookBook(
            title = "Test",
            author = null,
            chapters = listOf(EbookChapter(0, "Chapter", body)),
            resources = listOf(
                EbookResource("image-0001", recordIndex = 10, mimeType = "image/jpeg"),
                EbookResource("image-0002", recordIndex = 11, mimeType = "image/jpeg")
            )
        )
        return EbookHtmlRenderer().render(book, EbookStyle())
    }
}
