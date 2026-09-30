package com.example.comiclab

import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

internal object FileTransferOperations {

    enum class Result {
        SUCCESS,
        SOURCE_NOT_FILE,
        DESTINATION_NOT_DIRECTORY,
        TARGET_EXISTS,
        SOURCE_DELETE_FAILED,
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
        onProgress: (FileOperationProgress) -> Unit = {}
    ): Result {
        if (!sourceFile.isFile) {
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

            try {
                Files.move(tempFile.toPath(), targetFile.toPath())
                temporaryFile = null
            } catch (_: FileAlreadyExistsException) {
                return Result.TARGET_EXISTS
            }

            if (moveSource) {
                onProgress(
                    FileOperationProgress(
                        phase = FileOperationProgress.Phase.DELETING_SOURCE,
                        totalItems = 1,
                        currentItemName = sourceFile.name
                    )
                )
            }
            val sourceDeleted = !moveSource ||
                runCatching { sourceFile.delete() }.getOrDefault(false)
            if (!sourceDeleted) {
                runCatching { targetFile.delete() }
                return Result.SOURCE_DELETE_FAILED
            }
            if (moveSource) {
                onProgress(
                    FileOperationProgress(
                        phase = FileOperationProgress.Phase.DELETING_SOURCE,
                        completedItems = 1,
                        totalItems = 1,
                        currentItemName = sourceFile.name
                    )
                )
            }
            Result.SUCCESS
        } catch (_: InterruptedException) {
            Result.FAILED
        } catch (_: Exception) {
            if (targetFile.exists()) Result.TARGET_EXISTS else Result.FAILED
        } finally {
            temporaryFile?.delete()
        }
    }

    private val INVALID_FILE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
