package com.example.comiclab.ebook.mobi

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Reconstructs the XHTML documents stored by the KF8/AZW3 format.
 *
 * KF8 keeps the readable markup in text records and stores the actual XHTML
 * documents as skeletons plus fragments. Keeping that format-specific work
 * here leaves MobiParser responsible for PalmDB/MOBI metadata and resources.
 */
data class Kf8Content(
    val documents: List<String>,
    val stylesheet: String? = null
)

class Kf8Parser {

    fun parse(
        rawMarkup: ByteArray,
        charset: Charset,
        header: ByteArray,
        headerRecordIndex: Int,
        totalRecordCount: Int,
        readRecord: (Int) -> ByteArray
    ): Kf8Content {
        if (rawMarkup.isEmpty()) {
            return Kf8Content(emptyList())
        }

        val flows = readFlows(
            rawMarkup = rawMarkup,
            header = header,
            headerRecordIndex = headerRecordIndex,
            totalRecordCount = totalRecordCount,
            readRecord = readRecord
        )
        val stylesheet = flows
            .drop(1)
            .map { String(it, charset) }
            .filterNot(::looksLikeImageFlow)
            .filter(::looksLikeStylesheet)
            .joinToString("\n")
            .trim()
            .takeIf(String::isNotBlank)

        val documents = runCatching {
            reconstructDocuments(
                rawMarkup = rawMarkup,
                charset = charset,
                header = header,
                headerRecordIndex = headerRecordIndex,
                totalRecordCount = totalRecordCount,
                readRecord = readRecord
            )
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: flows
                .take(1)
                .map { String(it, charset) }
                .filter(String::isNotBlank)
                .ifEmpty { listOf(String(rawMarkup, charset)) }

        return Kf8Content(
            documents = documents,
            stylesheet = stylesheet
        )
    }

    private fun readFlows(
        rawMarkup: ByteArray,
        header: ByteArray,
        headerRecordIndex: Int,
        totalRecordCount: Int,
        readRecord: (Int) -> ByteArray
    ): List<ByteArray> {
        val flowCount = readOptionalUnsignedInt(header, FDST_FLOW_COUNT_OFFSET)?.toInt() ?: 0
        if (flowCount <= 1) {
            return listOf(rawMarkup)
        }
        val fdstRecordIndex = resolveRecordIndex(
            readOptionalUnsignedInt(header, FDST_RECORD_OFFSET),
            headerRecordIndex,
            totalRecordCount
        ) ?: return listOf(rawMarkup)
        val fdst = readRecord(fdstRecordIndex)
        if (!fdst.startsWith(FDST_MAGIC) || fdst.size < FDST_OFFSETS_OFFSET) {
            return listOf(rawMarkup)
        }

        val fdstSectionCount = readUnsignedInt(fdst, FDST_COUNT_OFFSET).toInt()
        if (fdstSectionCount <= 0 || fdstSectionCount > MAX_FLOW_COUNT ||
            FDST_OFFSETS_OFFSET + fdstSectionCount * 8 > fdst.size
        ) {
            return listOf(rawMarkup)
        }

        val offsets = (0 until fdstSectionCount).map { index ->
            readUnsignedInt(fdst, FDST_OFFSETS_OFFSET + index * 8).toInt()
        } + rawMarkup.size
        if (offsets.firstOrNull() != 0 || offsets.zipWithNext().any { (start, end) ->
                start < 0 || end < start || end > rawMarkup.size
            } || offsets.lastOrNull()?.let { it > rawMarkup.size } == true
        ) {
            return listOf(rawMarkup)
        }

        return offsets.zipWithNext()
            .map { (start, end) -> rawMarkup.copyOfRange(start, end) }
            .ifEmpty { listOf(rawMarkup) }
    }

    private fun reconstructDocuments(
        rawMarkup: ByteArray,
        charset: Charset,
        header: ByteArray,
        headerRecordIndex: Int,
        totalRecordCount: Int,
        readRecord: (Int) -> ByteArray
    ): List<String> {
        val skeletonIndex = parseIndex(
            readOptionalUnsignedInt(header, SKELETON_INDEX_RECORD_OFFSET),
            headerRecordIndex,
            totalRecordCount,
            readRecord,
            charset
        ) ?: return emptyList()
        val fragmentIndex = parseIndex(
            readOptionalUnsignedInt(header, FRAGMENT_INDEX_RECORD_OFFSET),
            headerRecordIndex,
            totalRecordCount,
            readRecord,
            charset
        ) ?: return emptyList()
        if (skeletonIndex.entries.isEmpty() || fragmentIndex.entries.isEmpty()) {
            return emptyList()
        }

        val documents = ArrayList<String>(skeletonIndex.entries.size)
        var fragmentCursor = 0
        for (skeleton in skeletonIndex.entries) {
            val skeletonStart = skeleton.tagValues[TAG_START]?.firstOrNull()
                ?: return emptyList()
            val skeletonLength = skeleton.tagValues[TAG_START]?.getOrNull(1)
                ?: return emptyList()
            val fragmentCount = skeleton.tagValues[TAG_FRAGMENT_COUNT]?.firstOrNull()
                ?: return emptyList()
            if (skeletonStart < 0 || skeletonLength < 0 ||
                skeletonStart > rawMarkup.size - skeletonLength || fragmentCount < 0
            ) {
                return emptyList()
            }

            var assembled = rawMarkup.copyOfRange(
                skeletonStart,
                skeletonStart + skeletonLength
            )
            var fragmentBase = skeletonStart + skeletonLength
            repeat(fragmentCount) {
                val fragment = fragmentIndex.entries.getOrNull(fragmentCursor++)
                    ?: return emptyList()
                val fragmentStart = fragment.tagValues[TAG_START]?.firstOrNull()
                    ?: return emptyList()
                val fragmentLength = fragment.tagValues[TAG_START]?.getOrNull(1)
                    ?: return emptyList()
                val insertionPosition = fragment.text.toIntOrNull()
                    ?: return emptyList()
                if (fragmentStart < 0 || fragmentLength < 0 ||
                    fragmentStart > rawMarkup.size - fragmentLength ||
                    fragmentBase > rawMarkup.size - fragmentLength
                ) {
                    return emptyList()
                }

                val fragmentBytes = rawMarkup.copyOfRange(
                    fragmentBase,
                    fragmentBase + fragmentLength
                )
                fragmentBase += fragmentLength
                val relativePosition = (insertionPosition - skeletonStart)
                    .coerceIn(0, assembled.size)
                assembled = insertBytes(assembled, relativePosition, fragmentBytes)
            }
            documents += String(assembled, charset)
        }

        return documents.filter(String::isNotBlank)
    }

    private fun parseIndex(
        recordValue: Long?,
        headerRecordIndex: Int,
        totalRecordCount: Int,
        readRecord: (Int) -> ByteArray,
        charset: Charset
    ): ParsedIndex? {
        val recordIndex = resolveRecordIndex(
            recordValue,
            headerRecordIndex,
            totalRecordCount
        ) ?: return null
        val mainRecord = readRecord(recordIndex)
        if (!mainRecord.startsWith(INDX_MAGIC) || mainRecord.size < INDX_HEADER_LENGTH) {
            return null
        }

        val indexLength = readUnsignedInt(mainRecord, INDX_LENGTH_OFFSET).toInt()
        val recordCount = readUnsignedInt(mainRecord, INDX_COUNT_OFFSET).toInt()
        if (indexLength < INDX_HEADER_LENGTH || indexLength > mainRecord.size ||
            recordCount < 0 || recordCount > MAX_INDEX_RECORD_COUNT
        ) {
            return null
        }

        val tagxOffset = indexLength
        if (tagxOffset + TAGX_HEADER_LENGTH > mainRecord.size ||
            !mainRecord.startsWith(TAGX_MAGIC, tagxOffset)
        ) {
            return null
        }
        val firstEntryOffset = readUnsignedInt(mainRecord, tagxOffset + TAGX_FIRST_ENTRY_OFFSET).toInt()
        val controlByteCount = readUnsignedInt(
            mainRecord,
            tagxOffset + TAGX_CONTROL_BYTE_COUNT_OFFSET
        ).toInt()
        if (firstEntryOffset < TAGX_HEADER_LENGTH ||
            tagxOffset + firstEntryOffset > mainRecord.size || controlByteCount <= 0
        ) {
            return null
        }

        val definitions = ArrayList<TagDefinition>()
        var definitionOffset = tagxOffset + TAGX_DEFINITION_OFFSET
        val definitionEnd = tagxOffset + firstEntryOffset
        while (definitionOffset + TAG_DEFINITION_LENGTH <= definitionEnd) {
            definitions += TagDefinition(
                tag = mainRecord[definitionOffset].toInt() and 0xFF,
                valuesPerEntry = mainRecord[definitionOffset + 1].toInt() and 0xFF,
                mask = mainRecord[definitionOffset + 2].toInt() and 0xFF,
                endFlag = mainRecord[definitionOffset + 3].toInt() and 0xFF
            )
            definitionOffset += TAG_DEFINITION_LENGTH
        }
        if (definitions.isEmpty()) {
            return null
        }

        val entries = ArrayList<IndexEntry>()
        repeat(recordCount) { extraRecordOffset ->
            val record = readRecord(recordIndex + extraRecordOffset + 1)
            val indexStart = readUnsignedInt(record, INDX_START_OFFSET).toInt()
            val entryCount = readUnsignedInt(record, INDX_COUNT_OFFSET).toInt()
            val positions = readEntryPositions(record, indexStart, entryCount)
            positions.forEachIndexed { positionIndex, entryOffset ->
                parseEntry(
                    record = record,
                    entryOffset = entryOffset,
                    endOffset = positions.getOrNull(positionIndex + 1) ?: indexStart,
                    definitions = definitions,
                    controlByteCount = controlByteCount,
                    charset = charset
                )?.let(entries::add)
            }
        }
        return ParsedIndex(entries)
    }

    private fun readEntryPositions(
        record: ByteArray,
        indexStart: Int,
        entryCount: Int
    ): List<Int> {
        if (indexStart < INDX_HEADER_LENGTH || entryCount <= 0 ||
            entryCount > MAX_ENTRIES_PER_RECORD ||
            indexStart > record.size - 4 - entryCount * 2
        ) {
            return emptyList()
        }

        val positions = ArrayList<Int>()
        var offset = indexStart + 4
        for (entryIndex in 0 until entryCount) {
            val entryOffset = readUnsignedShort(record, offset)
            offset += 2
            // INDX stores the entry offsets in the IDXT table, but the entries
            // themselves are located before IDXT. Requiring entryOffset >=
            // indexStart rejects valid KF8 skeleton/fragment indexes.
            if (entryOffset < INDX_HEADER_LENGTH || entryOffset >= indexStart) {
                return emptyList()
            }
            positions += entryOffset
        }
        if (positions.zipWithNext().any { (start, end) -> start >= end }) {
            return emptyList()
        }
        return positions
    }

    private fun parseEntry(
        record: ByteArray,
        entryOffset: Int,
        endOffset: Int,
        definitions: List<TagDefinition>,
        controlByteCount: Int,
        charset: Charset
    ): IndexEntry? {
        if (entryOffset < 0 || endOffset <= entryOffset || endOffset > record.size) {
            return null
        }
        val textLength = record[entryOffset].toInt() and 0xFF
        val textStart = entryOffset + 1
        val controlStart = textStart + textLength
        val controlEnd = controlStart + controlByteCount
        if (controlEnd > endOffset) {
            return null
        }

        val text = String(
            record.copyOfRange(textStart, controlStart),
            charset
        ).trim('\u0000', ' ', '\t', '\r', '\n')
        var dataOffset = controlEnd
        var controlByteIndex = 0
        val tagSpecs = ArrayList<TagSpec>()
        for (definition in definitions) {
            if (controlByteIndex >= controlByteCount) {
                return null
            }
            if (definition.endFlag != 0) {
                controlByteIndex++
                continue
            }
            val controlByte = record[controlStart + controlByteIndex].toInt() and 0xFF
            val maskedValue = controlByte and definition.mask
            if (maskedValue != 0) {
                val maskBitCount = Integer.bitCount(definition.mask)
                if (maskBitCount > 1 &&
                    maskedValue == definition.mask
                ) {
                    val (consumed, byteLength) = readVwi(record, dataOffset)
                    if (dataOffset + consumed > endOffset) {
                        return null
                    }
                    dataOffset += consumed
                    val end = dataOffset + byteLength
                    if (byteLength < 0 || end > endOffset) {
                        return null
                    }
                    tagSpecs += TagSpec(
                        tag = definition.tag,
                        valueCount = null,
                        valueBytes = byteLength,
                        valuesPerEntry = definition.valuesPerEntry
                    )
                } else {
                    val valueCount = if (maskBitCount == 1) {
                        1
                    } else {
                        maskedValue ushr Integer.numberOfTrailingZeros(definition.mask)
                    }
                    tagSpecs += TagSpec(
                        tag = definition.tag,
                        valueCount = valueCount.coerceAtMost(MAX_TAG_VALUES),
                        valueBytes = null,
                        valuesPerEntry = definition.valuesPerEntry
                    )
                }
            }
        }

        val tagValues = linkedMapOf<Int, List<Int>>()
        for (spec in tagSpecs) {
            val values = ArrayList<Int>()
            if (spec.valueCount != null) {
                repeat(spec.valueCount) {
                    repeat(spec.valuesPerEntry.coerceAtMost(MAX_TAG_VALUES)) {
                        val (consumed, value) = readVwi(record, dataOffset)
                        if (dataOffset + consumed > endOffset) {
                            return null
                        }
                        dataOffset += consumed
                        values += value
                    }
                }
            } else {
                val valueBytes = spec.valueBytes ?: return null
                val end = dataOffset + valueBytes
                if (valueBytes < 0 || end > endOffset) {
                    return null
                }
                while (dataOffset < end) {
                    val (consumed, value) = readVwi(record, dataOffset)
                    if (dataOffset + consumed > end) {
                        return null
                    }
                    dataOffset += consumed
                    values += value
                    if (values.size >= MAX_TAG_VALUES) {
                        break
                    }
                }
                dataOffset = end
            }
            if (values.isNotEmpty()) {
                tagValues[spec.tag] = values
            }
        }
        return IndexEntry(text = text, tagValues = tagValues)
    }

    private fun readVwi(record: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset !in record.indices) {
            throw IllegalArgumentException("KF8 variable-width integer is out of bounds")
        }
        var value = 0
        var consumed = 0
        while (offset + consumed < record.size && consumed < MAX_VWI_BYTES) {
            val current = record[offset + consumed].toInt() and 0xFF
            value = (value shl 7) or (current and 0x7F)
            consumed++
            if (current and 0x80 != 0) {
                return consumed to value
            }
        }
        throw IllegalArgumentException("KF8 variable-width integer is invalid")
    }

