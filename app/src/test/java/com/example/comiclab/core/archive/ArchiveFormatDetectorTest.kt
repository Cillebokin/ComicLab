package com.example.comiclab.core.archive

import com.example.comiclab.ComicArchive
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveFormatDetectorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun isArchive(file: File): Boolean = ComicArchive.isArchive(file).also {
        assertEquals(it, ArchiveFormatDetector.isArchive(file))
    }
    private fun isSupportedArchive(file: File): Boolean = ComicArchive.isSupportedArchive(file).also {
        assertEquals(it, ArchiveFormatDetector.isSupportedArchive(file))
    }
    private fun isPdf(file: File): Boolean = ComicArchive.isPdf(file).also {
        assertEquals(it, ArchiveFormatDetector.isPdf(file))
    }

    @Test fun allExtensionsIgnoreCaseAndFileContent() {
        for (extension in listOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "pdf", "tar", "gz")) {
            for (variant in listOf(extension, extension.uppercase(Locale.ROOT))) {
                val file = temporaryFolder.newFile("archive-${temporaryFolder.root.listFiles()!!.size}.$variant")
                assertTrue(variant, isArchive(file))
                assertEquals(variant, extension !in listOf("tar", "gz"), isSupportedArchive(file))
                assertEquals(variant, extension == "pdf", isPdf(file))
            }
        }
    }

    @Test fun missingDirectoriesNoExtensionAndUnsupportedAreRejected() {
        val files = listOf(File(temporaryFolder.root, "missing.zip"), temporaryFolder.newFolder("folder.pdf"),
            temporaryFolder.newFile("archive"), temporaryFolder.newFile("archive.txt"), temporaryFolder.newFile("archive.zip.txt"))
        for (file in files) {
            assertFalse(isArchive(file))
            assertFalse(isSupportedArchive(file))
            assertFalse(isPdf(file))
        }
    }

    @Test fun turkishDefaultLocaleDoesNotChangeFormatChecks() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val file = temporaryFolder.newFile("ARCHIVE.ZIP")
            assertTrue(isArchive(file))
            assertTrue(isSupportedArchive(file))
            assertFalse(isPdf(file))
        } finally { Locale.setDefault(original) }
    }
}

