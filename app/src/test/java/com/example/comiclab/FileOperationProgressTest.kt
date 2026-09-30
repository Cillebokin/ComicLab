package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileOperationProgressTest {

    @Test
    fun copyPercentageUsesBytesForNonEmptyFiles() {
        val progress = FileOperationProgress(
            phase = FileOperationProgress.Phase.COPYING,
            completedBytes = 25,
            totalBytes = 100,
            totalItems = 2
        )

        assertEquals(25, FileOperationProgressPolicy.percentage(progress))
    }

    @Test
    fun copyPercentageDoesNotFinishBeforeAllItems() {
        val progress = FileOperationProgress(
            phase = FileOperationProgress.Phase.COPYING,
            completedBytes = 100,
            totalBytes = 100,
            completedItems = 1,
            totalItems = 2
        )

        assertEquals(99, FileOperationProgressPolicy.percentage(progress))
    }

    @Test
    fun zeroByteOperationsUseCompletedItemCount() {
        val progress = FileOperationProgress(
            phase = FileOperationProgress.Phase.COPYING,
            completedItems = 3,
            totalItems = 4
        )

        assertEquals(75, FileOperationProgressPolicy.percentage(progress))
    }

    @Test
    fun scanningUsesIndeterminateProgress() {
        val progress = FileOperationProgress(
            phase = FileOperationProgress.Phase.SCANNING,
            completedItems = 12
        )

        assertNull(FileOperationProgressPolicy.percentage(progress))
    }

    @Test
    fun uncountedSingleFileDeletionUsesIndeterminateProgress() {
        val progress = FileOperationProgress(
            phase = FileOperationProgress.Phase.DELETING,
            currentItemName = "chapter.cbz"
        )

        assertNull(FileOperationProgressPolicy.percentage(progress))
    }
}