    private fun insertBytes(source: ByteArray, position: Int, insertion: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(source.size + insertion.size)
        output.write(source, 0, position)
        output.write(insertion)
        output.write(source, position, source.size - position)
        return output.toByteArray()
    }

    private fun resolveRecordIndex(
        value: Long?,
        headerRecordIndex: Int,
        totalRecordCount: Int
    ): Int? {
        if (value == null || value == UINT32_MAX || value > Int.MAX_VALUE) {
            return null
        }
        val relative = headerRecordIndex + value.toInt()
        if (relative in 0 until totalRecordCount) {
            return relative
        }
        return value.toInt().takeIf { it in 0 until totalRecordCount }
    }

    private fun looksLikeImageFlow(flow: String): Boolean {
        return flow.trimStart().startsWith("<svg", ignoreCase = true) ||
            flow.trimStart().startsWith("<?xml", ignoreCase = true) &&
            flow.contains("<svg", ignoreCase = true)
    }

    private fun looksLikeStylesheet(flow: String): Boolean {
        return flow.contains('{') && flow.contains('}') &&
            !flow.contains('\u0000')
    }

    private fun readUnsignedShort(source: ByteArray, offset: Int): Int {
        checkRange(source, offset, 2)
        return ((source[offset].toInt() and 0xFF) shl 8) or
            (source[offset + 1].toInt() and 0xFF)
    }

