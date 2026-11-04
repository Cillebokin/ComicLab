package com.example.comiclab.ebook

import com.example.comiclab.ebook.mobi.PalmDocDecompressor
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class EbookContentLimitsTest {
    @Test fun byteAndCharacterReadsStopAtTheirExpandedContentBudget() {
        assertThrows(EbookContentLimitException::class.java) {
            ByteArrayInputStream(ByteArray(100)).use { it.readEbookBytes(16) }
        }
        assertThrows(EbookContentLimitException::class.java) {
            StringReader("正文".repeat(100)).use { it.readEbookText(16) }
        }
        assertArrayEquals(ByteArray(16), ByteArrayInputStream(ByteArray(16)).readEbookBytes(16))
    }

    @Test fun metadataStreamAndPalmDocExpansionCannotBypassTheBudget() {
        assertThrows(EbookContentLimitException::class.java) {
            ByteArrayInputStream(ByteArray(100)).limitEbookInput(16).use { it.readBytes() }
        }
        // One literal followed by an overlapping ten-byte copy expands to eleven bytes.
        assertThrows(EbookContentLimitException::class.java) {
            PalmDocDecompressor.decompress(byteArrayOf(65, 0x80.toByte(), 0x0F), 8)
        }
    }

    @Test fun outputChecksBothCharactersAndMarkupComplexityBeforeWriting() {
        val target = StringWriter()
        val writer = EbookLimitWriter(target, EbookContentLimits(100, 100, 10, 2))
        writer.write("<p>abc</p>")
        assertEquals("<p>abc</p>", target.toString())
        assertThrows(EbookContentLimitException::class.java) { writer.write("x") }
        assertEquals(10, target.toString().length)
        assertThrows(EbookContentLimitException::class.java) {
            EbookLimitWriter(StringWriter(), EbookContentLimits(100, 100, 100, 1)).write("<p></p>")
        }
    }

    @Test fun smallerHeapsReceiveSmallerBudgetsAndLargeHeapsStillHaveACeiling() {
        val low = EbookContentLimits.forHeap(128L * 1024 * 1024)
        val high = EbookContentLimits.forHeap(1024L * 1024 * 1024)
        assertTrue(low.maxBookChars < high.maxBookChars)
        assertTrue(low.maxEntryBytes < high.maxEntryBytes)
        assertEquals(16L * 1024 * 1024, high.maxBookChars)
        assertEquals(32L * 1024 * 1024, high.maxEntryBytes)
    }
}
