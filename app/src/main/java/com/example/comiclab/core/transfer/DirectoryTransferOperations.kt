package com.example.comiclab.core.transfer

import java.io.File
import java.io.IOException
import java.nio.file.Files

internal object DirectoryTransferOperations {

    enum class CreateDirectoryResult {
        SUCCESS,
        PARENT_NOT_DIRECTORY,
        EMPTY_NAME,
        INVALID_NAME,
        HIDDEN_NAME,
        TARGET_EXISTS,
        FAILED
    }

    enum class Result {
        SUCCESS,
        SOURCE_NOT_DIRECTORY,
        DESTINATION_NOT_DIRECTORY,
        DESTINATION_INSIDE_SOURCE,
        INVALID_TARGET_NAME,
        TARGET_EXISTS,
        COPIED_SOURCE_RETAINED,
        COPY_INCOMPLETE,
        FAILED
    }

    fun suggestedName(sourceName: String, destinationDirectory: File): String {
        var index = 1
        while (true) {
            val candidate = "$sourceName ($index)"
            if (!File(destinationDirectory, candidate).exists()) {
                return candidate
            }
            index++
        }
    }

    fun suggestedNewFolderName(parentDirectory: File): String {
        var index = 0
        while (true) {
            val candidate = if (index == 0) "New Folder" else "New Folder ($index)"
            if (!File(parentDirectory, candidate).exists()) {
                return candidate
            }
            index++
        }
    }

    fun createDirectory(parentDirectory: File, requestedName: String): CreateDirectoryResult {
        if (!parentDirectory.isDirectory) {
            return CreateDirectoryResult.PARENT_NOT_DIRECTORY
        }

        val name = requestedName.trim()
        directoryNameError(name)?.let { return it }

        val targetDirectory = File(parentDirectory, name)
        if (targetDirectory.exists()) {
            return CreateDirectoryResult.TARGET_EXISTS
        }
        if (targetDirectory.mkdir()) {
            return CreateDirectoryResult.SUCCESS
        }
        return if (targetDirectory.exists()) {
            CreateDirectoryResult.TARGET_EXISTS
        } else {
            CreateDirectoryResult.FAILED
        }
    }

    fun directoryNameError(name: String): CreateDirectoryResult? = when {
        name.isBlank() -> CreateDirectoryResult.EMPTY_NAME
        name == "." || name == ".." || name.any { it.code < 32 || it in INVALID_DIRECTORY_NAME_CHARS } ->
            CreateDirectoryResult.INVALID_NAME

        name.startsWith(".") -> CreateDirectoryResult.HIDDEN_NAME
        else -> null
    }

    fun isDestinationInsideSource(sourceDirectory: File, destinationDirectory: File): Boolean {
        return runCatching {
            val sourcePath = sourceDirectory.canonicalFile.toPath()
            val destinationPath = destinationDirectory.canonicalFile.toPath()
            destinationPath.startsWith(sourcePath)
        }.getOrDefault(true)
    }

