package com.example.comiclab

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.comiclab.core.archive.PreparedArchiveExtractor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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

    @Test
    fun concurrentExtractorsSerializeTheFixedPartFileAndKeepArchiveBytesUnchanged() {
        val archive = File(temporaryFolder.root, "concurrent.cbz")
        val expected = ByteArray(512 * 1024) { index -> (index % 251).toByte() }
        writeZip(archive, "1.jpg" to expected)
        val archiveBefore = archive.readBytes()
        val output = temporaryFolder.newFolder("concurrent-prepared")
        val firstExtractor = ComicArchive.openPreparedImageExtractor(archive, output)
        val secondExtractor = ComicArchive.openPreparedImageExtractor(archive, output)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val results = AtomicReferenceArray<File?>(2)

        try {
            listOf(firstExtractor, secondExtractor).forEachIndexed { index, extractor ->
                Thread({
                    try {
                        start.await()
                        results.set(index, extractor.extract(0, "1.jpg"))
                    } finally {
                        finished.countDown()
                    }
                }, "PreparedImageExtractorTest-$index").apply { isDaemon = true; start() }
            }
            start.countDown()
            assertTrue("both extraction attempts must finish", finished.await(5, TimeUnit.SECONDS))
        } finally {
            firstExtractor.close()
            secondExtractor.close()
        }

        val firstResult = results.get(0)
        val secondResult = results.get(1)
        assertTrue(firstResult != null)
        assertEquals(firstResult, secondResult)
        assertTrue(firstResult!!.isFile)
        assertTrue(firstResult.readBytes().contentEquals(expected))
        assertTrue(output.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertTrue(archive.readBytes().contentEquals(archiveBefore))
    }

    @Test
    fun failedPreparedExtractionLeavesSourceArchiveUnchangedAndCleansPartFile() {
        val archive = File(temporaryFolder.root, "failed-entry.cbz")
        writeZip(archive, "empty.jpg" to byteArrayOf())
        val archiveBefore = archive.readBytes()
        val output = temporaryFolder.newFolder("failed-entry-prepared")
        val target = ComicArchive.preparedImageFile(output, 0, "empty.jpg")

        val extracted = ComicArchive.openPreparedImageExtractor(archive, output).use {
            it.extract(0, "empty.jpg")
        }

        assertEquals(null, extracted)
        assertFalse(target.exists())
        assertFalse(File(output, "${target.name}.part").exists())
        assertTrue(archive.readBytes().contentEquals(archiveBefore))
    }

    @Test
    fun manifestKeepsDuplicatesAndEscapesAndRepeatedWritesReplaceThePreviousList() {
        val output = temporaryFolder.newFolder("manifest")
        ComicArchive.writePreparedReaderManifest(output, listOf("old.jpg"))
        ComicArchive.writePreparedReaderManifest(output, listOf("a.jpg", "a.jpg", "folder/\"中\\.PNG"))

        assertEquals("[\"a.jpg\",\"a.jpg\",\"folder\\/\\\"中\\\\.PNG\"]", File(output, "reader_manifest.json").readText())
        assertEquals(listOf("a.jpg", "a.jpg", "folder/\"中\\.PNG"), ComicArchive.preparedImageEntries(output))
        assertEquals("000002_bdc5016b.png", ComicArchive.preparedImageFile(output, 2, "folder/\"中\\.PNG").name)
        ComicArchive.writePreparedReaderManifest(output, emptyList())
        assertEquals("[]", File(output, "reader_manifest.json").readText())
        assertTrue(ComicArchive.preparedImageEntries(output).isEmpty())
    }

    @Test
    fun failedManifestReplacementKeepsTheLastCompleteEntryList() {
        val output = temporaryFolder.newFolder("failed-manifest")
        val previousEntries = listOf("chapters/previous-page.jpg")
        ComicArchive.writePreparedReaderManifest(output, previousEntries)
        val manifest = File(output, "reader_manifest.json")
        val previousBytes = manifest.readBytes()

        val failure = runCatching {
            PreparedArchiveExtractor.writePreparedReaderManifest(output, listOf("new-page.jpg")) { stream, _ ->
                stream.write("[\"partial".toByteArray(Charsets.UTF_8))
                throw IOException("simulated manifest write failure")
            }
        }.exceptionOrNull()

        assertEquals("simulated manifest write failure", failure?.message)
        assertTrue("Failed update replaced the existing manifest", manifest.readBytes().contentEquals(previousBytes))
        assertEquals(previousEntries, ComicArchive.preparedImageEntries(output))
        assertTrue(
            "Failed update left a manifest staging file",
            output.listFiles().orEmpty().none { it.name.startsWith("reader_manifest.json.") }
        )

        ComicArchive.writePreparedReaderManifest(output, listOf("new-page.jpg"))
        assertEquals(listOf("new-page.jpg"), ComicArchive.preparedImageEntries(output))
    }

    @Test
    fun preparedReaderUsesLastDuplicateManifestSlotAndMalformedManifestFallsBackToSortedFiles() {
        val output = temporaryFolder.newFolder("reader-mapping")
        ComicArchive.writePreparedReaderManifest(output, listOf("same.png", "same.png"))
        writePng(ComicArchive.preparedImageFile(output, 0, "same.png"), 12, 24)
        writePng(ComicArchive.preparedImageFile(output, 1, "same.png"), 30, 60)
        ComicArchive.openPreparedReaderSession(output, false).use { session ->
            assertEquals(ComicArchive.ImageBounds(30, 60), session.readBounds("same.png"))
            assertEquals(null, session.readBounds("missing.png"))
        }

        File(output, "reader_manifest.json").writeText("malformed")
        val files = output.listFiles().orEmpty().filter { it.extension == "png" }.sortedBy { it.name }
        assertEquals(files.map { it.name }, ComicArchive.preparedImageEntries(output))
        ComicArchive.openPreparedReaderSession(output, false).use { session ->
            assertEquals(ComicArchive.ImageBounds(12, 24), session.readBounds(files[0].name))
            assertEquals(ComicArchive.ImageBounds(30, 60), session.readBounds(files[1].name))
            assertEquals(null, session.readBounds("same.png"))
        }
        assertEquals(ComicArchive.ImageBounds(30, 60), ComicArchive.imageFileBounds(files[1]))
    }

    @Test
    fun fullExtractionReplacesOutputWritesManifestBeforeProgressAndKeepsCancelledPartialFiles() {
        val archive = temporaryFolder.newFile("cancel-full.cbz")
        writeZip(archive, "1.jpg" to "one".toByteArray(), "2.jpg" to "two".toByteArray())
        val output = temporaryFolder.newFolder("full-output")
        val oldFile = File(output, "old.jpg").apply { writeText("old") }
        val progress = mutableListOf<Pair<Int, Int>>()
        var cancelled = false

        val extracted = ComicArchive.extractImagesToDirectory(archive, output, { !cancelled }) { done, total ->
            assertFalse(oldFile.exists())
            assertEquals(listOf("1.jpg", "2.jpg"), ComicArchive.preparedImageEntries(output))
            progress += done to total
            if (done == 1) cancelled = true
        }

        assertEquals(listOf(0 to 2, 1 to 2), progress)
        assertEquals("one", extracted.single().readText())
        assertTrue(File(output, "reader_manifest.json").isFile)
        assertEquals(listOf("1.jpg", "2.jpg"), ComicArchive.preparedImageEntries(output))
    }

    @Test
    fun preparedExtractionSkipsFailuresCountsAttemptsAndDoesNotWriteManifestOrDeleteOtherFiles() {
        val archive = temporaryFolder.newFile("skip.cbz")
        writeZip(archive, "1.jpg" to "one".toByteArray(), "empty.png" to byteArrayOf())
        val output = temporaryFolder.newFolder("skip-output")
        val marker = File(output, "keep.txt").apply { writeText("keep") }
        val progress = mutableListOf<Pair<Int, Int>>()

        val extracted = ComicArchive.extractPreparedImagesToDirectory(archive, listOf(
            ComicArchive.PreparedImageEntry(8, "missing.jpg"),
            ComicArchive.PreparedImageEntry(4, "empty.png"),
            ComicArchive.PreparedImageEntry(2, "1.jpg")
        ), output) { done, total -> progress += done to total }

        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals("one", extracted.single().readText())
        assertTrue(extracted.single().name.startsWith("000002_"))
        assertEquals("keep", marker.readText())
        assertFalse(File(output, "reader_manifest.json").exists())
        assertTrue(output.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun emptyExtractionReportsZeroAndLeavesExistingOutputUntouched() {
        val archive = temporaryFolder.newFile("no-images.cbz")
        writeZip(archive, "notes.txt" to "note".toByteArray())
        val output = temporaryFolder.newFolder("empty-output")
        val marker = File(output, "keep.txt").apply { writeText("keep") }
        val progress = mutableListOf<Pair<Int, Int>>()
        assertTrue(ComicArchive.extractImagesToDirectory(archive, output) { done, total -> progress += done to total }.isEmpty())
        assertTrue(ComicArchive.extractPreparedImagesToDirectory(archive, emptyList(), output) { done, total -> progress += done to total }.isEmpty())
        assertEquals(listOf(0 to 0, 0 to 0), progress)
        assertEquals("keep", marker.readText())
    }

    @Test
    fun singleEntryExtractionRejectsNonImagesAndFailedWriteCleansFixedPartFile() {
        val archive = temporaryFolder.newFile("single.cbz")
        writeZip(archive, "1.jpg" to "one".toByteArray(), "empty.png" to byteArrayOf(), "notes.txt" to "notes".toByteArray())
        val target = File(temporaryFolder.root, "target.jpg").apply { writeText("old") }
        File(temporaryFolder.root, "target.jpg.part").writeText("stale")

        assertFalse(ComicArchive.extractImageEntryToFile(archive, "notes.txt", target))
        assertEquals("old", target.readText())
        assertFalse(ComicArchive.extractImageEntryToFile(archive, "empty.png", target))
        assertFalse(target.exists())
        assertFalse(File(temporaryFolder.root, "target.jpg.part").exists())
        assertTrue(ComicArchive.extractImageEntryToFile(archive, "1.jpg", target))
        assertEquals("one", target.readText())
    }

    private fun writePng(file: File, width: Int, height: Int) {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
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
