package com.example.comiclab.ebook.mobi

import com.example.comiclab.ebook.checkEbookCancellation
import com.example.comiclab.ebook.EbookContentLimits
import com.example.comiclab.ebook.EbookContentLimitException

object PalmDocDecompressor {

    fun decompress(input: ByteArray, maxOutputBytes: Long = EbookContentLimits.forHeap().maxEntryBytes): ByteArray {
        checkEbookCancellation()
        val output = ByteOutput(input.size, maxOutputBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var index = 0
        var untilCancellationCheck = 4096

        fun readByte(): Int {
            if (index >= input.size) {
                throw MobiParseException(
                    MobiParseError.INVALID_RECORD,
                    "PalmDOC literal run exceeds record boundary"
                )
            }
            return input[index++].toInt() and 0xFF
        }

        while (index < input.size) {
            if (--untilCancellationCheck == 0) {
                checkEbookCancellation()
                untilCancellationCheck = 4096
            }
            val value = readByte()
            when {
                value == 0 -> output.add(0)
                value in 1..8 -> repeat(value) { output.add(readByte().toByte()) }
                value in 9..0x7F -> output.add(value.toByte())
                value in 0x80..0xBF -> {
                    val next = readByte()
                    val distance = ((value and 0x3F) shl 5) or ((next and 0xF8) shr 3)
                    val copyLength = (next and 0x07) + 3
                    if (distance <= 0 || distance > output.size) {
                        throw MobiParseException(
                            MobiParseError.INVALID_RECORD,
                            "PalmDOC back-reference is outside decoded text"
                        )
                    }
                    // 逐字节复制，允许源与目标重叠（distance 小于 copyLength 时）。
                    repeat(copyLength) {
                        output.add(output.get(output.size - distance))
                    }
                }
                else -> {
                    output.add(' '.code.toByte())
                    output.add((value xor 0x80).toByte())
                }
            }
        }

        return output.toByteArray()
    }

    /** 可增长的字节缓冲区，避免对每个字节进行装箱。 */
    private class ByteOutput(initialCapacity: Int, private val limit: Int) {
        private var data = ByteArray(initialCapacity.coerceAtLeast(16).coerceAtMost(limit))
        var size = 0
            private set

        fun add(value: Byte) {
            if (size >= limit) throw EbookContentLimitException()
            if (size == data.size) {
                data = data.copyOf((data.size.toLong() * 2).coerceAtMost(limit.toLong()).toInt())
            }
            data[size++] = value
        }

        fun get(index: Int): Byte = data[index]

        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}
