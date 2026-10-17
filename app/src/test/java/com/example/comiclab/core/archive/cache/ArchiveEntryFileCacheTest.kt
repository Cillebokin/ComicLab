package com.example.comiclab.core.archive.cache

import com.example.comiclab.core.archive.cache.ArchiveEntryFileCache

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveEntryFileCacheTest {

    @Test
    fun failedExtractionRemovesPartialFileAndCanRetryBeforeIdempotentClose() {
        val root = Files.createTempDirectory("comiclab-cache-failure").toFile()
        val cache = ArchiveEntryFileCache(root, maxBytes = 8L)
        var partial: File? = null
        try {
            assertNull(cache.withEntry("page", extract = {
                partial = it
                it.writeText("partial")
                false
            }) { error("failed extraction must not be used") })
            assertFalse(partial!!.exists())
            assertEquals("ok", cache.withEntry("page", extract = { it.writeText("ok"); true }) { it.readText() })
            cache.close()
            cache.close()
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertEquals(null, cache.withEntry("page", extract = { error("closed cache must not extract") }) { it.readText() })
        } finally {
            cache.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun useFailureDeletesOneShotFileButKeepsCachedFileUntilClose() {
        val root = Files.createTempDirectory("comiclab-cache-read-failure").toFile()
        try {
            for (budget in listOf(0L, 16L)) {
                val cache = ArchiveEntryFileCache(root, maxBytes = budget)
                var file: File? = null
                try {
                    val failure = runCatching {
                        cache.withEntry("page", extract = { it.writeText("bytes"); true }) {
                            file = it
                            throw java.io.IOException("read failed")
                        }
                    }.exceptionOrNull()
                    assertTrue(failure is java.io.IOException)
                    assertEquals(budget > 0L, file!!.exists())
                } finally {
                    cache.close()
                }
                assertFalse(file!!.exists())
                assertTrue(root.listFiles().orEmpty().isEmpty())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changedCachedLengthTriggersReextractionAndCacheHitUpdatesLruOrder() {
        val root = Files.createTempDirectory("comiclab-cache-hit").toFile()
        val cache = ArchiveEntryFileCache(root, maxBytes = 6L)
        var first: File? = null
        var second: File? = null
        try {
            cache.withEntry("first", extract = { it.writeText("123"); true }) { first = it }
            cache.withEntry("second", extract = { it.writeText("456"); true }) { second = it }
            assertEquals("123", cache.withEntry("first", extract = { error("cache hit must be reused") }) { it.readText() })
            cache.withEntry("third", extract = { it.writeText("789"); true }) { it.readText() }
            assertTrue(first!!.exists())
            assertFalse(second!!.exists())
            first!!.writeText("changed length")
            assertEquals("new", cache.withEntry("first", extract = { it.writeText("new"); true }) { it.readText() })
            assertFalse(first!!.exists())
        } finally {
            cache.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun oneShotEntryIsAvailableAsAFileDuringUseAndRemovedAfterward() {
        val root = Files.createTempDirectory("comiclab-archive-cache").toFile()
        val cache = ArchiveEntryFileCache(root, maxBytes = 0L)
        var extractedFile: File? = null

        try {
            val contents = cache.withEntry(
                entryName = "pages/page.png",
                extract = { target ->
                    target.writeText("image payload")
                    true
                },
                use = { file ->
                    extractedFile = file
                    assertTrue(file.isFile)
                    file.readText()
                }
            )

            assertEquals("image payload", contents)
            assertFalse(extractedFile!!.exists())
        } finally {
            cache.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun staleCurrentAndLegacySessionDirectoriesAreRemovedButRecentAndUnrelatedFilesRemain() {
        val root = Files.createTempDirectory("comiclab-archive-cleanup").toFile()
        val now = 2_000_000_000_000L
        val maxAge = 24L * 60L * 60L * 1000L
        val oldCurrentSession = File(root, "archive_entries_old").apply { mkdirs() }
        val oldLegacySession = File(root, "sevenzip_old").apply { mkdirs() }
        val recentSession = File(root, "archive_entries_recent").apply { mkdirs() }
        val unrelatedDirectory = File(root, "user-data").apply { mkdirs() }
        val unrelatedFile = File(root, "comiclab_zip_notes.txt").apply { writeText("keep") }
        val oldTimestamp = now - maxAge - 1L
        oldCurrentSession.setLastModified(oldTimestamp)
        oldLegacySession.setLastModified(oldTimestamp)
        recentSession.setLastModified(now - maxAge + 1L)

        try {
            val removed = ArchiveEntryFileCache.cleanupStaleSessions(
                cacheRoot = root,
                nowMillis = now,
                maxAgeMillis = maxAge
            )

            assertEquals(2, removed)
            assertFalse(oldCurrentSession.exists())
            assertFalse(oldLegacySession.exists())
            assertTrue(recentSession.isDirectory)
            assertTrue(unrelatedDirectory.isDirectory)
            assertTrue(unrelatedFile.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
