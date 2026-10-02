package com.example.comiclab.common_function

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveEntryFileCacheTest {

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
