package com.example.comiclab

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicArchivePreparedImagesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extractionManifestPreservesNaturalEntryOrderAndMapsEachSlotToItsSourceBytes() {
        val archive = File(temporaryFolder.root, "pages.cbz")
        writeZip(
            archive,
            "pages/10.jpg" to "ten".toByteArray(),
            "notes.txt" to "note".toByteArray(),
            "covers/cover.jpg" to "cover".toByteArray(),
            "pages/2.jpg" to "two".toByteArray()
        )
        val output = File(temporaryFolder.root, "prepared")
        val progress = mutableListOf<Pair<Int, Int>>()

        val extracted = ComicArchive.extractImagesToDirectory(archive, output) { completed, total ->
            progress += completed to total
        }

        assertEquals(
            listOf("covers/cover.jpg", "pages/2.jpg", "pages/10.jpg"),
            ComicArchive.preparedImageEntries(output)
        )
        assertEquals(3, extracted.size)
        assertEquals("cover", preparedFileAt(output, 0).readText())
        assertEquals("two", preparedFileAt(output, 1).readText())
        assertEquals("ten", preparedFileAt(output, 2).readText())
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun extractionFailureRemovesTheWholePartialDirectoryAndManifest() {
        val archive = File(temporaryFolder.root, "one-bad-image.cbz")
        writeZip(
            archive,
            "1.jpg" to "valid".toByteArray(),
            "2.png" to byteArrayOf()
        )
        val output = File(temporaryFolder.root, "failed-prepared")

        val extracted = ComicArchive.extractImagesToDirectory(archive, output) { _, _ -> }

        assertTrue(extracted.isEmpty())
        assertFalse(output.exists())
    }

    @Test
    fun preparedExtractionCancellationReturnsOnlyCompletedFilesAndReportsPartialProgress() {
        val archive = File(temporaryFolder.root, "cancelled.cbz")
        writeZip(archive, "1.jpg" to "one".toByteArray(), "2.jpg" to "two".toByteArray())
        val output = File(temporaryFolder.root, "partial-prepared")
        val progress = mutableListOf<Pair<Int, Int>>()
        var continuationChecks = 0

        val extracted = ComicArchive.extractPreparedImagesToDirectory(
            archive,
            listOf(
                ComicArchive.PreparedImageEntry(0, "1.jpg"),
                ComicArchive.PreparedImageEntry(1, "2.jpg")
            ),
            output,
            shouldContinue = { ++continuationChecks == 1 }
        ) { completed, total -> progress += completed to total }

        assertEquals(1, extracted.size)
        assertEquals("one", extracted.single().readText())
        assertTrue(output.listFiles().orEmpty().none { it.name.startsWith("%06d_".format(1)) })
        assertEquals(listOf(0 to 2, 1 to 2), progress)
    }

    @Test
    fun preparedReaderCloseDeletesOnlyWhenRequested() {
        val deleteDirectory = preparedDirectory("delete-on-close")
        val deleteSession = ComicArchive.openPreparedReaderSession(deleteDirectory, deleteOnClose = true)
        deleteSession.close()
        assertFalse(deleteDirectory.exists())

        val keepDirectory = preparedDirectory("keep-on-close")
        val keepSession = ComicArchive.openPreparedReaderSession(keepDirectory, deleteOnClose = false)
        keepSession.close()
        assertTrue(keepDirectory.isDirectory)
        assertTrue(ComicArchive.preparedImageFile(keepDirectory, 0, "prepared.jpg").isFile)
    }

    @Test
    fun extractorReusesExistingNonEmptyPreparedSlotWithoutOverwritingIt() {
        val archive = File(temporaryFolder.root, "cached.cbz")
        writeZip(archive, "1.jpg" to "archive bytes".toByteArray())
        val output = File(temporaryFolder.root, "cached-prepared").apply { mkdirs() }
        val cachedSlot = ComicArchive.preparedImageFile(output, 0, "1.jpg").apply {
            writeText("already prepared")
        }

        val extracted = ComicArchive.openPreparedImageExtractor(archive, output).use {
            it.extract(0, "1.jpg")
        }

        assertEquals(cachedSlot, extracted)
        assertEquals("already prepared", cachedSlot.readText())
    }

    @Test
    fun extractorReturnsNullForMissingEntryWithoutLeavingPartialSlot() {
        val archive = File(temporaryFolder.root, "missing-entry.cbz")
        writeZip(archive, "1.jpg" to "present".toByteArray())
        val output = File(temporaryFolder.root, "missing-entry-prepared")

        val extracted = ComicArchive.openPreparedImageExtractor(archive, output).use {
            it.extract(0, "missing.jpg")
        }

        assertEquals(null, extracted)
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    private fun preparedDirectory(name: String): File {
        val directory = File(temporaryFolder.root, name).apply { mkdirs() }
        ComicArchive.writePreparedReaderManifest(directory, listOf("prepared.jpg"))
        ComicArchive.preparedImageFile(directory, 0, "prepared.jpg").writeText("image")
        return directory
    }

    private fun preparedFileAt(directory: File, index: Int): File =
        directory.listFiles().orEmpty().single { it.isFile && it.name.startsWith("%06d_".format(index)) }

    private fun writeZip(file: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
