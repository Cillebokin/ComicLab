package com.example.comiclab.core.organize

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageFileMergeOperationsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyDirectoryReportsNoFoldersAndOnlyScanningProgress() {
        val root = temporaryFolder.newFolder("empty-root")
        val progress = mutableListOf<ImageFileMergeProgress>()

        val result = ImageFileMergeOperations.merge(root, onProgress = progress::add)

        assertEquals(
            ImageFileMergeResult.Completed(
                outputDirectory = null,
                folderCount = 0,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            ),
            result
        )
        assertEquals(
            listOf(ImageFileMergeProgress(ImageFileMergeProgress.Stage.SCANNING_DIRECTORIES)),
            progress
        )
    }

    @Test
    fun filtersDirectoriesAndFilesAndKeepsNaturalOrderAndSourceTimestamp() {
        val root = temporaryFolder.newFolder("mixed-root")
        val series2 = File(root, "Series2").apply { mkdir() }
        val series10 = File(root, "Series10").apply { mkdir() }
        val textOnly = File(root, "TextOnly").apply { mkdir() }
        File(root, ".hidden").mkdir()
        File(root, "ComicLab_merged_existing").mkdir()
        File(root, "ComicLab_file_merged_existing").mkdir()
        File(root, "loose.jpg").writeText("root file ignored")
        File(root, ".hidden/hidden.jpg").writeText("hidden directory ignored")
        File(root, "ComicLab_merged_existing/old.jpg").writeText("generated comic output ignored")
        File(root, "ComicLab_file_merged_existing/old.jpg").writeText("generated file output ignored")
        File(series2, "nested").mkdir()
        File(series2, "nested/nested.jpg").writeText("nested image ignored")
        File(textOnly, "readme.txt").writeText("not an image")
        val sourceTimestamp = 1_700_000_000_123L
        val series2Image = File(series2, "same.jpg").apply {
            writeText("series 2")
            assertTrue(setLastModified(sourceTimestamp))
        }
        File(series10, "same.jpg").writeText("series 10")
        File(series10, "page10.PNG").writeText("page ten")
        File(series10, "page1.png").writeText("page one")
        File(series10, "notes.txt").writeText("not an image")
        val progress = mutableListOf<ImageFileMergeProgress>()

        val completed = ImageFileMergeOperations.merge(root, onProgress = progress::add).completed()
        val outputDirectory = checkNotNull(completed.outputDirectory)

        assertEquals(3, completed.folderCount)
        assertEquals(4, completed.totalImageCount)
        assertEquals(4, completed.copiedCount)
        assertEquals(0, completed.failedCount)
        assertTrue(outputDirectory.name.matches(Regex("ComicLab_file_merged_\\d{8}_\\d{6}")))
        assertEquals(
            listOf("0001.jpg", "0002.png", "0003.png", "0004.jpg"),
            outputDirectory.listFiles().orEmpty().map { it.name }.sorted()
        )
        assertEquals("series 2", File(outputDirectory, "0001.jpg").readText())
        assertEquals("page one", File(outputDirectory, "0002.png").readText())
        assertEquals("page ten", File(outputDirectory, "0003.png").readText())
        assertEquals("series 10", File(outputDirectory, "0004.jpg").readText())
        assertEquals(sourceTimestamp, File(outputDirectory, "0001.jpg").lastModified())
        assertEquals(
            listOf(
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.SCANNING_DIRECTORIES),
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.READING_DIRECTORIES, completed = 0, total = 3),
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.READING_DIRECTORIES, completed = 1, total = 3),
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.READING_DIRECTORIES, completed = 2, total = 3),
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.READING_DIRECTORIES, completed = 3, total = 3),
                ImageFileMergeProgress(
                    ImageFileMergeProgress.Stage.COPYING_IMAGES,
                    sourceDirectoryName = "Series2",
                    completed = 1,
                    total = 4
                ),
                ImageFileMergeProgress(
                    ImageFileMergeProgress.Stage.COPYING_IMAGES,
                    sourceDirectoryName = "Series10",
                    completed = 2,
                    total = 4
                ),
                ImageFileMergeProgress(
                    ImageFileMergeProgress.Stage.COPYING_IMAGES,
                    sourceDirectoryName = "Series10",
                    completed = 3,
                    total = 4
                ),
                ImageFileMergeProgress(
                    ImageFileMergeProgress.Stage.COPYING_IMAGES,
                    sourceDirectoryName = "Series10",
                    completed = 4,
                    total = 4
                )
            ),
            progress
        )
        assertTrue(series2Image.exists())
    }

    @Test
    fun directoriesWithoutImagesDoNotCreateOutputDirectory() {
        val root = temporaryFolder.newFolder("no-images-root")
        File(root, "empty").mkdir()
        File(root, "text-only").apply { mkdir(); resolve("readme.txt").writeText("nothing to merge") }
        val progress = mutableListOf<ImageFileMergeProgress>()

        val completed = ImageFileMergeOperations.merge(root, onProgress = progress::add).completed()

        assertNull(completed.outputDirectory)
        assertEquals(2, completed.folderCount)
        assertEquals(0, completed.totalImageCount)
        assertEquals(0, completed.copiedCount)
        assertEquals(0, completed.failedCount)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("ComicLab_file_merged_") })
        assertEquals(
            listOf(
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.SCANNING_DIRECTORIES),
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.READING_DIRECTORIES, completed = 0, total = 2),
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.READING_DIRECTORIES, completed = 1, total = 2),
                ImageFileMergeProgress(ImageFileMergeProgress.Stage.READING_DIRECTORIES, completed = 2, total = 2)
            ),
            progress
        )
    }

    @Test
    fun failedCopiesDoNotConsumeNumbersAndSuccessfulPartialOutputRemains() {
        val root = temporaryFolder.newFolder("partial-root")
        val source = File(root, "pages").apply { mkdir() }
        File(source, "01-fail.jpg").writeText("fail one")
        File(source, "02-first-success.png").writeText("first success")
        File(source, "03-fail.gif").writeText("fail two")
        File(source, "04-last-success.webp").writeText("last success")

        val completed = ImageFileMergeOperations.merge(
            root,
            copyImageToTarget = { sourceFile, targetFile ->
                if (sourceFile.name.startsWith("01-") || sourceFile.name.startsWith("03-")) {
                    false
                } else {
                    ImageFileMergeOperations.copyImageFileToTarget(sourceFile, targetFile)
                }
            },
            onProgress = {}
        ).completed()
        val outputDirectory = checkNotNull(completed.outputDirectory)

        assertEquals(1, completed.folderCount)
        assertEquals(4, completed.totalImageCount)
        assertEquals(2, completed.copiedCount)
        assertEquals(2, completed.failedCount)
        assertEquals(listOf("0001.png", "0002.webp"), outputDirectory.listFiles().orEmpty().map { it.name }.sorted())
        assertEquals("first success", File(outputDirectory, "0001.png").readText())
        assertEquals("last success", File(outputDirectory, "0002.webp").readText())
    }

    @Test
    fun copyPreservesTargetCreatedBeforeStagedImageIsPublished() {
        val root = temporaryFolder.newFolder("target-race-root")
        val sourceDirectory = File(root, "pages").apply { mkdir() }
        File(sourceDirectory, "page.jpg").writeText("source bytes")
        var competingTarget: File? = null

        val completed = ImageFileMergeOperations.merge(
            root,
            copyImageToTarget = { sourceFile, targetFile ->
                targetFile.writeText("created by another writer")
                competingTarget = targetFile
                ImageFileMergeOperations.copyImageFileToTarget(sourceFile, targetFile)
            },
            onProgress = {}
        ).completed()

        assertEquals(0, completed.copiedCount)
        assertEquals(1, completed.failedCount)
        assertEquals("created by another writer", checkNotNull(competingTarget).readText())
        assertEquals(listOf("0001.jpg"), checkNotNull(completed.outputDirectory).listFiles().orEmpty().map { it.name })
    }

    @Test
    fun failedCopyPreservesTargetCreatedDuringCopy() {
        val root = temporaryFolder.newFolder("failed-target-race-root")
        val sourceDirectory = File(root, "pages").apply { mkdir() }
        File(sourceDirectory, "page.jpg").writeText("source bytes")
        var competingTarget: File? = null

        val completed = ImageFileMergeOperations.merge(
            root,
            copyImageToTarget = { _, targetFile ->
                targetFile.writeText("created by another writer")
                competingTarget = targetFile
                false
            },
            onProgress = {}
        ).completed()

        assertEquals(0, completed.copiedCount)
        assertEquals(1, completed.failedCount)
        assertEquals("created by another writer", checkNotNull(competingTarget).readText())
        assertEquals(listOf("0001.jpg"), checkNotNull(completed.outputDirectory).listFiles().orEmpty().map { it.name })
    }

    @Test
    fun outputDirectoryCreationFailureReturnsAnErrorResult() {
        val root = temporaryFolder.newFolder("mkdir-failure-root")
        File(root, "pages").apply { mkdir(); resolve("page.jpg").writeText("image") }

        val result = ImageFileMergeOperations.merge(
            root,
            createOutputDirectory = { false },
            onProgress = {}
        )

        assertEquals(
            ImageFileMergeResult.Failed(ImageFileMergeResult.Failure.OUTPUT_DIRECTORY_CREATION),
            result
        )
    }

    @Test
    fun outputDirectoryNameUsesTimestampAndFirstFreeCollisionSuffix() {
        val root = temporaryFolder.newFolder("output-name-root")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
            .parse("20261005_123456")!!
        File(root, "ComicLab_file_merged_20261005_123456").mkdir()
        File(root, "ComicLab_file_merged_20261005_123456_001").mkdir()

        val outputDirectory = ImageFileMergeOperations.buildOutputDirectory(root, timestamp)

        assertEquals("ComicLab_file_merged_20261005_123456_002", outputDirectory.name)
        assertFalse(outputDirectory.exists())
    }

    @Test
    fun interruptionDoesNotCancelTheExistingCopyLoop() {
        val root = temporaryFolder.newFolder("interrupted-root")
        val source = File(root, "pages").apply { mkdir() }
        File(source, "page1.jpg").writeText("one")
        File(source, "page2.jpg").writeText("two")
        var wasInterruptedAfterMerge = false
        val result: ImageFileMergeResult

        try {
            result = ImageFileMergeOperations.merge(root) { progress ->
                if (progress.stage == ImageFileMergeProgress.Stage.SCANNING_DIRECTORIES) {
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

    private fun ImageFileMergeResult.completed(): ImageFileMergeResult.Completed {
        assertTrue("Expected a completed result but was $this", this is ImageFileMergeResult.Completed)
        return this as ImageFileMergeResult.Completed
    }
}
