package com.example.comiclab.ui.browser.transfer

import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileTransferOperations
import java.io.File

/** Owns the pending transfer and the paste-in-progress state for the browser. */
class FileTransferController {
    var pendingTransfer: PendingFileTransfer? = null
        private set

    var isTransferInProgress: Boolean = false
        private set

    fun isPendingTransferSource(file: File): Boolean {
        val transfer = pendingTransfer ?: return false
        val source = File(transfer.sourcePath)
        return if (transfer.isDirectory) {
            isSamePathOrDescendant(file, source)
        } else {
            file.absoluteFile.toPath().normalize() == source.absoluteFile.toPath().normalize()
        }
    }

    fun containsPendingTransferSource(directory: File): Boolean {
        val transfer = pendingTransfer ?: return false
        val source = File(transfer.sourcePath)
        return isSamePathOrDescendant(source, directory) ||
            (transfer.isDirectory && isSamePathOrDescendant(directory, source))
    }

    private fun isSamePathOrDescendant(candidate: File, directory: File): Boolean {
        return candidate.absoluteFile.toPath().normalize()
            .startsWith(directory.absoluteFile.toPath().normalize())
    }

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
        }
        runCatching { updateReaderProgress() }
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
