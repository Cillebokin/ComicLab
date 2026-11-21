package com.example.comiclab.core.archive

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal fun openComicZip(file: File): ZipFile {
    val utf8Failure = try {
        val zip = ZipFile(file, Charsets.UTF_8)
        zip.readDirectoryOrClose {
            // Some Android versions defer decoding names until entries are enumerated.
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                entries.nextElement()
            }
        }
        return zip
    } catch (failure: ZipException) {
        if (failure.message?.contains("bad entry name", ignoreCase = true) != true) {
            throw failure
        }
        failure
    } catch (failure: IllegalArgumentException) {
        failure
    }

    try {
        return GbkComicZipFile(file)
    } catch (failure: Exception) {
        failure.addSuppressed(utf8Failure)
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
