package com.example.comiclab

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque

internal object DirectoryFileTree {

    data class Summary(
        val totalBytes: Long,
        val totalItems: Int
    )

    fun scan(
        directory: File,
        rejectSymbolicLinks: Boolean,
        onProgress: (FileOperationProgress) -> Unit
    ): Summary {
        if (!directory.isDirectory &&
            (rejectSymbolicLinks || !Files.isSymbolicLink(directory.toPath()))
        ) {
            throw IOException("Source is not a readable directory")
        }

        val state = ScanState(onProgress)
        val pendingEntries = ArrayDeque<File>()
        pendingEntries.addLast(directory)
        while (pendingEntries.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Directory scan interrupted")
            }

            val entry = pendingEntries.removeLast()
            if (Files.isSymbolicLink(entry.toPath())) {
                if (rejectSymbolicLinks) {
                    throw IOException("Symbolic links are not supported")
                }
                state.recordItem(entry.name)
                continue
            }

            when {
                entry.isDirectory -> {
                    state.recordItem(entry.name)
                    val children = entry.listFiles()
                        ?: throw IOException("Could not read directory")
                    children.forEach { child -> pendingEntries.addLast(child) }
                }

                entry.isFile -> state.recordItem(entry.name, entry.length().coerceAtLeast(0))
                rejectSymbolicLinks -> throw IOException("Unsupported source entry")
                else -> state.recordItem(entry.name)
            }
        }

        onProgress(
            FileOperationProgress(
                phase = FileOperationProgress.Phase.SCANNING,
                completedBytes = state.totalBytes,
                completedItems = state.totalItems,
                currentItemName = directory.name
            )
        )
        return Summary(state.totalBytes, state.totalItems)
    }

    fun deleteRecursively(
        directory: File,
        onProgress: (FileOperationProgress) -> Unit = {}
    ): Boolean {
        if (!directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            return false
        }

        return runCatching {
            onProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.SCANNING,
                    currentItemName = directory.name
                )
            )
            val summary = scan(
                directory = directory,
                rejectSymbolicLinks = false,
                onProgress = onProgress
            )
            deleteWithSummary(
                directory = directory,
                summary = summary,
                phase = FileOperationProgress.Phase.DELETING,
                onProgress = onProgress
            )
        }.getOrDefault(false)
    }

    fun deleteWithSummary(
        directory: File,
        summary: Summary,
        phase: FileOperationProgress.Phase,
        onProgress: (FileOperationProgress) -> Unit
    ): Boolean {
        return runCatching {
            val state = DeleteState(summary.totalItems, phase, onProgress)
            onProgress(
                FileOperationProgress(
                    phase = phase,
                    totalItems = summary.totalItems,
                    currentItemName = directory.name
                )
            )
            deleteEntry(directory, state)
        }.getOrDefault(false)
    }

    private fun deleteEntry(entry: File, state: DeleteState): Boolean {
        val pendingEntries = ArrayDeque<PendingDeletion>()
        pendingEntries.addLast(PendingDeletion(entry, deleteAfterChildren = false))
        while (pendingEntries.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Directory deletion interrupted")
            }

            val pending = pendingEntries.removeLast()
            val current = pending.file
            // 删除时把符号链接当作叶子处理，避免递归触及链接目标
            if (!pending.deleteAfterChildren &&
                !Files.isSymbolicLink(current.toPath()) && current.isDirectory
            ) {
                val children = current.listFiles() ?: return false
                pendingEntries.addLast(PendingDeletion(current, deleteAfterChildren = true))
                children.forEach { child ->
                    pendingEntries.addLast(PendingDeletion(child, deleteAfterChildren = false))
                }
                continue
            }

            if (!current.delete()) {
                return false
            }
            state.recordDeleted(current.name)
        }
        return true
    }

    private data class PendingDeletion(
        val file: File,
        val deleteAfterChildren: Boolean
    )

    private class ScanState(
        private val onProgress: (FileOperationProgress) -> Unit
    ) {
        var totalBytes = 0L
            private set
        var totalItems = 0
            private set

        fun recordItem(name: String, bytes: Long = 0) {
            totalItems++
            totalBytes += bytes
            if (totalItems % SCAN_PROGRESS_INTERVAL == 0) {
                onProgress(
                    FileOperationProgress(
                        phase = FileOperationProgress.Phase.SCANNING,
                        completedBytes = totalBytes,
                        completedItems = totalItems,
                        currentItemName = name
                    )
                )
            }
        }
    }

    private class DeleteState(
        private val totalItems: Int,
        private val phase: FileOperationProgress.Phase,
        private val onProgress: (FileOperationProgress) -> Unit
    ) {
        private var completedItems = 0

        fun recordDeleted(name: String) {
            completedItems++
            onProgress(
                FileOperationProgress(
                    phase = phase,
                    completedItems = completedItems,
                    totalItems = totalItems,
                    currentItemName = name
                )
            )
        }
    }

    private const val SCAN_PROGRESS_INTERVAL = 32
}
