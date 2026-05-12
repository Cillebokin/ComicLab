package com.example.comiclab

import java.io.File
import java.util.Locale

object ComicClassifier {

    data class Progress(
        val stage: Stage,
        val completed: Int = 0,
        val total: Int = 0,
        val currentFileName: String = ""
    )

    data class Result(
        val outputDirectory: File?,
        val totalCount: Int,
        val copiedCount: Int,
        val failedCount: Int,
        val noTagCount: Int
    )

    enum class Stage {
        SCANNING,
        COPYING
    }

    fun classify(
        rootDirectory: File,
        startMarkerErrorTags: String,
        onProgress: (Progress) -> Unit
    ): Result {
        if (!rootDirectory.isDirectory) {
            return Result(
                outputDirectory = null,
                totalCount = 0,
                copiedCount = 0,
                failedCount = 0,
                noTagCount = 0
            )
        }

        onProgress(Progress(stage = Stage.SCANNING))
        val comicFiles = scanComicFiles(rootDirectory)
        if (comicFiles.isEmpty()) {
            return Result(
                outputDirectory = null,
                totalCount = 0,
                copiedCount = 0,
                failedCount = 0,
                noTagCount = 0
            )
        }

        val outputDirectory = createNextOutputDirectory(rootDirectory)
        var copiedCount = 0
        var failedCount = 0
        var noTagCount = 0

        comicFiles.forEachIndexed { index, comicFile ->
            onProgress(
                Progress(
                    stage = Stage.COPYING,
                    completed = index,
                    total = comicFiles.size,
                    currentFileName = comicFile.name
                )
            )

            val marker = CommonFunc.extractStartMarker(comicFile.name, startMarkerErrorTags)
            val targetDirectory = if (marker.isEmpty()) {
                noTagCount++
                File(outputDirectory, NO_TAG_DIRECTORY_NAME)
            } else {
                File(outputDirectory, sanitizePathSegment(marker).ifBlank { NO_TAG_DIRECTORY_NAME })
            }

            val copied = runCatching {
                targetDirectory.mkdirs()
                val targetFile = uniqueTargetFile(targetDirectory, comicFile.name)
                copyFile(comicFile, targetFile)
            }.getOrDefault(false)

            if (copied) {
                copiedCount++
            } else {
                failedCount++
            }
        }

        onProgress(
            Progress(
                stage = Stage.COPYING,
                completed = comicFiles.size,
                total = comicFiles.size
            )
        )

        return Result(
            outputDirectory = outputDirectory,
            totalCount = comicFiles.size,
            copiedCount = copiedCount,
            failedCount = failedCount,
            noTagCount = noTagCount
        )
    }

    private fun scanComicFiles(rootDirectory: File): List<File> {
        val result = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.add(rootDirectory)

        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val children = runCatching {
                directory.listFiles()?.toList().orEmpty()
            }.getOrDefault(emptyList())

            children.forEach { child ->
                when {
                    child.isDirectory && shouldScanDirectory(child) -> stack.add(child)
                    child.isFile && ComicArchive.isSupportedArchive(child) -> result.add(child)
                }
            }
        }

        return result.sortedBy { it.absolutePath.lowercase(Locale.ROOT) }
    }

    private fun shouldScanDirectory(directory: File): Boolean {
        return !directory.name.startsWith(".") && !isClassifyOutputDirectory(directory.name)
    }

    private fun createNextOutputDirectory(rootDirectory: File): File {
        var index = 0
        while (true) {
            val directory = File(
                rootDirectory,
                "$OUTPUT_DIRECTORY_PREFIX${String.format(Locale.ROOT, "%03d", index)}"
            )
            if (!directory.exists()) {
                directory.mkdirs()
                return directory
            }
            index++
        }
    }

    private fun uniqueTargetFile(targetDirectory: File, originalFileName: String): File {
        val directTarget = File(targetDirectory, originalFileName)
        if (!directTarget.exists()) {
            return directTarget
        }

        val extension = originalFileName.substringAfterLast('.', "")
        val baseName = if (extension.isBlank() || !originalFileName.contains('.')) {
            originalFileName
        } else {
            originalFileName.substringBeforeLast('.')
        }
        val suffix = if (extension.isBlank() || !originalFileName.contains('.')) {
            ""
        } else {
            ".$extension"
        }

        var index = 0
        while (true) {
            val target = File(
                targetDirectory,
                "$baseName${String.format(Locale.ROOT, "%03d", index)}$suffix"
            )
            if (!target.exists()) {
                return target
            }
            index++
        }
    }

    private fun copyFile(sourceFile: File, targetFile: File): Boolean {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        tempFile.delete()

        val copied = runCatching {
            sourceFile.copyTo(tempFile, overwrite = true, bufferSize = COPY_BUFFER_SIZE)
            tempFile.isFile && tempFile.length() == sourceFile.length()
        }.getOrDefault(false)

        if (!copied) {
            tempFile.delete()
            return false
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.delete()
            return false
        }

        targetFile.setLastModified(sourceFile.lastModified())
        return true
    }

    private fun sanitizePathSegment(value: String): String {
        return value
            .map { char ->
                if (char.code < 32 || char in INVALID_PATH_SEGMENT_CHARS) {
                    '_'
                } else {
                    char
                }
            }
            .joinToString("")
            .trim()
            .trim('.')
    }

    private fun isClassifyOutputDirectory(name: String): Boolean {
        return name.startsWith(OUTPUT_DIRECTORY_PREFIX) &&
            name.removePrefix(OUTPUT_DIRECTORY_PREFIX).all { it.isDigit() } &&
            name.length > OUTPUT_DIRECTORY_PREFIX.length
    }

    private const val OUTPUT_DIRECTORY_PREFIX = "ClassifyNList"
    private const val NO_TAG_DIRECTORY_NAME = "ClassifyNListNoTag"
    private const val COPY_BUFFER_SIZE = 1024 * 1024
    private val INVALID_PATH_SEGMENT_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
