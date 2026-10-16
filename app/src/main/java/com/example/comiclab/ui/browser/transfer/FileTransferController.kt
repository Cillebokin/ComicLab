package com.example.comiclab.ui.browser.transfer

import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileTransferOperations

/** Owns the pending transfer and the paste-in-progress state for the browser. */
class FileTransferController {
    var pendingTransfer: PendingFileTransfer? = null
        private set

    var isTransferInProgress: Boolean = false
        private set

    fun begin(transfer: PendingFileTransfer): Boolean {
        if (pendingTransfer != null || isTransferInProgress) return false
        pendingTransfer = transfer
        return true
    }

    fun restore(transfer: PendingFileTransfer, sourceExists: Boolean): Boolean {
        if (!sourceExists || pendingTransfer != null || isTransferInProgress) return false
        pendingTransfer = transfer
        return true
    }

    fun startPaste(transfer: PendingFileTransfer): Boolean {
        if (pendingTransfer != transfer || isTransferInProgress) return false
        isTransferInProgress = true
        return true
    }

    internal fun finishPaste(
        transfer: PendingFileTransfer,
        result: FileTransferOperations.Result
    ): Boolean = finishPaste(
        transfer,
        succeeded = result == FileTransferOperations.Result.SUCCESS
    )

    internal fun finishPaste(
        transfer: PendingFileTransfer,
        result: DirectoryTransferOperations.Result
    ): Boolean = finishPaste(
        transfer,
        succeeded = result == DirectoryTransferOperations.Result.SUCCESS
    )

    private fun finishPaste(transfer: PendingFileTransfer, succeeded: Boolean): Boolean {
        if (pendingTransfer != transfer || !isTransferInProgress) return false
        isTransferInProgress = false
        if (succeeded) pendingTransfer = null
        return true
    }

    fun exit(): Boolean {
        if (isTransferInProgress) return false
        pendingTransfer = null
        return true
    }
}

internal object FileTransferMetadataUpdatePolicy {
    fun applyFileCut(
        updateFavorites: () -> Unit,
        updateReadingHistory: () -> Unit,
        updateReaderProgress: () -> Unit = {}
    ) {
        runCatching {
            updateFavorites()
            updateReadingHistory()
            updateReaderProgress()
        }
    }

    fun applyDirectoryCut(
        updateFavoritePaths: () -> Unit,
        updateFavoriteComics: () -> Unit,
        updateReadingHistory: () -> Unit,
        updateBookcasePaths: () -> Unit,
        updateReaderProgress: () -> Unit = {}
    ) {
        runCatching { updateFavoritePaths() }
        runCatching { updateFavoriteComics() }
        runCatching { updateReadingHistory() }
        runCatching { updateBookcasePaths() }
        runCatching { updateReaderProgress() }
    }
}
