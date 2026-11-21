package com.example.comiclab.core.transfer

import com.example.comiclab.core.transfer.FileOperationProgress
import com.example.comiclab.core.transfer.FileTransferOperations
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    fun cutUsesDirectMoveWithoutCopyingOrDeletingSource() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("comic data") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "moved.cbz")
        val progress = mutableListOf<FileOperationProgress>()

        val result = FileTransferOperations.transfer(
            source,
            target,
            moveSource = true,
            move = { from, to ->
                Files.move(from.toPath(), to.toPath())
                TransferFileMove.Result.MOVED
            },
            onProgress = { progress += it }
        )

        assertEquals(FileTransferOperations.Result.SUCCESS, result)
        assertFalse(source.exists())
        assertEquals("comic data", target.readText())
        assertEquals(listOf(FileOperationProgress.Phase.MOVING), progress.map { it.phase })
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
    fun unavailableSourceMovePublishesWithoutSecondCopyAndDeletesVerifiedSource() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("source data") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "copy.cbz")
        val phases = mutableListOf<FileOperationProgress.Phase>()

        val result = FileTransferOperations.transfer(
            source,
            target,
            moveSource = true,
            move = { _, _ -> TransferFileMove.Result.UNAVAILABLE },
            onProgress = { phases += it.phase }
        )

        assertEquals(FileTransferOperations.Result.SUCCESS, result)
        assertFalse(source.exists())
        assertEquals("source data", target.readText())
        assertEquals(listOf(target.name), destination.list()?.toList())
        assertTrue(FileOperationProgress.Phase.PUBLISHING in phases)
        assertTrue(FileOperationProgress.Phase.DELETING in phases)
    }

    @Test
    fun cutFallbackKeepsTargetCreatedBeforeExclusivePublication() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("source data") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, source.name)

        val result = FileTransferOperations.transfer(source, target, moveSource = true) {
            if (it.phase == FileOperationProgress.Phase.PUBLISHING) target.writeText("concurrent target")
        }

        assertEquals(FileTransferOperations.Result.TARGET_EXISTS, result)
        assertEquals("source data", source.readText())
        assertEquals("concurrent target", target.readText())
        assertEquals(listOf(target.name), destination.list()?.toList())
    }

    @Test
    fun atomicPublicationDoesNotDeleteARecreatedStagingPath() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("source data") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, source.name)
        var recreated: File? = null

        val result = FileTransferOperations.transfer(source, target, moveSource = true, move = { from, to ->
            if (from == source) {
                TransferFileMove.Result.UNAVAILABLE
            } else {
                Files.move(from.toPath(), to.toPath())
                from.writeText("new content")
                recreated = from
                TransferFileMove.Result.MOVED
            }
        })

        assertEquals(FileTransferOperations.Result.SUCCESS, result)
        assertEquals("new content", checkNotNull(recreated).readText())
        assertFalse(source.exists())
        assertEquals("source data", target.readText())
    }

    @Test
    fun cutFallbackWriteFailureKeepsSourceAndDoesNotPublishAStagedCopy() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("source data") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, source.name)

        val result = FileTransferOperations.transfer(source, target, moveSource = true) { progress ->
            if (progress.phase == FileOperationProgress.Phase.COPYING && progress.completedBytes > 0) {
                throw IOException("Injected failure after writing to the staging file")
            }
        }

        assertEquals(FileTransferOperations.Result.FAILED, result)
        assertEquals("source data", source.readText())
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

    @Test
    fun cutPreservesSourceChangedAfterItWasCopied() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("before") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, source.name)
        var modified = false

        val result = FileTransferOperations.transfer(source, target, moveSource = true) { progress ->
            if (!modified && progress.phase == FileOperationProgress.Phase.COPYING &&
                progress.completedItems == 1
            ) {
                val oldTime = source.lastModified()
                source.writeText("update")
                assertTrue(source.setLastModified(oldTime))
                modified = true
            }
        }

        assertTrue(modified)
        assertTrue("the updated source must still exist", source.isFile)
        assertEquals("update", source.readText())
        assertEquals("before", target.readText())
        assertEquals(FileTransferOperations.Result.COPIED_SOURCE_RETAINED, result)
    }

    @Test
    fun cutPreservesReplacementAtTheSourcePath() {
        val source = File(temporaryFolder.root, "source.cbz").apply { writeText("original") }
        val destination = temporaryFolder.newFolder("destination")
        val replacement = File(temporaryFolder.root, "replacement.cbz").apply { writeText("new file") }
        val target = File(destination, source.name)
        var replaced = false

        val result = FileTransferOperations.transfer(source, target, moveSource = true) { progress ->
            if (!replaced && progress.phase == FileOperationProgress.Phase.COPYING &&
                progress.completedItems == 1
            ) {
                Files.move(
                    replacement.toPath(),
                    source.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                replaced = true
            }
        }

        assertTrue(replaced)
        assertTrue("the replacement must still exist", source.isFile)
        assertEquals("new file", source.readText())
        assertEquals("original", target.readText())
        assertEquals(FileTransferOperations.Result.COPIED_SOURCE_RETAINED, result)
    }

}
