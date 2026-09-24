package com.example.comiclab

import java.io.File
import java.util.Locale

data class MangaCollectionCoverSource(
    val file: File,
    val imageEntry: String
)

/**
 * Finds direct comic files for a folder explicitly marked as a bookcase.
 * Nested directories are ignored, while their presence does not invalidate
 * the selected folder. Archive inspection is injectable for unit tests.
 */
object MangaCollectionCoverPlanner {

    fun findCoverSources(
        directory: File,
        isSupportedArchive: (File) -> Boolean = ComicArchive::isSupportedArchive,
        hasUsableImage: (File) -> Boolean = { file ->
            ComicArchive.imageEntries(file).isNotEmpty()
        }
    ): List<File> {
        return candidateArchives(directory, isSupportedArchive)
            .filter { file -> runCatching { hasUsableImage(file) }.getOrDefault(false) }
    }

    fun findCoverSourcesWithEntries(
        directory: File,
        isSupportedArchive: (File) -> Boolean = ComicArchive::isSupportedArchive,
        findFirstImageEntry: (File) -> String? = { file ->
            ComicArchive.imageEntries(file).firstOrNull()
        }
    ): List<MangaCollectionCoverSource> {
        return candidateArchives(directory, isSupportedArchive).mapNotNull { file ->
            val entry = runCatching { findFirstImageEntry(file) }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            MangaCollectionCoverSource(file, entry)
        }
    }

    private fun candidateArchives(
        directory: File,
        isSupportedArchive: (File) -> Boolean
    ): List<File> {
        return try {
            val children = directory.listFiles()?.toList().orEmpty()
            children.asSequence()
                .filter { file ->
                    file.isFile &&
                        !file.name.startsWith(".") &&
                        runCatching { isSupportedArchive(file) }.getOrDefault(false)
                }
                .sortedWith(
                    compareBy<File> { it.name.lowercase(Locale.ROOT) }
                        .thenBy { it.name }
                )
                .toList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
