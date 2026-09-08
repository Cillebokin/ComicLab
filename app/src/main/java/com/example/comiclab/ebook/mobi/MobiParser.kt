package com.example.comiclab.ebook.mobi

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.math.min

class MobiParser {

    fun parse(file: File): ParsedMobi {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) {
            throw MobiParseException(MobiParseError.INVALID_FILE, "MOBI file is not readable")
        }

        return try {
            RandomAccessFile(file, "r").use { randomAccessFile ->
                parseFile(file, randomAccessFile)
            }
        } catch (error: MobiParseException) {
            throw error
        } catch (error: IOException) {
            throw MobiParseException(
                MobiParseError.TRUNCATED_FILE,
                "Unable to read MOBI records",
                error
            )
        } catch (error: RuntimeException) {
            throw MobiParseException(
                MobiParseError.INVALID_FILE,
                "Unable to parse MOBI file",
                error
            )
        }
    }

    private fun parseFile(file: File, randomAccessFile: RandomAccessFile): ParsedMobi {
        val records = readRecordTable(randomAccessFile)
        val headerRecord = readRecord(randomAccessFile, records.first())
        if (headerRecord.size < MOBI_HEADER_OFFSET + MOBI_MIN_HEADER_LENGTH) {
            throw MobiParseException(
                MobiParseError.TRUNCATED_FILE,
                "MOBI header record is too short"
            )
        }

        val compression = readUnsignedShort(headerRecord, PALMDOC_COMPRESSION_OFFSET)
        val textLength = readUnsignedInt(headerRecord, PALMDOC_TEXT_LENGTH_OFFSET)
        val textRecordCount = readUnsignedShort(headerRecord, PALMDOC_RECORD_COUNT_OFFSET)
        val encryptionType = readUnsignedShort(headerRecord, PALMDOC_ENCRYPTION_OFFSET)

        if (!headerRecord.copyOfRange(MOBI_HEADER_OFFSET, MOBI_HEADER_OFFSET + 4)
                .contentEquals(MOBI_MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            throw MobiParseException(
                MobiParseError.INVALID_MOBI_HEADER,
                "MOBI magic is missing"
            )
        }

        val mobiHeaderLength = readUnsignedInt(headerRecord, MOBI_HEADER_LENGTH_OFFSET)
        if (mobiHeaderLength < MOBI_MIN_HEADER_LENGTH ||
            MOBI_HEADER_OFFSET + mobiHeaderLength > headerRecord.size
        ) {
            throw MobiParseException(
                MobiParseError.INVALID_MOBI_HEADER,
                "MOBI header length is invalid"
            )
        }

        val mobiFormatVersion = readUnsignedInt(headerRecord, MOBI_FORMAT_VERSION_OFFSET)
        if (mobiFormatVersion >= KF8_FORMAT_VERSION) {
            throw MobiParseException(
                MobiParseError.UNSUPPORTED_FORMAT,
                "KF8/AZW3 MOBI files are not supported"
            )
        }

        val drmOffset = readOptionalUnsignedInt(headerRecord, MOBI_DRM_OFFSET)
        if (encryptionType != 0 || (drmOffset != null && drmOffset != 0L && drmOffset != UINT32_MAX)) {
            throw MobiParseException(
                MobiParseError.DRM_PROTECTED,
                "DRM protected MOBI files are not supported"
            )
        }

        if (compression != COMPRESSION_NONE && compression != COMPRESSION_PALMDOC) {
            throw MobiParseException(
                MobiParseError.UNSUPPORTED_COMPRESSION,
                "MOBI compression $compression is not supported"
            )
        }

        val firstImageRecord = readOptionalUnsignedInt(headerRecord, MOBI_FIRST_IMAGE_OFFSET)
            ?.takeUnless { it == UINT32_MAX }
            ?.toInt()
        val firstContentRecord = readOptionalUnsignedShort(
            headerRecord,
            MOBI_FIRST_CONTENT_RECORD_OFFSET
        )?.takeIf { it > 0 } ?: 1
        val encoding = readUnsignedInt(headerRecord, MOBI_ENCODING_OFFSET)
        val charset = mobiCharset(encoding)
        val fullNameOffset = readUnsignedInt(headerRecord, MOBI_FULL_NAME_OFFSET).toInt()
        val fullNameLength = readUnsignedInt(headerRecord, MOBI_FULL_NAME_LENGTH_OFFSET).toInt()

        val exth = readExthMetadata(headerRecord, mobiHeaderLength.toInt(), charset)
        val fallbackTitle = readMetadataString(
            headerRecord,
            fullNameOffset,
            fullNameLength,
            charset
        )
        val title = exth.title?.takeIf { it.isNotBlank() }
            ?: fallbackTitle?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension.ifBlank { "未命名电子书" }
        val author = exth.author?.takeIf { it.isNotBlank() }

        val textRecordsStart = firstContentRecord
        val textRecordsEnd = textRecordsStart + textRecordCount
        if (textRecordsEnd > records.size) {
            throw MobiParseException(
                MobiParseError.TRUNCATED_FILE,
                "MOBI text records are incomplete"
            )
        }

        val textBytes = ByteArrayOutputStream(
            min(textLength.toIntOrMax(), MAX_INITIAL_TEXT_CAPACITY)
        )
        for (recordIndex in textRecordsStart until textRecordsEnd) {
            val compressedText = readRecord(randomAccessFile, records[recordIndex])
            val decodedText = when (compression) {
                COMPRESSION_NONE -> compressedText
                COMPRESSION_PALMDOC -> PalmDocDecompressor.decompress(compressedText)
                else -> error("validated compression type")
            }
            textBytes.write(decodedText)
        }

        val allTextBytes = textBytes.toByteArray()
        val boundedTextBytes = if (textLength in 1..allTextBytes.size.toLong()) {
            allTextBytes.copyOf(textLength.toInt())
        } else {
            allTextBytes
        }
        val content = decodeText(boundedTextBytes, charset)
        if (content.isBlank()) {
            throw MobiParseException(MobiParseError.EMPTY_BOOK, "MOBI does not contain readable text")
        }

        val resourcesResult = readResources(
            randomAccessFile = randomAccessFile,
            records = records,
            firstImageRecord = firstImageRecord,
            coverOffset = exth.coverOffset
        )
        val chapters = extractChapters(content, title)
        val book = MobiBook(
            title = title,
            author = author,
            chapters = chapters,
            resources = resourcesResult.resources,
            coverResourceId = resourcesResult.coverResourceId
        )

        return ParsedMobi(
            book = book,
            resourceRecords = resourcesResult.records,
            sourceFile = file
        )
    }

    private fun readRecordTable(randomAccessFile: RandomAccessFile): List<Record> {
        val fileLength = randomAccessFile.length()
        if (fileLength < PALMDB_RECORD_TABLE_OFFSET) {
            throw MobiParseException(MobiParseError.TRUNCATED_FILE, "PalmDB header is incomplete")
        }

        randomAccessFile.seek(PALMDB_RECORD_COUNT_OFFSET.toLong())
        val recordCount = randomAccessFile.readUnsignedShort()
        val tableEnd = PALMDB_RECORD_TABLE_OFFSET.toLong() + recordCount * PALMDB_RECORD_ENTRY_SIZE
        if (recordCount <= 0 || tableEnd > fileLength) {
            throw MobiParseException(MobiParseError.INVALID_RECORD, "PalmDB record table is invalid")
        }

        val offsets = ArrayList<Long>(recordCount)
        randomAccessFile.seek(PALMDB_RECORD_TABLE_OFFSET.toLong())
        repeat(recordCount) {
            val offset = randomAccessFile.readInt().toLong() and UINT32_MAX
            randomAccessFile.skipBytes(4)
            if (offset < tableEnd || offset >= fileLength) {
                throw MobiParseException(MobiParseError.INVALID_RECORD, "PalmDB record offset is invalid")
            }
            if (offsets.lastOrNull()?.let { offset <= it } == true) {
                throw MobiParseException(MobiParseError.INVALID_RECORD, "PalmDB record offsets are not ordered")
            }
            offsets += offset
        }

        return offsets.mapIndexed { index, offset ->
            val end = offsets.getOrNull(index + 1) ?: fileLength
            val length = end - offset
            if (length <= 0L || length > Int.MAX_VALUE) {
                throw MobiParseException(MobiParseError.INVALID_RECORD, "PalmDB record length is invalid")
            }
            Record(index = index, offset = offset, length = length.toInt())
        }
    }

    private fun readRecord(randomAccessFile: RandomAccessFile, record: Record): ByteArray {
        val bytes = ByteArray(record.length)
        randomAccessFile.seek(record.offset)
        randomAccessFile.readFully(bytes)
        return bytes
    }

    private fun readExthMetadata(
        headerRecord: ByteArray,
        mobiHeaderLength: Int,
        charset: Charset
    ): ExthMetadata {
        val exthFlags = readOptionalUnsignedInt(headerRecord, MOBI_EXTH_FLAGS_OFFSET) ?: return ExthMetadata()
        if (exthFlags and EXTH_PRESENT_FLAG == 0L) {
            return ExthMetadata()
        }

        val start = MOBI_HEADER_OFFSET + mobiHeaderLength
        if (start + EXTH_HEADER_LENGTH > headerRecord.size ||
            !headerRecord.copyOfRange(start, start + 4)
                .contentEquals(EXTH_MAGIC.toByteArray(StandardCharsets.US_ASCII))
        ) {
            return ExthMetadata()
        }

        val count = readUnsignedInt(headerRecord, start + 8).toInt()
            .coerceAtMost((headerRecord.size - (start + EXTH_HEADER_LENGTH)) / 8)
        var offset = start + EXTH_HEADER_LENGTH
        var title: String? = null
        var author: String? = null
        var coverOffset: Int? = null

        repeat(count) {
            if (offset + 8 > headerRecord.size) {
                return@repeat
            }
            val type = readUnsignedInt(headerRecord, offset).toInt()
            val length = readUnsignedInt(headerRecord, offset + 4).toInt()
            if (length < 8 || offset + length > headerRecord.size) {
                return@repeat
            }

            val value = headerRecord.copyOfRange(offset + 8, offset + length)
            when (type) {
                EXTH_AUTHOR -> author = decodeMetadataBytes(value, charset)
                EXTH_TITLE, EXTH_UPDATED_TITLE -> title = decodeMetadataBytes(value, charset)
                EXTH_COVER_OFFSET -> coverOffset = value.takeIf { it.size >= 4 }?.let { readUnsignedInt(it, 0).toInt() }
            }
            offset += length
        }

        return ExthMetadata(title = title, author = author, coverOffset = coverOffset)
    }

    private fun readMetadataString(
        source: ByteArray,
        offset: Int,
        length: Int,
        charset: Charset
    ): String? {
        if (offset < 0 || length <= 0 || offset > source.size || length > source.size - offset) {
            return null
        }
        return decodeMetadataBytes(source.copyOfRange(offset, offset + length), charset)
    }

    private fun readResources(
        randomAccessFile: RandomAccessFile,
        records: List<Record>,
        firstImageRecord: Int?,
        coverOffset: Int?
    ): ResourcesResult {
        if (firstImageRecord == null || firstImageRecord !in records.indices) {
            return ResourcesResult(emptyList(), emptyMap(), null)
        }

        val resources = mutableListOf<MobiResource>()
        val resourceRecords = linkedMapOf<String, ResourceRecord>()
        var coverResourceId: String? = null

        for (recordIndex in firstImageRecord until records.size) {
            val record = records[recordIndex]
            val prefix = readPrefix(randomAccessFile, record, IMAGE_PREFIX_LENGTH)
            val mimeType = imageMimeType(prefix) ?: continue
            val resourceId = "image-${(resources.size + 1).toString().padStart(4, '0')}"
            resources += MobiResource(resourceId, recordIndex, mimeType)
            resourceRecords[resourceId] = ResourceRecord(
                id = resourceId,
                recordIndex = recordIndex,
                mimeType = mimeType,
                offset = record.offset,
                length = record.length
            )
            if (coverOffset != null && recordIndex == firstImageRecord + coverOffset) {
                coverResourceId = resourceId
            }
        }

        return ResourcesResult(resources, resourceRecords, coverResourceId)
    }

    private fun readPrefix(
        randomAccessFile: RandomAccessFile,
        record: Record,
        maxLength: Int
    ): ByteArray {
        val length = min(record.length, maxLength)
        val bytes = ByteArray(length)
        randomAccessFile.seek(record.offset)
        randomAccessFile.readFully(bytes)
        return bytes
    }

    private fun extractChapters(content: String, bookTitle: String): List<MobiChapter> {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val sections = normalized
            .split(PAGE_BREAK_REGEX)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .ifEmpty { listOf(normalized.trim()) }

        return sections.mapIndexed { index, section ->
            val heading = HEADING_REGEX.find(section)?.groupValues?.getOrNull(1)
                ?.replace(TAG_REGEX, "")
                ?.replace(ENTITY_REGEX, ::decodeEntity)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            MobiChapter(
                index = index,
                title = heading ?: if (sections.size == 1) bookTitle else "第 ${index + 1} 章",
                html = asHtml(section)
            )
        }
    }

    private fun asHtml(content: String): String {
        if (TAG_DETECTOR.containsMatchIn(content)) {
            return content
        }
        return "<p>${escapeHtml(content).replace("\n", "<br>")}</p>"
    }

    private fun decodeText(bytes: ByteArray, charset: Charset): String = String(bytes, charset)

    private fun decodeMetadataBytes(bytes: ByteArray, charset: Charset): String {
        return String(bytes, charset).trim('\u0000', ' ', '\t', '\r', '\n')
    }

    private fun mobiCharset(encoding: Long): Charset {
        return when (encoding.toInt()) {
            1252 -> Charset.forName("windows-1252")
            65001, 0 -> StandardCharsets.UTF_8
            else -> StandardCharsets.UTF_8
        }
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

    private fun readOptionalUnsignedShort(source: ByteArray, offset: Int): Int? {
        return if (offset + 2 <= source.size) readUnsignedShort(source, offset) else null
    }

    private fun checkRange(source: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > source.size - length) {
            throw MobiParseException(MobiParseError.INVALID_MOBI_HEADER, "MOBI header field is out of bounds")
        }
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun decodeEntity(match: MatchResult): String {
        return when (match.value.lowercase()) {
            "&amp;" -> "&"
            "&lt;" -> "<"
            "&gt;" -> ">"
            "&quot;" -> "\""
            "&#39;", "&apos;" -> "'"
            else -> match.value
        }
    }

    private fun Long.toIntOrMax(): Int {
        return if (this > Int.MAX_VALUE) Int.MAX_VALUE else toInt()
    }

    private data class Record(
        val index: Int,
        val offset: Long,
        val length: Int
    )

    private data class ExthMetadata(
        val title: String? = null,
        val author: String? = null,
        val coverOffset: Int? = null
    )

    private data class ResourcesResult(
        val resources: List<MobiResource>,
        val records: Map<String, ResourceRecord>,
        val coverResourceId: String?
    )

    private companion object {
        const val PALMDB_RECORD_COUNT_OFFSET = 76
        const val PALMDB_RECORD_TABLE_OFFSET = 78
        const val PALMDB_RECORD_ENTRY_SIZE = 8L

        const val PALMDOC_COMPRESSION_OFFSET = 0
        const val PALMDOC_TEXT_LENGTH_OFFSET = 4
        const val PALMDOC_RECORD_COUNT_OFFSET = 8
        const val PALMDOC_ENCRYPTION_OFFSET = 12

        const val MOBI_HEADER_OFFSET = 16
        const val MOBI_HEADER_LENGTH_OFFSET = MOBI_HEADER_OFFSET + 4
        const val MOBI_ENCODING_OFFSET = MOBI_HEADER_OFFSET + 12
        const val MOBI_FORMAT_VERSION_OFFSET = MOBI_HEADER_OFFSET + 20
        const val MOBI_FULL_NAME_OFFSET = MOBI_HEADER_OFFSET + 68
        const val MOBI_FULL_NAME_LENGTH_OFFSET = MOBI_HEADER_OFFSET + 72
        const val MOBI_FIRST_IMAGE_OFFSET = MOBI_HEADER_OFFSET + 92
        const val MOBI_EXTH_FLAGS_OFFSET = MOBI_HEADER_OFFSET + 112
        const val MOBI_DRM_OFFSET = MOBI_HEADER_OFFSET + 152
        const val MOBI_FIRST_CONTENT_RECORD_OFFSET = 192

        const val MOBI_MIN_HEADER_LENGTH = 228L
        const val KF8_FORMAT_VERSION = 8L
        const val EXTH_PRESENT_FLAG = 0x40L
        const val EXTH_HEADER_LENGTH = 12
        const val EXTH_AUTHOR = 100
        const val EXTH_COVER_OFFSET = 201
        const val EXTH_TITLE = 99
        const val EXTH_UPDATED_TITLE = 503

        const val COMPRESSION_NONE = 1
        const val COMPRESSION_PALMDOC = 2
        const val UINT32_MAX = 0xFFFFFFFFL
        const val IMAGE_PREFIX_LENGTH = 12
        const val MAX_INITIAL_TEXT_CAPACITY = 4 * 1024 * 1024

        const val MOBI_MAGIC = "MOBI"
        const val EXTH_MAGIC = "EXTH"
        val PAGE_BREAK_REGEX = Regex("(?is)<mbp:pagebreak\\s*/?>|<pagebreak\\s*/?>")
        val HEADING_REGEX = Regex("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>")
        val TAG_REGEX = Regex("(?is)<[^>]+>")
        val ENTITY_REGEX = Regex("(?i)&(?:amp|lt|gt|quot|apos);|&#39;")
        val TAG_DETECTOR = Regex("(?is)<[a-z][^>]*>")
        val IMAGE_PREFIX_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val IMAGE_PREFIX_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val IMAGE_PREFIX_GIF = byteArrayOf(0x47, 0x49, 0x46, 0x38)
        val IMAGE_PREFIX_WEBP = byteArrayOf(0x52, 0x49, 0x46, 0x46)

        fun imageMimeType(prefix: ByteArray): String? {
            return when {
                prefix.startsWith(IMAGE_PREFIX_JPEG) -> "image/jpeg"
                prefix.startsWith(IMAGE_PREFIX_PNG) -> "image/png"
                prefix.startsWith(IMAGE_PREFIX_GIF) -> "image/gif"
                prefix.startsWith(IMAGE_PREFIX_WEBP) && prefix.size >= 12 &&
                    prefix.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50)) ->
                    "image/webp"
                else -> null
            }
        }

        fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            return size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
        }
    }
}
