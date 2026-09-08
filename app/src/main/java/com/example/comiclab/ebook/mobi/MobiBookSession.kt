package com.example.comiclab.ebook.mobi

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import com.example.comiclab.ebook.EbookSession

class MobiBookSession private constructor(
    override val book: MobiBook,
    private val resourceRecords: Map<String, ResourceRecord>,
    private val sourceFile: File
) : EbookSession {

    @Volatile
    private var closed = false

    override fun openResource(resourceId: String): InputStream? {
        if (closed) {
            return null
        }

        val record = resourceRecords[resourceId] ?: return null
        if (!sourceFile.isFile || record.length <= 0) {
            return null
        }

        return runCatching {
            val input = FileInputStream(sourceFile)
            var remainingOffset = record.offset
            while (remainingOffset > 0L) {
                val skipped = input.skip(remainingOffset)
                if (skipped > 0L) {
                    remainingOffset -= skipped
                } else if (input.read() >= 0) {
                    remainingOffset--
                } else {
                    input.close()
                    return null
                }
            }
            LimitedInputStream(input, record.length.toLong())
        }.getOrNull()
    }

    fun isClosed(): Boolean = closed

    override fun close() {
        closed = true
    }

    private class LimitedInputStream(
        input: InputStream,
        private var remaining: Long
    ) : FilterInputStream(input) {

        override fun read(): Int {
            if (remaining <= 0L) {
                return -1
            }
            val value = super.read()
            if (value >= 0) {
                remaining--
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) {
                return -1
            }
            val boundedLength = length.coerceAtMost(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val count = super.read(buffer, offset, boundedLength)
            if (count > 0) {
                remaining -= count
            }
            return count
        }

        override fun skip(length: Long): Long {
            if (remaining <= 0L) {
                return 0L
            }
            val skipped = super.skip(length.coerceAtMost(remaining))
            remaining -= skipped
            return skipped
        }
    }

    companion object {
        fun open(file: File): MobiBookSession {
            val parsed = MobiParser().parse(file)
            return MobiBookSession(
                book = parsed.book,
                resourceRecords = parsed.resourceRecords,
                sourceFile = parsed.sourceFile
            )
        }
    }
}
