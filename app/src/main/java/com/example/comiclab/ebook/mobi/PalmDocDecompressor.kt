package com.example.comiclab.ebook.mobi

object PalmDocDecompressor {

    fun decompress(input: ByteArray): ByteArray {
        val output = ArrayList<Byte>(input.size)
        var index = 0

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
            val value = readByte()
            when {
                value == 0 -> output += 0.toByte()
                value in 1..8 -> repeat(value) { output += readByte().toByte() }
                value in 9..0x7F -> output += value.toByte()
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
                    repeat(copyLength) {
                        output += output[output.size - distance]
                    }
                }
                else -> {
                    output += ' '.code.toByte()
                    output += (value xor 0x80).toByte()
                }
            }
        }

        return ByteArray(output.size) { output[it] }
    }
}
