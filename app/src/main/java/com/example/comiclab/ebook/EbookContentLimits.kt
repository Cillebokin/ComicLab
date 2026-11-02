package com.example.comiclab.ebook

import java.io.FilterInputStream
import java.io.InputStream
import java.io.Writer

/** 展开后的内容预算，不能用压缩文件大小代替。 */
data class EbookContentLimits(
    val maxBookChars: Long,
    val maxEntryBytes: Long,
    val maxHtmlChars: Long,
    val maxHtmlTags: Long,
    val maxMetadataBytes: Long = minOf(maxEntryBytes, 2L * 1024 * 1024),
    val maxChapterHtmlChars: Long = minOf(maxHtmlChars, 8L * 1024 * 1024),
    val maxChapterHtmlTags: Long = minOf(maxHtmlTags, 50_000)
) {
    init { require(listOf(maxBookChars, maxEntryBytes, maxHtmlChars, maxHtmlTags, maxMetadataBytes,
        maxChapterHtmlChars, maxChapterHtmlTags).all { it > 0 }) }
    fun checkBookChars(count: Long) { if (count > maxBookChars) throw EbookContentLimitException() }
    fun checkEntryBytes(count: Long) { if (count > maxEntryBytes) throw EbookContentLimitException() }

    companion object {
        fun forHeap(heapBytes: Long = Runtime.getRuntime().maxMemory()): EbookContentLimits {
            val chars = (heapBytes / 32).coerceIn(256L * 1024, 16L * 1024 * 1024)
            val bytes = (heapBytes / 16).coerceIn(1024L * 1024, 32L * 1024 * 1024)
            return EbookContentLimits(chars, bytes, chars * 2 + 64 * 1024,
                (heapBytes / 1024).coerceIn(10_000, 750_000),
                maxChapterHtmlChars = (heapBytes / 64).coerceIn(256L * 1024, 8L * 1024 * 1024))
        }
    }
}

class EbookContentLimitException : RuntimeException("Ebook expanded content exceeds the preparation budget")

internal fun InputStream.limitEbookInput(maxBytes: Long): InputStream = object : FilterInputStream(this) {
    private var count = 0L
    private fun consumed(size: Long) {
        checkEbookCancellation()
        count += size
        if (count > maxBytes) throw EbookContentLimitException()
    }
    override fun read(): Int = `in`.read().also { if (it >= 0) consumed(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        `in`.read(buffer, offset, minOf(length.toLong(), (maxBytes - count + 1).coerceAtLeast(1)).toInt())
            .also { if (it > 0) consumed(it.toLong()) }
    override fun skip(size: Long): Long = `in`.skip(minOf(size, (maxBytes - count + 1).coerceAtLeast(1)))
        .also { if (it > 0) consumed(it) }
}

internal class EbookLimitWriter(private val target: Writer, private val limits: EbookContentLimits) : Writer() {
    var charsWritten = 0L
        private set
    var tagsWritten = 0L
        private set
    private fun account(length: Int, tags: Int) {
        checkEbookCancellation()
        if (length.toLong() > limits.maxHtmlChars - charsWritten || tags.toLong() > limits.maxHtmlTags - tagsWritten) {
            throw EbookContentLimitException()
        }
        charsWritten += length
        tagsWritten += tags
    }
    override fun write(value: String, offset: Int, length: Int) {
        var tags = 0
        for (i in offset until offset + length) if (value[i] == '<') tags++
        account(length, tags)
        target.write(value, offset, length)
    }
    override fun write(value: CharArray, offset: Int, length: Int) {
        var tags = 0
        for (i in offset until offset + length) if (value[i] == '<') tags++
        account(length, tags)
        target.write(value, offset, length)
    }
    override fun write(value: Int) {
        account(1, if (value == '<'.code) 1 else 0)
        target.write(value)
    }
    override fun flush() = target.flush()
    override fun close() = target.close()
}
