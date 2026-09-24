package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MangaCollectionCoverPlannerTest {

    @Test
    fun findCoverSources_filtersDirectComicFilesAndSortsByName() {
        val root = Files.createTempDirectory("comiclab-cover-planner").toFile()
        try {
            val chapter02 = File(root, "02.cbz").also { it.writeText("chapter 02") }
            val chapter01 = File(root, "01.cbz").also { it.writeText("chapter 01") }
            File(root, "notes.txt").writeText("not a comic")
            File(root, ".hidden.cbz").writeText("hidden")
            File(root, "broken.cbz").writeText("no usable image")
            val result = MangaCollectionCoverPlanner.findCoverSources(
                directory = root,
                isSupportedArchive = { it.extension.equals("cbz", ignoreCase = true) },
                hasUsableImage = { it.name != "broken.cbz" }
            )

            assertEquals(listOf(chapter01, chapter02), result)

            File(root, "nested").mkdirs()
            File(root, "nested/03.cbz").writeText("nested comic")

            val mixedDirectoryResult = MangaCollectionCoverPlanner.findCoverSources(
                directory = root,
                isSupportedArchive = { it.extension.equals("cbz", ignoreCase = true) },
                hasUsableImage = { it.name != "broken.cbz" }
            )

            assertEquals(listOf(chapter01, chapter02), mixedDirectoryResult)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun findCoverSources_returnsEmptyForMissingOrUnreadableDirectory() {
        val missing = File("missing-${System.nanoTime()}")

        val result = MangaCollectionCoverPlanner.findCoverSources(
            directory = missing,
            isSupportedArchive = { true },
            hasUsableImage = { true }
        )

        assertEquals(emptyList<File>(), result)
    }

    @Test
    fun findCoverSourcesWithEntries_keepsDirectComicsWhenSubfoldersExist() {
        val root = Files.createTempDirectory("comiclab-bookcase-planner").toFile()
        try {
            val directComic = File(root, "01.cbz").also { it.writeText("direct comic") }
            val nestedDirectory = File(root, "extras").also { it.mkdirs() }
            File(nestedDirectory, "02.cbz").writeText("nested comic")

            val result = MangaCollectionCoverPlanner.findCoverSourcesWithEntries(
                directory = root,
                isSupportedArchive = { it.extension.equals("cbz", ignoreCase = true) },
                findFirstImageEntry = { "cover/page-1.jpg" }
            )

            assertEquals(
                listOf(MangaCollectionCoverSource(directComic, "cover/page-1.jpg")),
                result
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