    private fun readUnsignedInt(source: ByteArray, offset: Int): Long {
        checkRange(source, offset, 4)
        return ((source[offset].toLong() and 0xFF) shl 24) or
            ((source[offset + 1].toLong() and 0xFF) shl 16) or
            ((source[offset + 2].toLong() and 0xFF) shl 8) or
            (source[offset + 3].toLong() and 0xFF)
    }

    private fun readOptionalUnsignedInt(source: ByteArray, offset: Int): Long? {
        return if (offset + 4 <= source.size) readUnsignedInt(source, offset) else null
    }

    private fun checkRange(source: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > source.size - length) {
            throw IllegalArgumentException("KF8 field is out of bounds")
        }
    }

    private data class ParsedIndex(
        val entries: List<IndexEntry>
    )

    private data class IndexEntry(
        val text: String,
        val tagValues: Map<Int, List<Int>>
    )

    private data class TagDefinition(
        val tag: Int,
        val valuesPerEntry: Int,
        val mask: Int,
        val endFlag: Int
    )

    private data class TagSpec(
        val tag: Int,
        val valueCount: Int?,
        val valueBytes: Int?,
        val valuesPerEntry: Int
    )

    private companion object {
        const val UINT32_MAX = 0xFFFFFFFFL

        const val FDST_RECORD_OFFSET = 192
        const val FDST_FLOW_COUNT_OFFSET = 196
        const val FRAGMENT_INDEX_RECORD_OFFSET = 248
        const val SKELETON_INDEX_RECORD_OFFSET = 252

        const val INDX_HEADER_LENGTH = 0x54
        const val INDX_LENGTH_OFFSET = 4
        const val INDX_START_OFFSET = 20
        const val INDX_COUNT_OFFSET = 24

        const val TAGX_HEADER_LENGTH = 12
        const val TAGX_FIRST_ENTRY_OFFSET = 4
        const val TAGX_CONTROL_BYTE_COUNT_OFFSET = 8
        const val TAGX_DEFINITION_OFFSET = 12
        const val TAG_DEFINITION_LENGTH = 4

        const val FDST_COUNT_OFFSET = 8
        const val FDST_OFFSETS_OFFSET = 12

        const val TAG_FRAGMENT_COUNT = 1
        const val TAG_START = 6
        const val MAX_FLOW_COUNT = 4096
        const val MAX_INDEX_RECORD_COUNT = 4096
        const val MAX_ENTRIES_PER_RECORD = 65535
        const val MAX_TAG_VALUES = 1024
        const val MAX_VWI_BYTES = 5

        val FDST_MAGIC = "FDST".toByteArray(StandardCharsets.US_ASCII)
        val INDX_MAGIC = "INDX".toByteArray(StandardCharsets.US_ASCII)
        val TAGX_MAGIC = "TAGX".toByteArray(StandardCharsets.US_ASCII)

        fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean {
            return offset >= 0 && offset + prefix.size <= size &&
                prefix.indices.all { this[offset + it] == prefix[it] }
        }
    }
}
