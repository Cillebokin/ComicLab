package com.example.comiclab.core.organize

import com.example.comiclab.ComicArchive
import com.example.comiclab.core.archive.ArchiveEntryLister
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ComicMergeOperations {
    private const val OUTPUT_DIRECTORY_PREFIX = "ComicLab_merged_"

    fun merge(
        rootDirectory: File,
        extractEntryToFile: (File, String, File) -> Boolean = ComicArchive::extractImageEntryToFile,
        createOutputDirectory: (File) -> Boolean = { it.mkdirs() },
        onProgress: (ComicArchiveMergeProgress) -> Unit
    ): ComicMergeResult {
        onProgress(ComicArchiveMergeProgress(ComicArchiveMergeProgress.Stage.SCANNING_ARCHIVES))
        val archives = comicArchivesInCurrentDirectory(rootDirectory)
        if (archives.isEmpty()) {
            return ComicMergeResult.Completed(
                outputDirectory = null,
                archiveCount = 0,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            )
        }

        val sources = mutableListOf<ComicMergeSource>()
        archives.forEachIndexed { index, archive ->
            onProgress(
                ComicArchiveMergeProgress(
                    stage = ComicArchiveMergeProgress.Stage.READING_ARCHIVES,
                    completed = index,
                    total = archives.size
                )
            )
            val entries = ComicArchive.imageEntries(archive)
            if (entries.isNotEmpty()) {
                sources.add(ComicMergeSource(archive, entries))
            }
        }
        onProgress(
            ComicArchiveMergeProgress(
                stage = ComicArchiveMergeProgress.Stage.READING_ARCHIVES,
                completed = archives.size,
                total = archives.size
            )
        )

        val totalImageCount = sources.sumOf { it.entries.size }
        if (totalImageCount <= 0) {
            return ComicMergeResult.Completed(
                outputDirectory = null,
                archiveCount = archives.size,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            )
        }

        val outputDirectory = buildOutputDirectory(rootDirectory)
        if (!createOutputDirectory(outputDirectory)) {
            return ComicMergeResult.Failed(ComicMergeResult.Failure.OUTPUT_DIRECTORY_CREATION)
        }

        val numberWidth = maxOf(MIN_FILE_NUMBER_WIDTH, totalImageCount.toString().length)
        var completedCount = 0
        var copiedCount = 0
        var failedCount = 0
        var nextOutputIndex = 1

        sources.forEach { source ->
            source.entries.forEach { entryName ->
                val outputName = buildMergedImageFileName(nextOutputIndex, numberWidth, entryName)
                val targetFile = File(outputDirectory, outputName)
                val stagedFile = runCatching {
                    File.createTempFile(STAGING_FILE_PREFIX, STAGING_FILE_SUFFIX, outputDirectory)
                }.getOrNull()
                val copied = stagedFile?.let { staged ->
                    try {
                        extractEntryToFile(source.archive, entryName, staged) &&
                            runCatching {
                                Files.move(staged.toPath(), targetFile.toPath())
                                true
                            }.getOrDefault(false)
                    } finally {
                        staged.delete()
                    }
                } ?: false
                completedCount++

                if (copied) {
                    copiedCount++
                    nextOutputIndex++
                } else {
                    failedCount++
                }

                onProgress(
                    ComicArchiveMergeProgress(
                        stage = ComicArchiveMergeProgress.Stage.MERGING_ARCHIVE_ENTRIES,
                        sourceArchiveName = source.archive.name,
                        completed = completedCount,
                        total = totalImageCount
                    )
                )
            }
        }

        return ComicMergeResult.Completed(
            outputDirectory = outputDirectory,
            archiveCount = archives.size,
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

    internal fun buildMergedImageFileName(index: Int, numberWidth: Int, entryName: String): String {
        val extension = entryName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in ArchiveEntryLister.imageExtensions }
            ?: DEFAULT_IMAGE_EXTENSION
        return "${index.toString().padStart(numberWidth, '0')}.$extension"
    }

    private fun comicArchivesInCurrentDirectory(rootDirectory: File): List<File> {
        return runCatching {
            rootDirectory.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        ComicArchive.isSupportedArchive(file) &&
                        !ComicArchive.isPdf(file)
                }
                ?.sortedWith { left, right ->
                    ArchiveEntryLister.naturalEntryNameComparator.compare(left.name, right.name)
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private data class ComicMergeSource(
        val archive: File,
        val entries: List<String>
    )

    private const val MIN_FILE_NUMBER_WIDTH = 4
    private const val DEFAULT_IMAGE_EXTENSION = "jpg"
    private const val STAGING_FILE_PREFIX = ".comiclab-merge-"
    private const val STAGING_FILE_SUFFIX = ".tmp"
}

internal data class ComicArchiveMergeProgress(
    val stage: Stage,
    val sourceArchiveName: String? = null,
    val completed: Int = 0,
    val total: Int = 0
) {
    enum class Stage {
        SCANNING_ARCHIVES,
        READING_ARCHIVES,
        MERGING_ARCHIVE_ENTRIES
    }
}

internal sealed interface ComicMergeResult {
    data class Completed(
        val outputDirectory: File?,
        val archiveCount: Int,
        val totalImageCount: Int,
        val copiedCount: Int,
        val failedCount: Int
    ) : ComicMergeResult

    data class Failed(val failure: Failure) : ComicMergeResult

    enum class Failure {
        OUTPUT_DIRECTORY_CREATION
    }
}