    fun transfer(
        sourceDirectory: File,
        destinationDirectory: File,
        targetName: String,
        moveSource: Boolean,
        move: (File, File) -> TransferFileMove.Result = TransferFileMove::move,
        onProgress: (FileOperationProgress) -> Unit = {}
    ): Result {
        if (!sourceDirectory.isDirectory) {
            return Result.SOURCE_NOT_DIRECTORY
        }
        if (Files.isSymbolicLink(sourceDirectory.toPath())) {
            return Result.FAILED
        }
        if (!destinationDirectory.isDirectory) {
            return Result.DESTINATION_NOT_DIRECTORY
        }
        if (directoryNameError(targetName) != null) {
            return Result.INVALID_TARGET_NAME
        }
        if (isDestinationInsideSource(sourceDirectory, destinationDirectory)) {
            return Result.DESTINATION_INSIDE_SOURCE
        }

        val targetDirectory = File(destinationDirectory, targetName)
        if (targetDirectory.exists()) {
            return Result.TARGET_EXISTS
        }

        var stagingDirectory: File? = null
        return try {
            onProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.SCANNING,
                    currentItemName = sourceDirectory.name
                )
            )
            val summary = DirectoryFileTree.scan(
                directory = sourceDirectory,
                rejectSymbolicLinks = true,
                onProgress = onProgress
            )
            if (moveSource) {
                onProgress(FileOperationProgress(FileOperationProgress.Phase.MOVING, currentItemName = sourceDirectory.name))
                if (Thread.currentThread().isInterrupted) return Result.FAILED
                when (move(sourceDirectory, targetDirectory)) {
                    TransferFileMove.Result.MOVED -> return Result.SUCCESS
                    TransferFileMove.Result.TARGET_EXISTS -> return Result.TARGET_EXISTS
                    TransferFileMove.Result.FAILED -> return Result.FAILED
                    TransferFileMove.Result.UNAVAILABLE -> Unit
                }
            }
            onProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.COPYING,
                    totalBytes = summary.totalBytes,
                    totalItems = summary.totalItems,
                    currentItemName = sourceDirectory.name
                )
            )
            val staging = Files.createTempDirectory(
                destinationDirectory.toPath(),
                STAGING_DIRECTORY_PREFIX
            ).toFile()
            stagingDirectory = staging
            val copyState = CopyState(summary, onProgress)
            val copiedSnapshot = CopiedTreeSnapshot()
            copyDirectoryContents(sourceDirectory, staging, copyState, copiedSnapshot, isRoot = true)
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Directory transfer interrupted")
            }

            val publication = TransferFileMove.publish(staging, targetDirectory, move.takeIf { moveSource }) {
                onProgress(FileOperationProgress(FileOperationProgress.Phase.PUBLISHING))
            }
            when (publication) {
                TransferFileMove.Publication.MOVED -> {
                    stagingDirectory = null
                    if (!moveSource || deleteVerifiedSources(sourceDirectory, copiedSnapshot, onProgress)) {
                        Result.SUCCESS
                    } else {
                        Result.COPIED_SOURCE_RETAINED
                    }
                }
                TransferFileMove.Publication.COPIED -> {
                    if (!moveSource || deleteVerifiedSources(sourceDirectory, copiedSnapshot, onProgress)) {
                        Result.SUCCESS
                    } else {
                        Result.COPIED_SOURCE_RETAINED
                    }
                }
                TransferFileMove.Publication.TARGET_EXISTS -> Result.TARGET_EXISTS
                TransferFileMove.Publication.INCOMPLETE -> Result.COPY_INCOMPLETE
                TransferFileMove.Publication.FAILED -> Result.FAILED
            }
        } catch (_: InterruptedException) {
            Result.FAILED
        } catch (_: Exception) {
            Result.FAILED
        } finally {
            stagingDirectory?.let {
                val interrupted = Thread.interrupted()
                try {
                    DirectoryFileTree.deleteRecursively(it)
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun copyDirectoryContents(
        sourceDirectory: File,
        targetDirectory: File,
        state: CopyState,
        snapshot: CopiedTreeSnapshot,
        isRoot: Boolean
    ) {
        val entries = sourceDirectory.listFiles()
            ?: throw IOException("Could not read source directory")

        val recordedChildNames = HashSet<String>(entries.size)
        entries.forEach { sourceEntry ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Directory transfer interrupted")
            }
            if (Files.isSymbolicLink(sourceEntry.toPath())) {
                throw IOException("Symbolic links are not supported")
            }

            recordedChildNames.add(sourceEntry.name)
            val targetEntry = File(targetDirectory, sourceEntry.name)
            when {
                sourceEntry.isDirectory -> {
                    if (!targetEntry.mkdir()) {
                        throw IOException("Could not create target directory")
                    }
                    copyDirectoryContents(sourceEntry, targetEntry, state, snapshot, isRoot = false)
                    state.recordItem(sourceEntry.name)
                }

                sourceEntry.isFile -> {
                    val fileSnapshot = TransferFileMove.copyFileWithDigest(
                        sourceFile = sourceEntry,
                        targetFile = targetEntry,
                        interruptionMessage = "Directory transfer interrupted"
                    ) { bytesCopied ->
                        state.recordBytes(bytesCopied, sourceEntry.name)
                    }
                    snapshot.files.add(fileSnapshot)
                    state.recordItem(sourceEntry.name)
                }
                else -> throw IOException("Unsupported source entry")
            }
        }

        // Post-order keeps nested directories before parents for non-recursive rmdir cleanup.
        snapshot.directories.add(CopiedDirectorySnapshot(sourceDirectory, recordedChildNames))
        if (isRoot) {
            state.recordItem(sourceDirectory.name)
        }
    }

    private fun deleteVerifiedSources(
        sourceDirectory: File,
        snapshot: CopiedTreeSnapshot,
        onProgress: (FileOperationProgress) -> Unit
    ): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        for (dirSnapshot in snapshot.directories) {
            if (Thread.currentThread().isInterrupted) return false
            val dir = dirSnapshot.directory
            if (Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory) return false
            val currentNames = dir.listFiles()?.mapTo(HashSet()) { it.name } ?: return false
            if (currentNames != dirSnapshot.childNames) return false
        }
        for (fileSnapshot in snapshot.files) {
            if (Thread.currentThread().isInterrupted) return false
            if (!TransferFileMove.matchesMetadataAndContent(fileSnapshot)) return false
        }

        val totalItems = snapshot.files.size + snapshot.directories.size
        var completedItems = 0
        onProgress(
            FileOperationProgress(
                phase = FileOperationProgress.Phase.DELETING,
                totalItems = totalItems,
                currentItemName = sourceDirectory.name
            )
        )
        for (fileSnapshot in snapshot.files) {
            if (Thread.currentThread().isInterrupted || !TransferFileMove.matchesMetadata(fileSnapshot)) return false
            if (!fileSnapshot.file.delete()) return false
            completedItems++
            onProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.DELETING,
                    completedItems = completedItems,
                    totalItems = totalItems,
                    currentItemName = fileSnapshot.file.name
                )
            )
        }
        for (dirSnapshot in snapshot.directories) {
            if (Thread.currentThread().isInterrupted || Files.isSymbolicLink(dirSnapshot.directory.toPath())) return false
            // File.delete on a directory uses rmdir and refuses if new files appeared concurrently.
            if (!dirSnapshot.directory.delete()) return false
            completedItems++
            onProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.DELETING,
                    completedItems = completedItems,
                    totalItems = totalItems,
                    currentItemName = dirSnapshot.directory.name
                )
            )
        }
        return !sourceDirectory.exists()
    }

    private class CopiedTreeSnapshot(
        val files: MutableList<TransferFileMove.CopiedFileSnapshot> = mutableListOf(),
        val directories: MutableList<CopiedDirectorySnapshot> = mutableListOf()
    )

    private data class CopiedDirectorySnapshot(
        val directory: File,
        val childNames: Set<String>
    )

    private class CopyState(
        private val summary: DirectoryFileTree.Summary,
        private val onProgress: (FileOperationProgress) -> Unit
    ) {
        private var completedBytes = 0L
        private var completedItems = 0

        fun recordBytes(bytes: Long, name: String) {
            completedBytes += bytes
            report(name)
        }

        fun recordItem(name: String) {
            completedItems++
            report(name)
        }

        private fun report(name: String) {
            onProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.COPYING,
                    completedBytes = completedBytes,
                    totalBytes = summary.totalBytes,
                    completedItems = completedItems,
                    totalItems = summary.totalItems,
                    currentItemName = name
                )
            )
        }
    }

    private val INVALID_DIRECTORY_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private const val STAGING_DIRECTORY_PREFIX = ".comiclab-transfer-"
}
