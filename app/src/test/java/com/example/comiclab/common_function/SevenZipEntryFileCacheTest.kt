package com.example.comiclab.common_function

import com.example.comiclab.core.archive.cache.ArchiveEntryFileCache

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenZipEntryFileCacheTest {

    @Test
    fun sessionsUsePrivateDirectoriesAndClosingOneKeepsTheOtherAlive() {
        val root = Files.createTempDirectory("comiclab-sevenz-cache").toFile()
        val first = ArchiveEntryFileCache(root, maxBytes = 16L)
        val second = ArchiveEntryFileCache(root, maxBytes = 16L)
        var firstFile: java.io.File? = null
        var secondFile: java.io.File? = null

        try {
            first.withEntry("page.jpg", extract = { target ->
                target.writeText("first")
                true
            }) { file ->
                firstFile = file
                file.readText()
            }
            second.withEntry("page.jpg", extract = { target ->
                target.writeText("second")
                true
            }) { file ->
                secondFile = file
                file.readText()
            }

            assertNotEquals(firstFile!!.parentFile, secondFile!!.parentFile)
            first.close()

            assertFalse(firstFile!!.exists())
            assertTrue(secondFile!!.isFile)
            assertEquals("second", secondFile!!.readText())
        } finally {
            first.close()
            second.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun evictsLeastRecentlyUsedEntriesToStayWithinTheDiskBudget() {
        val root = Files.createTempDirectory("comiclab-sevenz-budget").toFile()
        val cache = ArchiveEntryFileCache(root, maxBytes = 5L)
        var firstExtractions = 0
        var firstFile: java.io.File? = null

        try {
            cache.withEntry("first", extract = { target ->
                firstExtractions++
                target.writeText("123")
                true
            }) { file ->
                firstFile = file
                file.readText()
            }
            cache.withEntry("second", extract = { target ->
                target.writeText("456")
                true
            }) { file -> file.readText() }

            assertFalse(firstFile!!.exists())
            cache.withEntry("first", extract = { target ->
                firstExtractions++
                target.writeText("789")
                true
            }) { file -> file.readText() }

            assertEquals(2, firstExtractions)
        } finally {
            cache.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedEntryCanBeDecodedThenIsNotRetained() {
        val root = Files.createTempDirectory("comiclab-sevenz-oversized").toFile()
        val cache = ArchiveEntryFileCache(root, maxBytes = 3L)
        var extractionCount = 0
        var extractedFile: java.io.File? = null

        try {
            val decoded = cache.withEntry("large-page", extract = { target ->
                extractionCount++
                target.writeText("1234")
                true
            }) { file ->
                extractedFile = file
                assertTrue(file.isFile)
                file.readText()
            }

            assertEquals("1234", decoded)
            assertFalse(extractedFile!!.exists())
            assertEquals(1, extractionCount)
        } finally {
            cache.close()
            root.deleteRecursively()
        }
    }
}
