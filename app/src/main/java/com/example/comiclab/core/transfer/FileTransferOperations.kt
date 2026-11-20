package com.example.comiclab.core.transfer

import java.io.File
import java.io.FileOutputStream
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
        onProgress: (FileOperationProgress) -> Unit = {}
    ): Result {
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
                if (Thread.currentThread().isInterrupted) return Result.FAILED
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
            val tempFile = File.createTempFile(
                ".comiclab-transfer-",
                ".tmp",
                destinationDirectory
            )
            temporaryFile = tempFile
            sourceFile.inputStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("File transfer interrupted")
                        }
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        output.write(buffer, 0, count)
                        copiedBytes += count
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
                    output.flush()
                }
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

            if (Thread.currentThread().isInterrupted) return Result.FAILED
            // 复制完成也不能证明此刻的源路径仍指向相同内容，回退时保留原件。
            val publication = TransferFileMove.publish(tempFile, targetFile, move.takeIf { moveSource }) {
                onProgress(FileOperationProgress(FileOperationProgress.Phase.PUBLISHING))
            }
            when (publication) {
                TransferFileMove.Publication.MOVED -> {
                    temporaryFile = null
                    if (moveSource) Result.COPIED_SOURCE_RETAINED else Result.SUCCESS
                }
                TransferFileMove.Publication.COPIED -> Result.COPIED_SOURCE_RETAINED
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

    private val INVALID_FILE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
