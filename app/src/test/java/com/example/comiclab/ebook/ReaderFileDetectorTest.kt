package com.example.comiclab.ebook

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFileDetectorTest {

    @Test
    fun typeOfRecognizesSupportedExtensionsWithoutRequiringValidArchiveContents() {
        val root = Files.createTempDirectory("comiclab-reader-file-types").toFile()
        try {
            val expectedTypes = mapOf(
                "comic.CBZ" to ReaderFileType.IMAGE_ARCHIVE,
                "comic.cbr" to ReaderFileType.IMAGE_ARCHIVE,
                "comic.7z" to ReaderFileType.IMAGE_ARCHIVE,
                "comic.PDF" to ReaderFileType.PDF,
                "book.MOBI" to ReaderFileType.MOBI,
                "book.azw" to ReaderFileType.MOBI,
                "book.AZW3" to ReaderFileType.MOBI,
                "book.EPUB" to ReaderFileType.EPUB,
                "book.Txt" to ReaderFileType.TXT
            )

            expectedTypes.forEach { (name, expectedType) ->
                val file = File(root, name).apply { writeText("not required for extension detection") }
                assertEquals(name, expectedType, ReaderFileDetector.typeOf(file))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun typeOfRejectsUnsupportedExtensionsAndDirectories() {
        val root = Files.createTempDirectory("comiclab-reader-unsupported").toFile()
        try {
            val unsupported = File(root, "book.tar.gz").apply { writeText("data") }
            val directory = File(root, "book.epub").apply { mkdirs() }

            assertNull(ReaderFileDetector.typeOf(unsupported))
            assertNull(ReaderFileDetector.typeOf(directory))
            assertFalse(ReaderFileDetector.isSupported(unsupported))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun isEbookIncludesOnlyTextBookFormats() {
        val root = Files.createTempDirectory("comiclab-reader-ebook-types").toFile()
        try {
            val mobi = File(root, "book.mobi").apply { writeText("data") }
            val epub = File(root, "book.epub").apply { writeText("data") }
            val txt = File(root, "book.txt").apply { writeText("data") }
            val pdf = File(root, "book.pdf").apply { writeText("data") }
            val comic = File(root, "book.cbz").apply { writeText("data") }

            assertTrue(ReaderFileDetector.isEbook(mobi))
            assertTrue(ReaderFileDetector.isEbook(epub))
            assertTrue(ReaderFileDetector.isEbook(txt))
            assertFalse(ReaderFileDetector.isEbook(pdf))
            assertFalse(ReaderFileDetector.isEbook(comic))
        } finally {
            root.deleteRecursively()
        }
    }
}
