package com.example.comiclab.core.transfer

import java.io.File
import java.nio.file.Files

internal object FileTransferOperations {

    enum class Result {
        SUCCESS,
        SOURCE_NOT_FILE,
        DESTINATION_NOT_DIRECTORY,
        TARGET_EXISTS,
        COPIED_SOURCE_RETAINED,
        COPY_INCOMPLETE,
        FAILED
    }

    fun suggestedName(sourceName: String, destinationDirectory: File): String {
        val source = File(sourceName)
        val extension = source.extension
        val baseName = if (extension.isBlank()) source.name else source.nameWithoutExtension
        var index = 1
        while (true) {
            val candidate = if (extension.isBlank()) {
                "$baseName ($index)"
            } else {
                "$baseName ($index).$extension"
            }
            if (!File(destinationDirectory, candidate).exists()) {
                return candidate
            }
            index++
        }
    }

    fun targetFileName(sourceName: String, requestedName: String): String? {
        val trimmedName = requestedName.trim()
        if (!isValidFileName(trimmedName)) {
            return null
        }

        val extension = File(sourceName).extension
        val targetName = when {
            extension.isBlank() -> trimmedName
            trimmedName.endsWith(".$extension", ignoreCase = true) -> trimmedName
            else -> "$trimmedName.$extension"
        }
        return targetName.takeIf(::isValidFileName)
    }

    fun isValidFileName(name: String): Boolean {
        return name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            name.none { it.code < 32 || it in INVALID_FILE_NAME_CHARS }
    }

    fun transfer(
        sourceFile: File,
        targetFile: File,
        moveSource: Boolean,
        move: (File, File) -> TransferFileMove.Result = TransferFileMove::move,
        canContinue: () -> Boolean = { true },
        onProgress: (FileOperationProgress) -> Unit = {}
    ): Result {
        if (!canContinue()) return Result.FAILED
        if (!sourceFile.isFile || (moveSource && Files.isSymbolicLink(sourceFile.toPath()))) {
            return Result.SOURCE_NOT_FILE
        }
        val destinationDirectory = targetFile.parentFile
        if (destinationDirectory?.isDirectory != true) {
            return Result.DESTINATION_NOT_DIRECTORY
        }
        if (targetFile.exists() || sourceFile.absoluteFile == targetFile.absoluteFile) {
            return Result.TARGET_EXISTS
        }

        var temporaryFile: File? = null
        return try {
            if (Thread.currentThread().isInterrupted) return Result.FAILED
            if (moveSource) {
                onProgress(
                    FileOperationProgress(FileOperationProgress.Phase.MOVING, currentItemName = sourceFile.name)
                )
                if (Thread.currentThread().isInterrupted || !canContinue()) return Result.FAILED
                when (move(sourceFile, targetFile)) {
                    TransferFileMove.Result.MOVED -> return Result.SUCCESS
                    TransferFileMove.Result.TARGET_EXISTS -> return Result.TARGET_EXISTS
                    TransferFileMove.Result.FAILED -> return Result.FAILED
                    TransferFileMove.Result.UNAVAILABLE -> Unit
                }
            }
            val totalBytes = sourceFile.length().coerceAtLeast(0)
            var copiedBytes = 0L
            onProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.COPYING,
                    totalBytes = totalBytes,
                    totalItems = 1,
                    currentItemName = sourceFile.name
                )
            )
            if (!canContinue()) return Result.FAILED
            val tempFile = File.createTempFile(
                ".comiclab-transfer-",
                ".tmp",
                destinationDirectory
            )
            temporaryFile = tempFile
            val copiedSnapshot = TransferFileMove.copyFileWithDigest(
                sourceFile = sourceFile,
                targetFile = tempFile,
                interruptionMessage = "File transfer interrupted"
            ) { chunkBytes ->
                copiedBytes += chunkBytes
                onProgress(
                    FileOperationProgress(
                        phase = FileOperationProgress.Phase.COPYING,
                        completedBytes = copiedBytes,
                        totalBytes = totalBytes,
                        totalItems = 1,
                        currentItemName = sourceFile.name
                    )
                )
            }

            onProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.COPYING,
                    completedBytes = copiedBytes,
                    totalBytes = totalBytes,
                    completedItems = 1,
                    totalItems = 1,
                    currentItemName = sourceFile.name
                )
            )

            if (Thread.currentThread().isInterrupted || !canContinue()) return Result.FAILED
            val publication = TransferFileMove.publish(tempFile, targetFile, move.takeIf { moveSource }) {
                onProgress(FileOperationProgress(FileOperationProgress.Phase.PUBLISHING))
                check(canContinue())
            }
            when (publication) {
                TransferFileMove.Publication.MOVED -> {
                    temporaryFile = null
                    if (!moveSource || deleteVerifiedSource(copiedSnapshot, canContinue, onProgress)) {
                        Result.SUCCESS
                    } else {
                        Result.COPIED_SOURCE_RETAINED
                    }
                }
                TransferFileMove.Publication.COPIED -> {
                    if (!moveSource || deleteVerifiedSource(copiedSnapshot, canContinue, onProgress)) {
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
            temporaryFile?.delete()
        }
    }

    private fun deleteVerifiedSource(
        snapshot: TransferFileMove.CopiedFileSnapshot,
        canContinue: () -> Boolean,
        onProgress: (FileOperationProgress) -> Unit
    ): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        onProgress(
            FileOperationProgress(
                phase = FileOperationProgress.Phase.DELETING,
                totalItems = 1,
                currentItemName = snapshot.file.name
            )
        )
        if (!canContinue() || !TransferFileMove.matchesMetadataAndContent(snapshot)) return false
        if (Thread.currentThread().isInterrupted || !canContinue() || !TransferFileMove.matchesMetadata(snapshot)) return false
        if (!snapshot.file.delete()) return false
        onProgress(
            FileOperationProgress(
                phase = FileOperationProgress.Phase.DELETING,
                completedItems = 1,
                totalItems = 1,
                currentItemName = snapshot.file.name
            )
        )
        return true
    }

    private val INVALID_FILE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
