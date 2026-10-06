package com.example.comiclab

import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileTransferOperations
import com.example.comiclab.ui.browser.transfer.FileTransferController
import com.example.comiclab.ui.browser.transfer.FileTransferMetadataUpdatePolicy
import com.example.comiclab.ui.browser.transfer.FileTransferMode
import com.example.comiclab.ui.browser.transfer.PendingFileTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferControllerTest {

    @Test
    fun beginKeepsFirstPendingTransferAndRejectsAnother() {
        val controller = FileTransferController()
        val first = transfer("/first.cbz", FileTransferMode.COPY)
        val second = transfer("/second.cbz", FileTransferMode.CUT)

        assertTrue(controller.begin(first))
        assertFalse(controller.begin(second))
        assertEquals(first, controller.pendingTransfer)
        assertFalse(controller.isTransferInProgress)
    }

    @Test
    fun restoreRequiresAnExistingSourceAndDoesNotReplacePendingTransfer() {
        val controller = FileTransferController()
        val restored = transfer("/restored", FileTransferMode.CUT, isDirectory = true)

        assertFalse(controller.restore(restored, sourceExists = false))
        assertNull(controller.pendingTransfer)
        assertTrue(controller.restore(restored, sourceExists = true))
        assertFalse(controller.isTransferInProgress)

        val other = transfer("/other.cbz", FileTransferMode.COPY)
        assertFalse(controller.restore(other, sourceExists = true))
        assertEquals(restored, controller.pendingTransfer)
    }

    @Test
    fun startPasteRequiresCurrentTransferAndRejectsASecondPaste() {
        val controller = FileTransferController()
        val pending = transfer("/source.cbz", FileTransferMode.COPY)
        val other = transfer("/other.cbz", FileTransferMode.CUT)
        controller.begin(pending)

        assertFalse(controller.startPaste(other))
        assertTrue(controller.startPaste(pending))
        assertFalse(controller.startPaste(pending))
        assertTrue(controller.isTransferInProgress)
        assertEquals(pending, controller.pendingTransfer)
    }

    @Test
    fun successfulPasteClearsPendingTransferAndBusyState() {
        val controller = FileTransferController()
        val pending = transfer("/source.cbz", FileTransferMode.COPY)
        controller.begin(pending)
        controller.startPaste(pending)

        assertTrue(controller.finishPaste(pending, FileTransferOperations.Result.SUCCESS))
        assertNull(controller.pendingTransfer)
        assertFalse(controller.isTransferInProgress)
    }

    @Test
    fun failedPasteRetainsPendingTransferForRetryOrExit() {
        val controller = FileTransferController()
        val pending = transfer("/source", FileTransferMode.CUT, isDirectory = true)
        controller.begin(pending)
        controller.startPaste(pending)

        assertTrue(controller.finishPaste(pending, FileTransferOperations.Result.TARGET_EXISTS))
        assertEquals(pending, controller.pendingTransfer)
        assertFalse(controller.isTransferInProgress)
        assertTrue(controller.startPaste(pending))
    }

    @Test
    fun exitIsIgnoredWhilePasteRunsAndClearsPendingAfterFailure() {
        val controller = FileTransferController()
        val pending = transfer("/source.cbz", FileTransferMode.COPY)
        controller.begin(pending)
        controller.startPaste(pending)

        assertFalse(controller.exit())
        assertEquals(pending, controller.pendingTransfer)
        controller.finishPaste(pending, FileTransferOperations.Result.FAILED)

        assertTrue(controller.exit())
        assertNull(controller.pendingTransfer)
        assertFalse(controller.isTransferInProgress)
    }

    @Test
    fun fileResultsClearOnlyAfterSuccessIncludingDeleteFailure() {
        FileTransferOperations.Result.values().forEach { result ->
            val controller = FileTransferController()
            val pending = transfer("/source.cbz", FileTransferMode.CUT)
            controller.begin(pending)
            controller.startPaste(pending)

            assertTrue(controller.finishPaste(pending, result))
            assertEquals(
                result != FileTransferOperations.Result.SUCCESS,
                controller.pendingTransfer != null
            )
            assertFalse(controller.isTransferInProgress)
        }
    }

    @Test
    fun directoryResultsClearOnlyAfterSuccessIncludingDeleteFailure() {
        DirectoryTransferOperations.Result.values().forEach { result ->
            val controller = FileTransferController()
            val pending = transfer("/source", FileTransferMode.CUT, isDirectory = true)
            controller.begin(pending)
            controller.startPaste(pending)

            assertTrue(controller.finishPaste(pending, result))
            assertEquals(
                result != DirectoryTransferOperations.Result.SUCCESS,
                controller.pendingTransfer != null
            )
            assertFalse(controller.isTransferInProgress)
        }
    }

    @Test
    fun fileCutMetadataKeepsFavoriteAndHistoryInsideOneFailureBoundary() {
        val calls = mutableListOf<String>()

        FileTransferMetadataUpdatePolicy.applyFileCut(
            updateFavorites = {
                calls += "favorites"
                error("favorite store failure")
            },
            updateReadingHistory = { calls += "reading-history" }
        )

        assertEquals(listOf("favorites"), calls)
    }

    @Test
    fun directoryCutMetadataRunsFourIsolatedUpdatesInOrder() {
        val calls = mutableListOf<String>()

        FileTransferMetadataUpdatePolicy.applyDirectoryCut(
            updateFavoritePaths = {
                calls += "favorite-paths"
                error("favorite path store failure")
            },
            updateFavoriteComics = {
                calls += "favorite-comics"
                error("favorite comic store failure")
            },
            updateReadingHistory = {
                calls += "reading-history"
                error("reading history store failure")
            },
            updateBookcasePaths = {
                calls += "bookcase-paths"
                error("bookcase store failure")
            }
        )

        assertEquals(
            listOf("favorite-paths", "favorite-comics", "reading-history", "bookcase-paths"),
            calls
        )
    }

    private fun transfer(
        sourcePath: String,
        mode: FileTransferMode,
        isDirectory: Boolean = false
    ) = PendingFileTransfer(sourcePath, mode, isDirectory)
}
