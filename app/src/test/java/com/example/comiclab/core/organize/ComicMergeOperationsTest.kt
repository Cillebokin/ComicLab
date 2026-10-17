package com.example.comiclab.core.organize

import com.example.comiclab.ComicArchive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicMergeOperationsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyDirectoryReportsNoArchivesAndOnlyScanningProgress() {
        val root = temporaryFolder.newFolder("empty-root")
        val progress = mutableListOf<ComicArchiveMergeProgress>()

        val result = ComicMergeOperations.merge(root, onProgress = progress::add)

        assertEquals(
            ComicMergeResult.Completed(
                outputDirectory = null,
                archiveCount = 0,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            ),
            result
        )
        assertEquals(
            listOf(ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.SCANNING_ARCHIVES)),
            progress
        )
    }

    @Test
    fun filtersUnsupportedFilesAndPdfWhilePreservingNaturalArchiveAndEntryOrder() {
        val root = temporaryFolder.newFolder("ordered-root")
        createArchive(root, "comic10.cbz", "page10.PNG" to "ten", "page1.png" to "duplicate-10", "page2.JpG" to "two", "notes.txt" to "ignore")
        createArchive(root, "comic2.zip", "page1.png" to "duplicate-2")
        File(root, "notes.txt").writeText("not an archive")
        File(root, "document.pdf").writeText("not a comic archive")
        File(root, "directory.cbz").mkdir()
        val progress = mutableListOf<ComicArchiveMergeProgress>()

        val result = ComicMergeOperations.merge(root, onProgress = progress::add)
        val completed = result.completed()
        val outputDirectory = checkNotNull(completed.outputDirectory)

        assertEquals(2, completed.archiveCount)
        assertEquals(4, completed.totalImageCount)
        assertEquals(4, completed.copiedCount)
        assertEquals(0, completed.failedCount)
        assertTrue(outputDirectory.name.matches(Regex("ComicLab_merged_\\d{8}_\\d{6}")))
        assertEquals(
            listOf("0001.png", "0002.png", "0003.jpg", "0004.png"),
            outputDirectory.listFiles().orEmpty().map { it.name }.sorted()
        )
        assertEquals("duplicate-2", File(outputDirectory, "0001.png").readText())
        assertEquals("duplicate-10", File(outputDirectory, "0002.png").readText())
        assertEquals("two", File(outputDirectory, "0003.jpg").readText())
        assertEquals("ten", File(outputDirectory, "0004.png").readText())
        assertEquals(
            listOf(
                ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.SCANNING_ARCHIVES),
                ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.READING_ARCHIVES, completed = 0, total = 2),
                ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.READING_ARCHIVES, completed = 1, total = 2),
                ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.READING_ARCHIVES, completed = 2, total = 2),
                ComicArchiveMergeProgress(
                    ComicArchiveMergeProgress.Stage.MERGING_ARCHIVE_ENTRIES,
                    sourceArchiveName = "comic2.zip",
                    completed = 1,
                    total = 4
                ),
                ComicArchiveMergeProgress(
                    ComicArchiveMergeProgress.Stage.MERGING_ARCHIVE_ENTRIES,
                    sourceArchiveName = "comic10.cbz",
                    completed = 2,
                    total = 4
                ),
                ComicArchiveMergeProgress(
                    ComicArchiveMergeProgress.Stage.MERGING_ARCHIVE_ENTRIES,
                    sourceArchiveName = "comic10.cbz",
                    completed = 3,
                    total = 4
                ),
                ComicArchiveMergeProgress(
                    ComicArchiveMergeProgress.Stage.MERGING_ARCHIVE_ENTRIES,
                    sourceArchiveName = "comic10.cbz",
                    completed = 4,
                    total = 4
                )
            ),
            progress
        )
    }

    @Test
    fun archivesWithoutImageEntriesDoNotCreateOutputDirectory() {
        val root = temporaryFolder.newFolder("no-images-root")
        createArchive(root, "text-only.cbz", "readme.txt" to "no images")
        val progress = mutableListOf<ComicArchiveMergeProgress>()

        val result = ComicMergeOperations.merge(root, onProgress = progress::add).completed()

        assertNull(result.outputDirectory)
        assertEquals(1, result.archiveCount)
        assertEquals(0, result.totalImageCount)
        assertEquals(0, result.copiedCount)
        assertEquals(0, result.failedCount)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("ComicLab_merged_") })
        assertEquals(
            listOf(
                ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.SCANNING_ARCHIVES),
                ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.READING_ARCHIVES, completed = 0, total = 1),
                ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.READING_ARCHIVES, completed = 1, total = 1)
            ),
            progress
        )
    }

    @Test
    fun failedEntriesDoNotConsumeNumbersAndSuccessfulPartialOutputRemains() {
        val root = temporaryFolder.newFolder("partial-output-root")
        createArchive(
            root,
            "pages.cbz",
            "01-fail.jpg" to "failed one",
            "02-first-success.png" to "first success",
            "03-fail.gif" to "failed two",
            "04-last-success.webp" to "last success"
        )

        val result = ComicMergeOperations.merge(
            root,
            extractEntryToFile = { archive, entryName, targetFile ->
                if (entryName.startsWith("01-") || entryName.startsWith("03-")) {
                    false
                } else {
                    ComicArchive.extractImageEntryToFile(archive, entryName, targetFile)
                }
            },
            onProgress = {}
        ).completed()
        val outputDirectory = checkNotNull(result.outputDirectory)

        assertEquals(4, result.totalImageCount)
        assertEquals(2, result.copiedCount)
        assertEquals(2, result.failedCount)
        assertEquals(listOf("0001.png", "0002.webp"), outputDirectory.listFiles().orEmpty().map { it.name }.sorted())
        assertEquals("first success", File(outputDirectory, "0001.png").readText())
        assertEquals("last success", File(outputDirectory, "0002.webp").readText())
    }

    @Test
    fun extractionPreservesTargetCreatedBeforeStagedImageIsPublished() {
        val root = temporaryFolder.newFolder("target-race-root")
        createArchive(root, "one.cbz", "page.jpg" to "archive bytes")
        var racedTarget: File? = null

        val result = ComicMergeOperations.merge(
            root,
            extractEntryToFile = { archive, entryName, targetFile ->
                val target = File(targetFile.parentFile, "0001.jpg")
                target.writeText("target created by another writer")
                racedTarget = target
                ComicArchive.extractImageEntryToFile(archive, entryName, targetFile)
            },
            onProgress = {}
        ).completed()
        val outputDirectory = checkNotNull(result.outputDirectory)

        assertEquals(0, result.copiedCount)
        assertEquals(1, result.failedCount)
        assertEquals("target created by another writer", checkNotNull(racedTarget).readText())
        assertEquals(listOf("0001.jpg"), outputDirectory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun failedExtractionPreservesTargetCreatedDuringExtraction() {
        val root = temporaryFolder.newFolder("failed-target-race-root")
        createArchive(root, "one.cbz", "page.jpg" to "archive bytes")
        var racedTarget: File? = null

        val result = ComicMergeOperations.merge(
            root,
            extractEntryToFile = { _, _, stagedFile ->
                val target = File(stagedFile.parentFile, "0001.jpg")
                target.writeText("target created by another writer")
                stagedFile.writeText("partial staged output")
                racedTarget = target
                false
            },
            onProgress = {}
        ).completed()
        val outputDirectory = checkNotNull(result.outputDirectory)

        assertEquals(0, result.copiedCount)
        assertEquals(1, result.failedCount)
        assertEquals("target created by another writer", checkNotNull(racedTarget).readText())
        assertEquals(listOf("0001.jpg"), outputDirectory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun outputDirectoryCreationFailureReturnsAnErrorResult() {
        val root = temporaryFolder.newFolder("output-creation-failure-root")
        createArchive(root, "one.cbz", "page.jpg" to "archive bytes")

        val result = ComicMergeOperations.merge(
            root,
            createOutputDirectory = { false },
            onProgress = {}
        )

        assertEquals(
            ComicMergeResult.Failed(ComicMergeResult.Failure.OUTPUT_DIRECTORY_CREATION),
            result
        )
    }

    @Test
    fun outputDirectoryNameUsesTimestampAndFirstFreeCollisionSuffix() {
        val root = temporaryFolder.newFolder("output-name-root")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
            .parse("20261005_123456")!!
        File(root, "ComicLab_merged_20261005_123456").mkdir()
        File(root, "ComicLab_merged_20261005_123456_001").mkdir()

        val outputDirectory = ComicMergeOperations.buildOutputDirectory(root, timestamp)

        assertEquals("ComicLab_merged_20261005_123456_002", outputDirectory.name)
        assertFalse(outputDirectory.exists())
    }

    @Test
    fun interruptionDoesNotCancelTheExistingMergeLoop() {
        val root = temporaryFolder.newFolder("interrupted-root")
        createArchive(root, "pages.cbz", "page1.jpg" to "one", "page2.jpg" to "two")
        var wasInterruptedAfterMerge = false
        val result: ComicMergeResult

        try {
            result = ComicMergeOperations.merge(root) { progress ->
                if (progress.stage == ComicArchiveMergeProgress.Stage.SCANNING_ARCHIVES) {
                    Thread.currentThread().interrupt()
                }
            }
            wasInterruptedAfterMerge = Thread.currentThread().isInterrupted
        } finally {
            Thread.interrupted()
        }

        val completed = result.completed()
        assertTrue(wasInterruptedAfterMerge)
        assertEquals(2, completed.copiedCount)
        assertEquals(0, completed.failedCount)
    }

    private fun createArchive(root: File, name: String, vararg entries: Pair<String, String>): File {
        val archive = File(root, name)
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (entryName, content) ->
                output.putNextEntry(ZipEntry(entryName))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return archive
    }

    private fun ComicMergeResult.completed(): ComicMergeResult.Completed {
        assertTrue("Expected a completed result but was $this", this is ComicMergeResult.Completed)
        return this as ComicMergeResult.Completed
    }
}
