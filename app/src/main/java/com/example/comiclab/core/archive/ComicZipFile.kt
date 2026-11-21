package com.example.comiclab.core.archive

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

private const val UNICODE_REPLACEMENT_CHAR = '\uFFFD'

internal fun openComicZip(
    file: File,
    openUtf8: (File) -> ZipFile = { ZipFile(it, Charsets.UTF_8) }
): ZipFile {
    var utf8Failure: Throwable? = null
    try {
        val zip = openUtf8(file)
        val hasReplacementName = zip.readDirectoryOrClose {
            // Some Android versions defer decoding names until entries are enumerated,
            // and Android 15 replaces invalid UTF-8 bytes with U+FFFD instead of throwing.
            val entries = zip.entries()
            var hasReplacement = false
            while (entries.hasMoreElements()) {
                if (UNICODE_REPLACEMENT_CHAR in entries.nextElement().name) {
                    hasReplacement = true
                    break
                }
            }
            hasReplacement
        }
        if (!hasReplacementName) return zip
        runCatching { zip.close() }
    } catch (failure: ZipException) {
        if (failure.message?.contains("bad entry name", ignoreCase = true) != true) {
            throw failure
        }
        utf8Failure = failure
    } catch (failure: IllegalArgumentException) {
        utf8Failure = failure
    }

    try {
        return GbkComicZipFile(file)
    } catch (failure: Exception) {
        utf8Failure?.let(failure::addSuppressed)
        throw failure
    }
}

private class GbkComicZipFile(file: File) : ZipFile(file, Charset.forName("GBK")) {
    // Older Android implementations encode lookup keys using the archive charset even for UTF-8 entries.
    private val entriesByName = readDirectoryOrClose {
        entries().asSequence().associateBy { it.name }
    }

    override fun getEntry(name: String): ZipEntry? = entriesByName[name]
}

private inline fun <T> ZipFile.readDirectoryOrClose(readDirectory: () -> T): T {
    try {
        return readDirectory()
    } catch (failure: Throwable) {
        runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}
