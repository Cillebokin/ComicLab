package com.example.comiclab.ebook.html

import com.example.comiclab.ebook.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EpubContentRobolectricTest {
    private fun book(first: String, second: String = "<p id='note'>Footnote</p>") = EbookBook(
        "book", null,
        listOf(EbookChapter(0, "one", first, "OPS/Text/chapter.xhtml"),
            EbookChapter(1, "notes", second, "OPS/Notes/notes.xhtml")),
        listOf(EbookResource("cover", mimeType = "image/jpeg", path = "OPS/Images/cover 1.jpg"))
    )
    private fun render(book: EbookBook) = EbookHtmlRenderer().render(book, EbookStyle())
    private fun idFor(text: String, html: String) = Regex("<p[^>]*\\sid=\"([^\"]+)\"[^>]*>$text</p>").find(html)!!.groupValues[1]

    @Test fun relativeCrossChapterFootnoteAndReturnLinksUseDistinctTargets() {
        val html = render(book("<p id='note'>Body</p><a href='../Notes/notes.xhtml#note'>See note</a>",
            "<p id='note'>Footnote</p><a href='../Text/chapter.xhtml#note'>Return</a>"))
        val body = idFor("Body", html)
        val note = idFor("Footnote", html)
        assertNotEquals(body, note)
        assertTrue(html.contains("href=\"#$note\">See note"))
        assertTrue(html.contains("href=\"#$body\">Return"))
    }

    @Test fun namedUnicodeAnchorsAndSelfClosingAnchorsDoNotCaptureFollowingText() {
        val html = render(book("<a name='注1'/><a href='#%E6%B3%A81'>Note</a><p>body</p>"))
        val target = Regex("<a id=\"([^\"]+)\"[^>]*></a>").find(html)?.groupValues?.get(1)
        assertNotNull(target)
        assertTrue(html.contains("href=\"#$target\">Note</a><p>body</p>"))
    }

    @Test fun missingOrExternalTargetsRemainInertAndChapterOnlyLinksWork() {
        val html = render(book("<a href='../Notes/notes.xhtml'>Notes</a><a href='#missing'>Missing</a><a href='https://site/1'>External</a>"))
        assertTrue(html.contains("href=\"#chapter-1\">Notes"))
        assertTrue(html.contains("<a>Missing</a>"))
        assertTrue(html.contains("<a>External</a>"))
    }

    @Test fun svgImageWrapperAndBasicVectorContentSurviveWithSafeResourceReferences() {
        val html = render(book("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 900"><image width="600" height="900" xlink:href="../Images/cover%201.jpg"/><path d="M0 0 L10 10" stroke="red" onclick="bad()"/></svg>"""))
        assertTrue(html.contains("viewBox=\"0 0 600 900\""))
        assertTrue(html.contains("href=\"ebook-resource://cover\""))
        assertTrue(html.contains("<path"))
        assertFalse(html.contains("onclick"))
    }

    @Test fun unknownEpubImageDoesNotFallBackToAnUnrelatedNumericImageSlot() {
        val html = render(book("<img src='../Images/missing0.jpg'><img src='https://remote/0.jpg'>"))
        assertFalse(html.contains("src=\"ebook-resource://cover\""))
    }

    @Test fun standaloneSvgIsInlinedWithRelativeImagesAndIsolatedIdsForEachOccurrence() {
        val book = book("<img src='../Images/picture.svg'><img src='../Images/picture.svg'>").let {
            it.copy(resources = it.resources + EbookResource("svg", mimeType = "image/svg+xml", path = "OPS/Images/picture.svg"))
        }
        var loads = 0
        val html = EbookHtmlRenderer().render(book, EbookStyle()) {
            loads++
            """<svg:svg viewBox="0 0 100 100"><svg:defs><svg:linearGradient id="paint"><svg:stop offset="0" stop-color="red"/></svg:linearGradient></svg:defs><svg:path d="M0 0 L50 50" fill="url(#paint)"/><svg:image xlink:href="cover%201.jpg" width="100" height="100"/></svg:svg>"""
        }
        assertEquals(2, loads)
        assertEquals(2, Regex("<svg ").findAll(html).count())
        assertEquals(2, Regex(" href=\"ebook-resource://cover\"").findAll(html).count())
        val gradientIds = Regex("<linearGradient id=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(2, gradientIds.distinct().size)
        gradientIds.forEach { assertTrue(html.contains("fill=\"url(#$it)\"")) }
    }

    @Test fun unsafeSvgBlocksAndRemotePaintsAreRemovedButSafeInlinePaintSurvives() {
        val html = render(book("""<svg viewBox="0 0 10 10"><script>bad()</script><foreignObject><div>unsafe</div></foreignObject><rect style="fill:#fff;stroke:url(https://remote/paint);opacity:0.5" width="10" height="10" onload="bad()"/></svg>"""))
        assertTrue(html.contains("fill=\"#fff\""))
        assertTrue(html.contains("opacity=\"0.5\""))
        assertFalse(html.contains("bad()"))
        assertFalse(html.contains("unsafe"))
        assertFalse(html.contains("https://remote"))
    }

    @Test fun escapedFileNamesAndEntitiesResolveWithoutDoublePercentDecoding() {
        val source = book("<a href='../Notes/notes%2520%26more.xhtml#n%26m'>note</a>", "<p id='n&amp;m'>Footnote</p>")
        val book = source.copy(chapters = source.chapters.map { if (it.index == 1) it.copy(sourcePath = "OPS/Notes/notes%20&more.xhtml") else it })
        val html = render(book)
        assertTrue(html.contains("href=\"#${idFor("Footnote", html)}\">note"))
    }

    @Test fun namespacingPreservesSimpleIdStylesAndNamedAnchorAliases() {
        val source = book("<p id='note'>Body</p><a id='target' name='alias'/><a href='#alias'>alias</a>")
            .copy(stylesheet = "#note { color: #fff; } p:hover { color: red; }")
        val html = render(source)
        assertTrue(html.contains("[data-ebook-id=\"6e6f7465\"] { color: #fff; }"))
        val target = Regex("<a id=\"([^\"]+)\"[^>]*></a>").find(html)!!.groupValues[1]
        assertTrue(html.contains("href=\"#$target\">alias"))
    }

    @Test fun repeatedStandaloneVectorsRemainResourcesWithoutExpandingTheWholeHtml() {
        val book = book("<img src='../Images/picture.svg'>".repeat(20)).let {
            it.copy(resources = it.resources + EbookResource("svg", mimeType = "image/svg+xml", path = "OPS/Images/picture.svg"))
        }
        var reads = 0
        val html = EbookHtmlRenderer().render(book, EbookStyle()) {
            reads++
            "<svg viewBox='0 0 100 100'><path d='M0 0 L50 50'/></svg>"
        }
        assertEquals(1, reads)
        assertEquals(20, Regex("src=\"ebook-resource://svg\"").findAll(html).count())
        assertFalse(html.contains("<path"))
    }

    @Test fun inlineSvgPreservesEmbeddedRasterDataButDropsActiveDataDocuments() {
        val html = render(book("<svg><image href='data:image/png;base64,AAAA'/><image href='data:text/html;base64,AAAA'/></svg>"))
        assertTrue(html.contains(" href=\"data:image/png;base64,AAAA\""))
        assertFalse(html.contains("data:text/html"))
    }
}
