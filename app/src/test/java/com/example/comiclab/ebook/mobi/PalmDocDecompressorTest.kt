package com.example.comiclab.ebook.mobi

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PalmDocDecompressorTest {

    @Test
    fun decompressesUncompressedBytesAndZeroBytes() {
        val expected = byteArrayOf(0, 'A'.code.toByte(), 'B'.code.toByte(), 0)

        assertArrayEquals(expected, PalmDocDecompressor.decompress(expected))
    }

    @Test
    fun decompressesLiteralRunsAndSpacePrefixedLiterals() {
        val input = byteArrayOf(3, 'c'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 0xC1.toByte())

        assertEquals(
            "cat A",
            String(PalmDocDecompressor.decompress(input), StandardCharsets.US_ASCII)
        )
    }

    @Test
    fun decompressesBackReferencesIncludingOverlappingCopies() {
        val regularBackReference = byteArrayOf(
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            0x80.toByte(), 0x18
        )
        val overlappingBackReference = byteArrayOf('x'.code.toByte(), 0x80.toByte(), 0x0A)

        assertEquals(
            "abcabc",
            String(PalmDocDecompressor.decompress(regularBackReference), StandardCharsets.US_ASCII)
        )
        assertEquals(
            "xxxxxx",
            String(PalmDocDecompressor.decompress(overlappingBackReference), StandardCharsets.US_ASCII)
        )
    }

    @Test
    fun decompressRejectsTruncatedLiteralRun() {
        assertInvalidRecord(byteArrayOf(3, 'a'.code.toByte()))
    }

    @Test
    fun decompressRejectsTruncatedOrOutOfRangeBackReference() {
        assertInvalidRecord(byteArrayOf(0x80.toByte()))
        assertInvalidRecord(byteArrayOf(0x80.toByte(), 0x18))
    }

    private fun assertInvalidRecord(input: ByteArray) {
        try {
            PalmDocDecompressor.decompress(input)
            fail("Expected malformed PalmDOC record to be rejected")
        } catch (error: MobiParseException) {
            assertEquals(MobiParseError.INVALID_RECORD, error.reason)
        }
    }
}
