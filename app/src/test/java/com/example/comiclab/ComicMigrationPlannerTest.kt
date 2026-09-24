package com.example.comiclab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicMigrationPlannerTest {

    @Test
    fun scanDirectComicFilesDoesNotEnterChildDirectories() {
        withTemporaryDirectory { root ->
            val directComic = File(root, "direct.comic").apply { writeText("comic") }
            File(root, "ignored.txt").writeText("text")
            val childDirectory = File(root, "child").apply { mkdirs() }
            File(childDirectory, "nested.comic").writeText("comic")

            val result = ComicMigrationPlanner { it.extension == "comic" }
                .scanDirectComicFiles(root)

            assertEquals(listOf(directComic.absolutePath), result.map { it.absolutePath })
        }
    }

    @Test
    fun findMatchingDirectoriesSearchesAllDescendantsAndExcludesRoot() {
        withTemporaryDirectory { root ->
            val directMatch = File(root, "Shelf Alpha").apply { mkdirs() }
            val branch = File(root, "Other").apply { mkdirs() }
            val nestedMatch = File(branch, "Shelf Alpha Extra").apply { mkdirs() }
            File(root, "Shelf").mkdirs()

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "alpha")

            assertEquals(
                listOf(directMatch, nestedMatch)
                    .map { it.absolutePath }
                    .sorted(),
                result.map { it.absolutePath }.sorted()
            )
            assertTrue(result.none { it.absolutePath == root.absolutePath })
        }
    }

    @Test
    fun nextNoMatchDirectoryUsesThreeDigitNextAvailableNumber() {
        withTemporaryDirectory { root ->
            File(root, "ClassifyNListNoMatch_000").mkdirs()
            File(root, "ClassifyNListNoMatch_001").mkdirs()

            val result = ComicMigrationPlanner { false }
                .nextNoMatchDirectory(root)

            assertEquals("ClassifyNListNoMatch_002", result.name)
            assertTrue(!result.exists())
        }
    }

    @Test
    fun targetDirectoryOptionsExposeDefaultWhenNoMatchExists() {
        withTemporaryDirectory { root ->
            val defaultDirectory = File(root, "ClassifyNListNoMatch_000")

            val result = ComicMigrationPlanner { false }
                .targetDirectoriesForSelection(emptyList(), defaultDirectory)

            assertEquals(listOf(defaultDirectory.absolutePath), result.map { it.absolutePath })
        }
    }

    @Test
    fun targetDirectoryOptionsKeepAllMatchedDirectoriesForSelection() {
        withTemporaryDirectory { root ->
            val firstDirectory = File(root, "First").apply { mkdirs() }
            val secondDirectory = File(root, "Second").apply { mkdirs() }
            val defaultDirectory = File(root, "ClassifyNListNoMatch_000")

            val result = ComicMigrationPlanner { false }
                .targetDirectoriesForSelection(
                    listOf(firstDirectory, secondDirectory),
                    defaultDirectory
                )

            assertEquals(
                listOf(firstDirectory.absolutePath, secondDirectory.absolutePath),
                result.map { it.absolutePath }
            )
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("comic-migration-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
