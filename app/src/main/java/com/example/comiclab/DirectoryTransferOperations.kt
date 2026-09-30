package com.example.comiclab

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

internal object DirectoryTransferOperations {

    enum class Result {
        SUCCESS,
        SOURCE_NOT_DIRECTORY,
        DESTINATION_NOT_DIRECTORY,
        DESTINATION_INSIDE_SOURCE,
        INVALID_TARGET_NAME,
        TARGET_EXISTS,
        SOURCE_DELETE_FAILED,
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

    fun targetDirectoryName(requestedName: String): String? {
        val name = requestedName.trim()
        return name.takeIf(::isValidDirectoryName)
    }

    fun isValidDirectoryName(name: String): Boolean {
        return name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            name.none { it.code < 32 || it in INVALID_DIRECTORY_NAME_CHARS }
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
        if (!isValidDirectoryName(targetName)) {
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
            copyDirectoryContents(sourceDirectory, staging, copyState, isRoot = true)
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Directory transfer interrupted")
            }

            try {
                Files.move(staging.toPath(), targetDirectory.toPath())
                stagingDirectory = null
            } catch (_: FileAlreadyExistsException) {
                return Result.TARGET_EXISTS
            }

            val sourceDeleted = !moveSource ||
                (!Thread.currentThread().isInterrupted &&
                    DirectoryFileTree.deleteWithSummary(
                        directory = sourceDirectory,
                        summary = summary,
                        phase = FileOperationProgress.Phase.DELETING_SOURCE,
                        onProgress = onProgress
                    ))
            if (!sourceDeleted) {
                return Result.SOURCE_DELETE_FAILED
            }
            Result.SUCCESS
        } catch (_: InterruptedException) {
            Result.FAILED
        } catch (_: Exception) {
            if (targetDirectory.exists()) Result.TARGET_EXISTS else Result.FAILED
        } finally {
            stagingDirectory?.deleteRecursively()
        }
    }

    private fun copyDirectoryContents(
        sourceDirectory: File,
        targetDirectory: File,
        state: CopyState,
        isRoot: Boolean
    ) {
        val entries = sourceDirectory.listFiles()
            ?: throw IOException("Could not read source directory")

        entries.forEach { sourceEntry ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Directory transfer interrupted")
            }
            if (Files.isSymbolicLink(sourceEntry.toPath())) {
                throw IOException("Symbolic links are not supported")
            }

            val targetEntry = File(targetDirectory, sourceEntry.name)
            when {
                sourceEntry.isDirectory -> {
                    if (!targetEntry.mkdir()) {
                        throw IOException("Could not create target directory")
                    }
                    copyDirectoryContents(sourceEntry, targetEntry, state, isRoot = false)
                    state.recordItem(sourceEntry.name)
                }

                sourceEntry.isFile -> {
                    copyFile(sourceEntry, targetEntry) { bytesCopied ->
                        state.recordBytes(bytesCopied, sourceEntry.name)
                    }
                    state.recordItem(sourceEntry.name)
                }
                else -> throw IOException("Unsupported source entry")
            }
        }

        if (isRoot) {
            state.recordItem(sourceDirectory.name)
        }
    }

    private fun copyFile(sourceFile: File, targetFile: File, onBytesCopied: (Long) -> Unit) {
        sourceFile.inputStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedException("Directory transfer interrupted")
                    }
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    output.write(buffer, 0, count)
                    onBytesCopied(count.toLong())
                }
                output.flush()
            }
        }
    }

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
