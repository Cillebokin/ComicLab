package com.example.comiclab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTransferOperationsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun suggestedNameAddsFirstAvailableNumberAndKeepsExtension() {
        val destination = temporaryFolder.newFolder("destination")
        File(destination, "Manga (1).cbz").writeText("existing")
        File(destination, "Manga (2).cbz").writeText("existing")

        assertEquals(
            "Manga (3).cbz",
            FileTransferOperations.suggestedName("Manga.cbz", destination)
        )
    }

    @Test
    fun targetFileNamePreservesExtensionWithoutDuplicatingIt() {
        assertEquals(
            "New title.cbz",
            FileTransferOperations.targetFileName("Original.cbz", "New title")
        )
        assertEquals(
            "New title.cbz",
            FileTransferOperations.targetFileName("Original.cbz", "New title.cbz")
        )
    }

    @Test
    fun targetFileNameRejectsEmptyOrPathLikeNames() {
        assertEquals(null, FileTransferOperations.targetFileName("Manga.cbz", "  "))
        assertEquals(null, FileTransferOperations.targetFileName("Manga.cbz", "../Other"))
        assertEquals(null, FileTransferOperations.targetFileName("Manga.cbz", "Bad:name"))
    }

    @Test
    fun copyLeavesSourceAndCopiesContents() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("comic data") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "copy.cbz")

        val result = FileTransferOperations.transfer(source, target, moveSource = false)

        assertEquals(FileTransferOperations.Result.SUCCESS, result)
        assertTrue(source.isFile)
        assertEquals("comic data", target.readText())
    }

    @Test
    fun cutCopiesContentsAndRemovesSourceOnlyAfterCopySucceeds() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("comic data") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "moved.cbz")
        val progress = mutableListOf<FileOperationProgress>()

        val result = FileTransferOperations.transfer(
            source,
            target,
            moveSource = true,
            onProgress = { progress += it }
        )

        assertEquals(FileTransferOperations.Result.SUCCESS, result)
        assertFalse(source.exists())
        assertEquals("comic data", target.readText())
        assertTrue(progress.any { it.phase == FileOperationProgress.Phase.DELETING_SOURCE })
        assertEquals(1, progress.last { it.phase == FileOperationProgress.Phase.DELETING_SOURCE }.completedItems)
    }

    @Test
    fun copyReportsByteAndItemProgressThroughCompletion() {
        val sourceContents = "comic data".repeat(1024)
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText(sourceContents) }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "copy.cbz")
        val progress = mutableListOf<FileOperationProgress>()

        val result = FileTransferOperations.transfer(
            source,
            target,
            moveSource = false,
            onProgress = { progress += it }
        )

        assertEquals(FileTransferOperations.Result.SUCCESS, result)
        assertEquals(sourceContents, target.readText())
        assertTrue(progress.any {
            it.phase == FileOperationProgress.Phase.COPYING &&
                it.completedBytes == 0L && it.totalBytes == sourceContents.length.toLong()
        })
        val completed = progress.last { it.phase == FileOperationProgress.Phase.COPYING }
        assertEquals(sourceContents.length.toLong(), completed.completedBytes)
        assertEquals(1, completed.completedItems)
        assertEquals(1, completed.totalItems)
    }

    @Test
    fun existingTargetIsNotOverwritten() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("source data") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "same.cbz").apply { writeText("target data") }

        val result = FileTransferOperations.transfer(source, target, moveSource = false)

        assertEquals(FileTransferOperations.Result.TARGET_EXISTS, result)
        assertTrue(source.isFile)
        assertEquals("target data", target.readText())
    }
}
