package com.example.comiclab

import com.example.comiclab.core.transfer.FileOperationProgress
import com.example.comiclab.core.transfer.FileTransferOperations
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

    @Test
    fun targetCreatedDuringCopyIsNotOverwrittenAndTemporaryOutputIsRemoved() {
        val source = File(temporaryFolder.root, "racing-source.cbz").apply { writeText("source data") }
        val destination = temporaryFolder.newFolder("racing-destination")
        val target = File(destination, "copy.cbz")
        var targetCreated = false

        val result = FileTransferOperations.transfer(source, target, moveSource = false) { progress ->
            if (!targetCreated && progress.phase == FileOperationProgress.Phase.COPYING &&
                progress.completedItems == 1
            ) {
                target.writeText("concurrent target")
                targetCreated = true
            }
        }

        assertEquals(FileTransferOperations.Result.TARGET_EXISTS, result)
        assertTrue(source.isFile)
        assertEquals("concurrent target", target.readText())
        assertEquals(listOf("copy.cbz"), destination.list()?.toList())
    }

    @Test
    fun cutSourceDeletionFailureRemovesPublishedCopyAndPreservesSource() {
        val sourcePath = File(temporaryFolder.root, "undeletable-source.cbz").apply {
            writeText("source data")
        }
        val source = NonDeletableFile(sourcePath.path)
        val destination = temporaryFolder.newFolder("undeletable-destination")
        val target = File(destination, "copy.cbz")

        val result = FileTransferOperations.transfer(source, target, moveSource = true)

        assertEquals(FileTransferOperations.Result.SOURCE_DELETE_FAILED, result)
        assertTrue(sourcePath.isFile)
        assertEquals("source data", sourcePath.readText())
        assertFalse(target.exists())
        assertTrue(destination.list().orEmpty().isEmpty())
    }

    @Test
    fun interruptedCopyKeepsSourceAndRemovesTemporaryOutput() {
        val source = File(temporaryFolder.root, "source.cbz").apply {
            writeBytes(ByteArray(1024 * 1024) { (it % 251).toByte() })
        }
        val destination = temporaryFolder.newFolder("interrupted-destination")
        val target = File(destination, "copy.cbz")

        Thread.currentThread().interrupt()
        val result = try {
            FileTransferOperations.transfer(source, target, moveSource = true)
        } finally {
            Thread.interrupted()
        }

        assertEquals(FileTransferOperations.Result.FAILED, result)
        assertTrue(source.isFile)
        assertFalse(target.exists())
        assertTrue(destination.list().orEmpty().isEmpty())
    }

    @Test
    fun invalidSourceDestinationAndSelfTargetAreRejectedWithoutChangingSource() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("source data") }
        val sourceDirectory = temporaryFolder.newFolder("source-directory")
        val destination = temporaryFolder.newFolder("valid-destination")
        val destinationFile = File(temporaryFolder.root, "destination-file").apply { writeText("x") }

        assertEquals(
            FileTransferOperations.Result.SOURCE_NOT_FILE,
            FileTransferOperations.transfer(sourceDirectory, File(destination, "copy.cbz"), false)
        )
        assertEquals(
            FileTransferOperations.Result.DESTINATION_NOT_DIRECTORY,
            FileTransferOperations.transfer(source, File(temporaryFolder.root, "missing/copy.cbz"), false)
        )
        assertEquals(
            FileTransferOperations.Result.DESTINATION_NOT_DIRECTORY,
            FileTransferOperations.transfer(source, File(destinationFile, "copy.cbz"), false)
        )
        assertEquals(
            FileTransferOperations.Result.TARGET_EXISTS,
            FileTransferOperations.transfer(source, source, moveSource = false)
        )
        assertEquals("source data", source.readText())
    }

    private class NonDeletableFile(path: String) : File(path) {
        override fun delete(): Boolean = false
    }
}
