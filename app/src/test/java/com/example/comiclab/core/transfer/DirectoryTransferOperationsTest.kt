package com.example.comiclab.core.transfer

import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileOperationProgress
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryTransferOperationsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun suggestedNameAddsFirstAvailableNumber() {
        val destination = temporaryFolder.newFolder("destination")
        File(destination, "Manga (1)").mkdir()
        File(destination, "Manga (2)").mkdir()

        assertEquals(
            "Manga (3)",
            DirectoryTransferOperations.suggestedName("Manga", destination)
        )
    }

    @Test
    fun suggestedNewFolderNameUsesBaseNameThenFirstAvailableNumber() {
        val parent = temporaryFolder.newFolder("parent")

        assertEquals("New Folder", DirectoryTransferOperations.suggestedNewFolderName(parent))

        File(parent, "New Folder").mkdir()
        File(parent, "New Folder (1)").writeText("occupied by a file")
        File(parent, "New Folder (3)").mkdir()

        assertEquals("New Folder (2)", DirectoryTransferOperations.suggestedNewFolderName(parent))
    }

    @Test
    fun createDirectoryCreatesTrimmedNameInParent() {
        val parent = temporaryFolder.newFolder("parent")

        val result = DirectoryTransferOperations.createDirectory(parent, " New Folder ")

        assertEquals(DirectoryTransferOperations.CreateDirectoryResult.SUCCESS, result)
        assertTrue(File(parent, "New Folder").isDirectory)
    }

    @Test
    fun createDirectoryRejectsEmptyName() {
        val parent = temporaryFolder.newFolder("parent")

        assertEquals(
            DirectoryTransferOperations.CreateDirectoryResult.EMPTY_NAME,
            DirectoryTransferOperations.createDirectory(parent, "   ")
        )
    }

    @Test
    fun createDirectoryRejectsInvalidName() {
        val parent = temporaryFolder.newFolder("parent")

        assertEquals(
            DirectoryTransferOperations.CreateDirectoryResult.INVALID_NAME,
            DirectoryTransferOperations.createDirectory(parent, "Bad:name")
        )
    }

    @Test
    fun createDirectoryRejectsExistingPath() {
        val parent = temporaryFolder.newFolder("parent")
        File(parent, "Existing").mkdir()

        assertEquals(
            DirectoryTransferOperations.CreateDirectoryResult.TARGET_EXISTS,
            DirectoryTransferOperations.createDirectory(parent, "Existing")
        )
    }

    @Test
    fun targetDirectoryNameRejectsEmptyOrPathLikeNames() {
        assertEquals(null, DirectoryTransferOperations.targetDirectoryName("  "))
        assertEquals(null, DirectoryTransferOperations.targetDirectoryName("../Other"))
        assertEquals(null, DirectoryTransferOperations.targetDirectoryName("Bad:name"))
        assertEquals("New Manga", DirectoryTransferOperations.targetDirectoryName(" New Manga "))
    }

    @Test
    fun copyRecursivelyCopiesNestedAndHiddenEntriesAndLeavesSource() {
        val source = temporaryFolder.newFolder("source")
        val nested = File(source, "nested/empty").apply { mkdirs() }
        File(source, ".hidden.cbz").writeText("hidden data")
        File(nested.parentFile, "chapter.cbz").writeText("chapter data")
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "Manga copy")

        val result = DirectoryTransferOperations.transfer(
            sourceDirectory = source,
            destinationDirectory = destination,
            targetName = target.name,
            moveSource = false
        )

        assertEquals(DirectoryTransferOperations.Result.SUCCESS, result)
        assertTrue(source.isDirectory)
        assertEquals("hidden data", File(target, ".hidden.cbz").readText())
        assertEquals("chapter data", File(target, "nested/chapter.cbz").readText())
        assertTrue(File(target, "nested/empty").isDirectory)
        assertEquals(listOf("Manga copy"), destination.list()?.toList())
    }

    @Test
    fun cutRecursivelyCopiesTreeAndRemovesSourceAfterCopy() {
        val source = temporaryFolder.newFolder("source").apply {
            File(this, "nested").mkdir()
            File(this, "nested/chapter.cbz").writeText("chapter data")
        }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "Manga")
        val progress = mutableListOf<FileOperationProgress>()

        val result = DirectoryTransferOperations.transfer(
            sourceDirectory = source,
            destinationDirectory = destination,
            targetName = target.name,
            moveSource = true,
            onProgress = { progress += it }
        )

        assertEquals(DirectoryTransferOperations.Result.SUCCESS, result)
        assertFalse(source.exists())
        assertEquals("chapter data", File(target, "nested/chapter.cbz").readText())
        assertTrue(progress.any { it.phase == FileOperationProgress.Phase.SCANNING })
        val copyComplete = progress.last { it.phase == FileOperationProgress.Phase.COPYING }
        assertEquals("chapter data".length.toLong(), copyComplete.completedBytes)
        assertEquals(3, copyComplete.completedItems)
        assertEquals(3, copyComplete.totalItems)
        val sourceDeleteComplete = progress.last {
            it.phase == FileOperationProgress.Phase.DELETING_SOURCE
        }
        assertEquals(3, sourceDeleteComplete.completedItems)
        assertEquals(3, sourceDeleteComplete.totalItems)
    }

    @Test
    fun copyOfEmptyFoldersReportsItemBasedProgress() {
        val source = temporaryFolder.newFolder("empty-source").apply {
            File(this, "nested/empty").mkdirs()
        }
        val destination = temporaryFolder.newFolder("destination")
        val progress = mutableListOf<FileOperationProgress>()

        val result = DirectoryTransferOperations.transfer(
            sourceDirectory = source,
            destinationDirectory = destination,
            targetName = "empty-copy",
            moveSource = false,
            onProgress = { progress += it }
        )

        assertEquals(DirectoryTransferOperations.Result.SUCCESS, result)
        val completed = progress.last { it.phase == FileOperationProgress.Phase.COPYING }
        assertEquals(0L, completed.totalBytes)
        assertEquals(3, completed.completedItems)
        assertEquals(3, completed.totalItems)
        assertTrue(File(destination, "empty-copy/nested/empty").isDirectory)
    }

    @Test
    fun existingTargetIsNotOverwritten() {
        val source = temporaryFolder.newFolder("source").apply {
            File(this, "chapter.cbz").writeText("source data")
        }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, "Manga").apply {
            mkdir()
            File(this, "chapter.cbz").writeText("target data")
        }

        val result = DirectoryTransferOperations.transfer(
            sourceDirectory = source,
            destinationDirectory = destination,
            targetName = target.name,
            moveSource = false
        )

        assertEquals(DirectoryTransferOperations.Result.TARGET_EXISTS, result)
        assertTrue(source.isDirectory)
        assertEquals("target data", File(target, "chapter.cbz").readText())
    }

    @Test
    fun targetCreatedDuringCopyIsNotOverwrittenAndStagingDirectoryIsRemoved() {
        val source = temporaryFolder.newFolder("racing-source").apply {
            File(this, "chapter.cbz").writeText("source data")
        }
        val destination = temporaryFolder.newFolder("racing-destination")
        val target = File(destination, "Manga")
        var targetCreated = false

        val result = DirectoryTransferOperations.transfer(
            sourceDirectory = source,
            destinationDirectory = destination,
            targetName = target.name,
            moveSource = false
        ) { progress ->
            if (!targetCreated && progress.phase == FileOperationProgress.Phase.COPYING &&
                progress.completedItems == progress.totalItems && progress.totalItems > 0
            ) {
                target.mkdir()
                File(target, "sentinel.txt").writeText("concurrent target")
                targetCreated = true
            }
        }

        assertEquals(DirectoryTransferOperations.Result.TARGET_EXISTS, result)
        assertTrue(File(source, "chapter.cbz").isFile)
        assertEquals("concurrent target", File(target, "sentinel.txt").readText())
        assertEquals(listOf("Manga"), destination.list()?.toList())
    }

    @Test
    fun cutSourceDeletionFailureKeepsCompleteTargetAndRemainingSourceDirectory() {
        val sourcePath = temporaryFolder.newFolder("undeletable-source").apply {
            File(this, "nested").mkdir()
            File(this, "nested/chapter.cbz").writeText("chapter data")
        }
        val source = NonDeletableDirectory(sourcePath.path)
        val destination = temporaryFolder.newFolder("undeletable-destination")
        val target = File(destination, "Manga")

        val result = DirectoryTransferOperations.transfer(
            sourceDirectory = source,
            destinationDirectory = destination,
            targetName = target.name,
            moveSource = true
        )

        assertEquals(DirectoryTransferOperations.Result.SOURCE_DELETE_FAILED, result)
        assertTrue(sourcePath.isDirectory)
        assertTrue(sourcePath.listFiles().orEmpty().isEmpty())
        assertEquals("chapter data", File(target, "nested/chapter.cbz").readText())
    }

    @Test
    fun pasteIntoSourceOrItsDescendantIsRejected() {
        val source = temporaryFolder.newFolder("source")
        val destinations = listOf(source, File(source, "nested").apply { mkdir() })

        destinations.forEach { destination ->
            val result = DirectoryTransferOperations.transfer(
                sourceDirectory = source,
                destinationDirectory = destination,
                targetName = "copy",
                moveSource = false
            )

            assertEquals(DirectoryTransferOperations.Result.DESTINATION_INSIDE_SOURCE, result)
            assertFalse(File(destination, "copy").exists())
        }
    }

    @Test
    fun interruptedCopyLeavesSourceAndDoesNotPublishPartialTargetOrStagingDirectory() {
        val source = temporaryFolder.newFolder("interrupted-source").apply {
            File(this, "nested").mkdirs()
            File(this, "nested/chapter.cbz").writeBytes(ByteArray(1024 * 1024) { (it % 251).toByte() })
        }
        val destination = temporaryFolder.newFolder("interrupted-destination")
        val target = File(destination, "copy")

        Thread.currentThread().interrupt()
        val result = try {
            DirectoryTransferOperations.transfer(source, destination, target.name, moveSource = true)
        } finally {
            Thread.interrupted()
        }

        assertEquals(DirectoryTransferOperations.Result.FAILED, result)
        assertTrue(File(source, "nested/chapter.cbz").isFile)
        assertFalse(target.exists())
        assertTrue(destination.list().orEmpty().isEmpty())
    }

    private class NonDeletableDirectory(path: String) : File(path) {
        override fun delete(): Boolean = false
    }
}
