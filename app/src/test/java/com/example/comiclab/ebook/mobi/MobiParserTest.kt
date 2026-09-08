package com.example.comiclab.ebook.mobi

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class MobiParserTest {

    @Test
    fun stripsMultibyteTrailingDataBeforePalmDocDecode() {
        val text = "abc".toByteArray(StandardCharsets.US_ASCII)
        val header = ByteArray(280).apply {
            putUnsignedShort(0, 2)
            putUnsignedInt(4, text.size)
            putUnsignedShort(8, 1)
            writeAscii(16, "MOBI")
            putUnsignedInt(20, 264)
            putUnsignedInt(28, 65001)
            putUnsignedInt(36, 5)
            putUnsignedInt(108, -1)
            putUnsignedShort(242, 1)
        }
        val textRecord = text + byteArrayOf(0)
        val file = File.createTempFile("comiclab-mobi", ".mobi")
        try {
            file.writeBytes(buildPalmDb(listOf(header, textRecord)))

            val parsed = MobiParser().parse(file)

            assertEquals("<p>abc</p>", parsed.book.chapters.single().html)
        } finally {
            file.delete()
        }
    }

    private fun buildPalmDb(records: List<ByteArray>): ByteArray {
        val tableEnd = 78 + records.size * 8
        val offsets = records.runningFold(tableEnd) { offset, record ->
            offset + record.size
        }.dropLast(1)
        val output = ByteArray(tableEnd + records.sumOf(ByteArray::size))
        output.putUnsignedShort(76, records.size)
        offsets.forEachIndexed { index, offset ->
            output.putUnsignedInt(78 + index * 8, offset)
        }
        records.fold(tableEnd) { offset, record ->
            record.copyInto(output, offset)
            offset + record.size
        }
        return output
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.toByteArray(StandardCharsets.US_ASCII).copyInto(this, offset)
    }

    private fun ByteArray.putUnsignedShort(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.putUnsignedInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
