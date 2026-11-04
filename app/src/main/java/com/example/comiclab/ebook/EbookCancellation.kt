package com.example.comiclab.ebook

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Reader
import java.util.concurrent.CancellationException

internal fun checkEbookCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Ebook preparation cancelled")
}

internal fun InputStream.readEbookBytes(maxBytes: Long = EbookContentLimits.forHeap().maxEntryBytes): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkEbookCancellation()
        val count = read(buffer)
        if (count < 0) break
        if (count.toLong() > maxBytes - output.size()) throw EbookContentLimitException()
        output.write(buffer, 0, count)
    }
    checkEbookCancellation()
    return output.toByteArray()
}

internal fun Reader.readEbookText(maxChars: Long = EbookContentLimits.forHeap().maxBookChars): String {
    val output = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        checkEbookCancellation()
        val count = read(buffer)
        if (count < 0) break
        if (count.toLong() > maxChars - output.length) throw EbookContentLimitException()
        output.append(buffer, 0, count)
    }
    checkEbookCancellation()
    return output.toString()
}
