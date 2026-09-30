package com.example.comiclab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryFileTreeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deleteReportsScanAndPerEntryProgressIncludingTheRoot() {
        val directory = temporaryFolder.newFolder("library").apply {
            File(this, "nested/empty").mkdirs()
            File(this, "nested/chapter.cbz").writeText("chapter")
            File(this, "readme.txt").writeText("index")
        }
        val progress = mutableListOf<FileOperationProgress>()

        val deleted = DirectoryFileTree.deleteRecursively(directory) { progress += it }

        assertTrue(deleted)
        assertFalse(directory.exists())
        assertEquals(FileOperationProgress.Phase.SCANNING, progress.first().phase)
        val deletionProgress = progress.filter {
            it.phase == FileOperationProgress.Phase.DELETING
        }
        assertEquals(6, deletionProgress.size)
        assertEquals(0, deletionProgress.first().completedItems)
        assertEquals(5, deletionProgress.last().completedItems)
        assertEquals(5, deletionProgress.last().totalItems)
    }

    @Test
    fun scanReportsProgressForManyZeroByteItems() {
        val directory = temporaryFolder.newFolder("many-files")
        repeat(65) { index ->
            assertTrue(File(directory, "chapter-$index.cbz").createNewFile())
        }
        val progress = mutableListOf<FileOperationProgress>()

        val summary = DirectoryFileTree.scan(directory, rejectSymbolicLinks = false) {
            progress += it
        }

        assertEquals(66, summary.totalItems)
        assertEquals(0L, summary.totalBytes)
        assertTrue(progress.any {
            it.phase == FileOperationProgress.Phase.SCANNING &&
                it.completedItems >= 32 && it.totalItems == 0
        })
        assertEquals(66, progress.last().completedItems)
    }
}
