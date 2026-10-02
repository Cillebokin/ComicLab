package com.example.comiclab.ebook.epub

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EpubParserTest {

    @Test
    fun parseRejectsMissingEmptyAndNonFileInputs() {
        val root = Files.createTempDirectory("comiclab-epub-invalid-input").toFile()
        try {
            val missing = File(root, "missing.epub")
            val empty = File(root, "empty.epub").apply { createNewFile() }
            val directory = File(root, "directory.epub").apply { mkdirs() }

            assertParseError(EpubParseError.INVALID_FILE, missing)
            assertParseError(EpubParseError.INVALID_FILE, empty)
            assertParseError(EpubParseError.INVALID_FILE, directory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parseReportsInvalidZipArchive() {
        val root = Files.createTempDirectory("comiclab-epub-not-zip").toFile()
        try {
            val file = File(root, "not-an-archive.epub").apply { writeText("not a zip") }

            assertParseError(EpubParseError.INVALID_ARCHIVE, file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parseRejectsMissingOrInvalidMimetype() {
        val root = Files.createTempDirectory("comiclab-epub-mimetype").toFile()
        try {
            val missing = File(root, "missing-mimetype.epub")
            writeZip(missing, "META-INF/other.xml" to "<other/>")
            val invalid = File(root, "invalid-mimetype.epub")
            writeZip(invalid, "mimetype" to "text/plain")

            assertParseError(EpubParseError.MISSING_MIMETYPE, missing)
            assertParseError(EpubParseError.MISSING_MIMETYPE, invalid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parseRejectsEncryptedEpubBeforeReadingPackageMetadata() {
        val root = Files.createTempDirectory("comiclab-epub-encrypted").toFile()
        try {
            val file = File(root, "encrypted.epub")
            writeZip(file, "META-INF/encryption.xml" to "<encryption/>")

            assertParseError(EpubParseError.ENCRYPTED, file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parseReportsMissingContainerWithoutAttemptingXmlParsing() {
        val root = Files.createTempDirectory("comiclab-epub-container").toFile()
        try {
            val file = File(root, "missing-container.epub")
            writeZip(file, "mimetype" to "application/epub+zip")

            assertParseError(EpubParseError.INVALID_CONTAINER, file)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertParseError(expected: EpubParseError, file: File) {
        try {
            EpubParser().parse(file)
            fail("Expected ${expected.name} for ${file.name}")
        } catch (error: EpubParseException) {
            assertEquals(expected, error.reason)
        }
    }

    private fun writeZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }
}
