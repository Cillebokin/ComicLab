package com.example.comiclab.core.organize

import com.example.comiclab.core.archive.ArchiveEntryLister
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ImageFileMergeOperations {

    fun merge(
        rootDirectory: File,
        copyImageToTarget: (File, File) -> Boolean = ::copyImageFileToTarget,
        createOutputDirectory: (File) -> Boolean = { it.mkdirs() },
        onProgress: (ImageFileMergeProgress) -> Unit
    ): ImageFileMergeResult {
        onProgress(ImageFileMergeProgress(ImageFileMergeProgress.Stage.SCANNING_DIRECTORIES))
        val directories = imageSourceDirectoriesInCurrentDirectory(rootDirectory)
        if (directories.isEmpty()) {
            return ImageFileMergeResult.Completed(
                outputDirectory = null,
                folderCount = 0,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            )
        }

        val sources = mutableListOf<ImageFileMergeSource>()
        directories.forEachIndexed { index, directory ->
            onProgress(
                ImageFileMergeProgress(
                    stage = ImageFileMergeProgress.Stage.READING_DIRECTORIES,
                    completed = index,
                    total = directories.size
                )
            )
            val images = imageFilesInDirectory(directory)
            if (images.isNotEmpty()) {
                sources.add(ImageFileMergeSource(directory, images))
            }
        }
        onProgress(
            ImageFileMergeProgress(
                stage = ImageFileMergeProgress.Stage.READING_DIRECTORIES,
                completed = directories.size,
                total = directories.size
            )
        )

        val totalImageCount = sources.sumOf { it.images.size }
        if (totalImageCount <= 0) {
            return ImageFileMergeResult.Completed(
                outputDirectory = null,
                folderCount = directories.size,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            )
        }

        val outputDirectory = buildOutputDirectory(rootDirectory)
        if (!createOutputDirectory(outputDirectory)) {
            return ImageFileMergeResult.Failed(ImageFileMergeResult.Failure.OUTPUT_DIRECTORY_CREATION)
        }

        val numberWidth = maxOf(MIN_FILE_NUMBER_WIDTH, totalImageCount.toString().length)
        var completedCount = 0
        var copiedCount = 0
        var failedCount = 0
        var nextOutputIndex = 1

        sources.forEach { source ->
            source.images.forEach { imageFile ->
                val outputName = buildOutputFileName(nextOutputIndex, numberWidth, imageFile.name)
                val targetFile = File(outputDirectory, outputName)
                val copied = copyImageToTarget(imageFile, targetFile)
                completedCount++

                if (copied) {
                    copiedCount++
                    nextOutputIndex++
                } else {
                    failedCount++
                }

                onProgress(
                    ImageFileMergeProgress(
                        stage = ImageFileMergeProgress.Stage.COPYING_IMAGES,
                        sourceDirectoryName = source.directory.name,
                        completed = completedCount,
                        total = totalImageCount
                    )
                )
            }
        }

        return ImageFileMergeResult.Completed(
            outputDirectory = outputDirectory,
            folderCount = directories.size,
            totalImageCount = totalImageCount,
            copiedCount = copiedCount,
            failedCount = failedCount
        )
    }

    internal fun buildOutputDirectory(rootDirectory: File, timestamp: Date = Date()): File {
        val formattedTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(timestamp)
        var outputDirectory = File(rootDirectory, "$OUTPUT_DIRECTORY_PREFIX$formattedTimestamp")
        var index = 1
        while (outputDirectory.exists()) {
            outputDirectory = File(
                rootDirectory,
                "$OUTPUT_DIRECTORY_PREFIX${formattedTimestamp}_${String.format(Locale.ROOT, "%03d", index)}"
            )
            index++
        }
        return outputDirectory
    }

    internal fun copyImageFileToTarget(sourceFile: File, targetFile: File): Boolean {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            return false
        }

        val parentDirectory = targetFile.parentFile ?: return false
        if (!parentDirectory.isDirectory && !parentDirectory.mkdirs()) {
            return false
        }

        val tempFile = runCatching {
            File.createTempFile(STAGING_FILE_PREFIX, STAGING_FILE_SUFFIX, parentDirectory)
        }.getOrNull() ?: return false

        return try {
            val copied = runCatching {
                sourceFile.copyTo(tempFile, overwrite = true, bufferSize = COPY_BUFFER_SIZE)
                tempFile.isFile && tempFile.length() == sourceFile.length()
            }.getOrDefault(false)
            if (!copied) {
                return false
            }

            try {
                Files.move(tempFile.toPath(), targetFile.toPath())
            } catch (_: Exception) {
                return false
            }

            targetFile.setLastModified(sourceFile.lastModified())
            true
        } finally {
            tempFile.delete()
        }
    }

    private fun imageSourceDirectoriesInCurrentDirectory(rootDirectory: File): List<File> {
        return runCatching {
            rootDirectory.listFiles()
                ?.filter { file ->
                    file.isDirectory &&
                        !file.name.startsWith(".") &&
                        !isGeneratedMergeDirectory(file.name)
                }
                ?.sortedWith { left, right ->
                    ArchiveEntryLister.naturalEntryNameComparator.compare(left.name, right.name)
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun imageFilesInDirectory(directory: File): List<File> {
        return runCatching {
            directory.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        file.extension.lowercase(Locale.ROOT) in IMAGE_FILE_EXTENSIONS
                }
                ?.sortedWith { left, right ->
                    ArchiveEntryLister.naturalEntryNameComparator.compare(left.name, right.name)
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun isGeneratedMergeDirectory(name: String): Boolean {
        return name.startsWith(COMIC_MERGE_DIRECTORY_PREFIX) ||
            name.startsWith(OUTPUT_DIRECTORY_PREFIX)
    }

    private fun buildOutputFileName(index: Int, numberWidth: Int, imageFileName: String): String {
        val extension = imageFileName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in IMAGE_FILE_EXTENSIONS }
            ?: DEFAULT_IMAGE_EXTENSION
        return "${index.toString().padStart(numberWidth, '0')}.$extension"
    }

    private data class ImageFileMergeSource(
        val directory: File,
        val images: List<File>
    )

    private const val OUTPUT_DIRECTORY_PREFIX = "ComicLab_file_merged_"
    private const val COMIC_MERGE_DIRECTORY_PREFIX = "ComicLab_merged_"
    private const val MIN_FILE_NUMBER_WIDTH = 4
    private const val DEFAULT_IMAGE_EXTENSION = "jpg"
    private const val COPY_BUFFER_SIZE = 1024 * 1024
    private const val STAGING_FILE_PREFIX = ".comiclab-file-merge-"
    private const val STAGING_FILE_SUFFIX = ".tmp"
    private val IMAGE_FILE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
}

internal data class ImageFileMergeProgress(
    val stage: Stage,
    val sourceDirectoryName: String? = null,
    val completed: Int = 0,
    val total: Int = 0
) {
    enum class Stage {
        SCANNING_DIRECTORIES,
        READING_DIRECTORIES,
        COPYING_IMAGES
    }
}

internal sealed interface ImageFileMergeResult {
    data class Completed(
        val outputDirectory: File?,
        val folderCount: Int,
        val totalImageCount: Int,
        val copiedCount: Int,
        val failedCount: Int
    ) : ImageFileMergeResult

    data class Failed(val failure: Failure) : ImageFileMergeResult

    enum class Failure {
        OUTPUT_DIRECTORY_CREATION
    }
}
