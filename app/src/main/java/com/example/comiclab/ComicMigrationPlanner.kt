package com.example.comiclab

import com.example.comiclab.ebook.ReaderFileDetector
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

class ComicMigrationPlanner(
    private val isComicFile: (File) -> Boolean = { file ->
        ReaderFileDetector.isSupported(file)
    }
) {

    fun scanDirectComicFiles(rootDirectory: File): List<File> {
        if (!rootDirectory.isDirectory) {
            return emptyList()
        }

        return runCatching {
            rootDirectory.listFiles()
                ?.filter { file -> file.isFile && isComicFile(file) }
                ?.sortedWith(filePathComparator)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun findMatchingDirectories(rootDirectory: File, startMarker: String): List<File> {
        val marker = startMarker.trim()
        if (!rootDirectory.isDirectory || marker.isEmpty()) {
            return emptyList()
        }

        val pending = ArrayDeque<File>()
        val visitedPaths = mutableSetOf<String>()
        val matches = mutableListOf<File>()
        pending.add(rootDirectory)

        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            if (!directory.isDirectory || !visitedPaths.add(directory.stablePath())) {
                continue
            }

            val children = runCatching {
                directory.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedWith(filePathComparator)
                    .orEmpty()
            }.getOrDefault(emptyList())

            children.forEach { child ->
                if (child.name.contains(marker, ignoreCase = true)) {
                    matches.add(child)
                }
                pending.add(child)
            }
        }

        return matches.distinctBy { it.stablePath() }.sortedWith(filePathComparator)
    }

    fun targetDirectoriesForSelection(
        matchingDirectories: List<File>,
        defaultDirectory: File
    ): List<File> {
        return matchingDirectories.ifEmpty { listOf(defaultDirectory) }
    }

    fun nextNoMatchDirectory(rootDirectory: File): File {
        var index = 0
        while (true) {
            val candidate = File(
                rootDirectory,
                "$NO_MATCH_DIRECTORY_PREFIX${String.format(Locale.ROOT, "%03d", index)}"
            )
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }

    private fun File.stablePath(): String {
        return runCatching { canonicalPath }.getOrDefault(absolutePath)
    }

    private companion object {
        const val NO_MATCH_DIRECTORY_PREFIX = "ClassifyNListNoMatch_"

        val filePathComparator = compareBy<File>(
            { it.name.lowercase(Locale.ROOT) },
            { it.absolutePath.lowercase(Locale.ROOT) }
        )
    }
}
