package com.example.comiclab.ui.reader.manga

import com.example.comiclab.ui.browser.DirectorySortOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextMangaTest {
    @Test fun followsDirectorySortAndSkipsNonComics() {
        val dir = Files.createTempDirectory("next-manga").toFile()
        try {
            val first = dir.resolve("01.cbz").apply { writeText("a") }
            dir.resolve("02.txt").writeText("b")
            val next = dir.resolve("03.pdf").apply { writeText("ccc") }
            dir.resolve("04.cbz").mkdir()
            assertEquals(next, findNextManga(first, DirectorySortOrder.NAME_ASC))
            assertEquals(first, findNextManga(next, DirectorySortOrder.NAME_DESC))
            assertNull(findNextManga(next, DirectorySortOrder.NAME_ASC))
        } finally { dir.deleteRecursively() }
    }

    @Test fun respectsSizeAndTimeOrderAndDoesNotUseHiddenFiles() {
        val dir = Files.createTempDirectory("next-manga-order").toFile()
        try {
            val small = dir.resolve("small.cbz").apply { writeText("x"); setLastModified(1000L) }
            val medium = dir.resolve("medium.cbz").apply { writeText("xx"); setLastModified(2000L) }
            val large = dir.resolve("large.pdf").apply { writeText("xxx"); setLastModified(3000L) }
            dir.resolve(".hidden.cbz").writeText("xxxx")
            assertEquals(medium, findNextManga(small, DirectorySortOrder.SIZE_ASC))
            assertEquals(medium, findNextManga(large, DirectorySortOrder.SIZE_DESC))
            assertEquals(medium, findNextManga(small, DirectorySortOrder.MODIFIED_ASC))
            assertEquals(medium, findNextManga(large, DirectorySortOrder.MODIFIED_DESC))
            assertNull(findNextManga(large, DirectorySortOrder.SIZE_ASC))
        } finally { dir.deleteRecursively() }
    }
}
