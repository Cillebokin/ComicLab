package com.example.comiclab

import org.junit.Test

import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun extractStartMarker_returnsFirstValidBracketContent() {
        val marker = CommonFunc.extractStartMarker("[作者][作品名].zip", "中;汉;翻;翻译")

        assertEquals("作者", marker)
    }

    @Test
    fun extractStartMarker_skipsEmptyAndErrorMarkers() {
        val marker = CommonFunc.extractStartMarker("[][中文翻译][社团][标题].zip", "中;汉;翻;翻译")

        assertEquals("社团", marker)
    }

    @Test
    fun extractStartMarker_returnsEmptyWhenNoValidMarkerExists() {
        val marker = CommonFunc.extractStartMarker("漫画[翻译][汉化].zip", "中;汉;翻;翻译")

        assertEquals("", marker)
    }

    @Test
    fun extractStartMarker_allowsEmptyErrorMarkers() {
        val marker = CommonFunc.extractStartMarker("[翻译][标题].zip", "")

        assertEquals("翻译", marker)
    }

    @Test
    fun classify_createsNextOutputDirectoryAndRenamesDuplicates() {
        val root = Files.createTempDirectory("comiclab-classify").toFile()
        try {
            File(root, "ClassifyNList000").mkdirs()
            File(root, "[社团]漫画.zip").writeText("one")
            File(root, "sub").mkdirs()
            File(root, "sub/[社团]漫画.zip").writeText("two")
            File(root, "[翻译]无标签.zip").writeText("three")

            val result = ComicClassifier.classify(root, "翻译") {}
            val output = File(root, "ClassifyNList001")

            assertEquals(3, result.totalCount)
            assertEquals(3, result.copiedCount)
            assertEquals(0, result.failedCount)
            assertEquals(output.absolutePath, result.outputDirectory?.absolutePath)
            assertTrue(File(output, "社团/[社团]漫画.zip").isFile)
            assertTrue(File(output, "社团/[社团]漫画000.zip").isFile)
            assertTrue(File(output, "ClassifyNListNoTag/[翻译]无标签.zip").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteEntry_removesSelectedZipEntryAndKeepsOtherEntries() {
        val root = Files.createTempDirectory("comiclab-delete-entry").toFile()
        try {
            val archive = File(root, "comic.cbz")
            ZipOutputStream(archive.outputStream()).use { output ->
                output.writeEntry("001.jpg", "one")
                output.writeEntry("002.jpg", "two")
                output.writeEntry("notes.txt", "note")
            }

            assertTrue(ComicArchive.deleteEntry(archive, "002.jpg"))

            ZipFile(archive).use { zipFile ->
                val keptImage = zipFile.getEntry("001.jpg")
                assertNotNull(keptImage)
                assertEquals(ZipEntry.STORED, keptImage.method)
                assertEquals("one", zipFile.readEntryText("001.jpg"))
                assertNull(zipFile.getEntry("002.jpg"))
                assertEquals("note", zipFile.readEntryText("notes.txt"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun imageEntries_returnsEmptyWhenArchiveWasMovedOrDeleted() {
        val root = Files.createTempDirectory("comiclab-missing-archive").toFile()
        try {
            val archive = File(root, "deleted.cbz")
            ZipOutputStream(archive.outputStream()).use { output ->
                output.writeEntry("001.jpg", "one")
            }
            assertTrue(archive.delete())

            assertTrue(ComicArchive.imageEntries(archive).isEmpty())
            assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(archive))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun imageEntries_returnsEmptyForDamagedZip() {
        val root = Files.createTempDirectory("comiclab-damaged-zip").toFile()
        try {
            val archive = File(root, "damaged.cbz")
            archive.writeText("this is not a zip archive")

            assertTrue(ComicArchive.imageEntries(archive).isEmpty())
            assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(archive))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun imageEntries_returnsEmptyForDamagedPdf() {
        val root = Files.createTempDirectory("comiclab-damaged-pdf").toFile()
        try {
            val pdf = File(root, "damaged.pdf")
            pdf.writeText("this is not a pdf file")

            assertTrue(ComicArchive.imageEntries(pdf).isEmpty())
            assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(pdf))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun imageEntries_sortsLargeZipNaturally() {
        val root = Files.createTempDirectory("comiclab-large-zip").toFile()
        try {
            val archive = File(root, "large.cbz")
            ZipOutputStream(archive.outputStream()).use { output ->
                (120 downTo 1).forEach { index ->
                    output.writeEntry("$index.jpg", "page-$index")
                }
            }

            val entries = ComicArchive.imageEntries(archive)

            assertEquals(120, entries.size)
            assertEquals("1.jpg", entries[0])
            assertEquals("2.jpg", entries[1])
            assertEquals("10.jpg", entries[9])
            assertEquals("120.jpg", entries.last())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun ZipFile.readEntryText(name: String): String {
        val entry = getEntry(name) ?: return ""
        return getInputStream(entry).use { input ->
            String(input.readBytes())
        }
    }
}
