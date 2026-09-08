package com.example.comiclab.ebook.mobi

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertTrue
import org.junit.Test

class Kf8ParserTest {

    @Test
    fun reconstructsBodyWhenIndexEntryOffsetsPrecedeIdxt() {
        val charset = StandardCharsets.UTF_8
        val skeleton = "<html><body><h1>Chapter</h1></body></html>"
        val fragment = "<p>正文内容</p>"
        val rawMarkup = (skeleton + fragment).toByteArray(charset)
        val records = buildList {
            add(ByteArray(256).also { header ->
                putUnsignedInt(header, 248, 3)
                putUnsignedInt(header, 252, 1)
            })
            add(buildMainIndex(definitions = listOf(1 to 1, 6 to 2)))
            add(buildExtraIndex(
                text = "s",
                controlByte = 0x03,
                values = listOf(1, 0, skeleton.toByteArray(charset).size)
            ))
            add(buildMainIndex(definitions = listOf(6 to 1)))
            add(buildExtraIndex(
                text = skeleton.toByteArray(charset).size.toString(),
                controlByte = 0x01,
                values = listOf(0, fragment.toByteArray(charset).size)
            ))
        }

        val content = Kf8Parser().parse(
            rawMarkup = rawMarkup,
            charset = charset,
            header = records.first(),
            headerRecordIndex = 0,
            totalRecordCount = records.size,
            readRecord = records::get
        )

        assertTrue(content.documents.single().contains(fragment))
    }

    private fun buildMainIndex(definitions: List<Pair<Int, Int>>): ByteArray {
        val record = ByteArray(128)
        record.writeAscii(0, "INDX")
        putUnsignedInt(record, 4, 0x54)
        putUnsignedInt(record, 24, 1)
        record.writeAscii(0x54, "TAGX")
        putUnsignedInt(record, 0x58, 12 + definitions.size * 4)
        putUnsignedInt(record, 0x5C, 1)
        definitions.forEachIndexed { index, (tag, mask) ->
            val offset = 0x60 + index * 4
            record[offset] = tag.toByte()
            record[offset + 1] = if (tag == 6) 2 else 1
            record[offset + 2] = mask.toByte()
            record[offset + 3] = 0
        }
        return record
    }

    private fun buildExtraIndex(text: String, controlByte: Int, values: List<Int>): ByteArray {
        val record = ByteArray(128)
        record.writeAscii(0, "INDX")
        putUnsignedInt(record, 20, 0x60)
        putUnsignedInt(record, 24, 1)
        val entryOffset = 0x54
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        record[entryOffset] = textBytes.size.toByte()
        textBytes.copyInto(record, entryOffset + 1)
        record[entryOffset + 1 + textBytes.size] = controlByte.toByte()
        values.forEachIndexed { index, value ->
            record[entryOffset + 2 + textBytes.size + index] = (value or 0x80).toByte()
        }
        putUnsignedShort(record, 0x64, entryOffset)
        return record
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.toByteArray(StandardCharsets.US_ASCII).copyInto(this, offset)
    }

    private fun putUnsignedShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putUnsignedInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
