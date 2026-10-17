package com.example.comiclab.ebook.mobi

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Random
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

    @Test
    fun largeOutputMatchesReferenceDecodingByteForByte() {
        // 构造有效的 PalmDOC 输入：字面量段与回溯复制交替，输出远超初始缓冲容量。
        val random = Random(20261016L)
        val input = ByteArrayOutputStream()
        var decodedLength = 0
        while (decodedLength < 20_000) {
            if (decodedLength == 0 || random.nextBoolean()) {
                val count = 1 + random.nextInt(8)
                input.write(count)
                repeat(count) { input.write(random.nextInt(256)) }
                decodedLength += count
            } else {
                val distance = 1 + random.nextInt(minOf(decodedLength, 2047))
                val length = 3 + random.nextInt(8)
                input.write(0x80 or ((distance shr 5) and 0x3F))
                input.write(((distance and 0x1F) shl 3) or (length - 3))
                decodedLength += length
            }
        }
        val bytes = input.toByteArray()

        assertArrayEquals(referenceDecompress(bytes), PalmDocDecompressor.decompress(bytes))
    }

    /** 改前的参考实现（装箱版本），用于证明新实现输出字节完全一致。 */
    private fun referenceDecompress(input: ByteArray): ByteArray {
        val output = ArrayList<Byte>()
        var index = 0
        while (index < input.size) {
            val value = input[index++].toInt() and 0xFF
            when {
                value == 0 -> output += 0.toByte()
                value in 1..8 -> repeat(value) { output += input[index++] }
                value in 9..0x7F -> output += value.toByte()
                value in 0x80..0xBF -> {
                    val next = input[index++].toInt() and 0xFF
                    val distance = ((value and 0x3F) shl 5) or ((next and 0xF8) shr 3)
                    val copyLength = (next and 0x07) + 3
                    repeat(copyLength) { output += output[output.size - distance] }
                }
                else -> {
                    output += ' '.code.toByte()
                    output += (value xor 0x80).toByte()
                }
            }
        }
        return ByteArray(output.size) { output[it] }
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
