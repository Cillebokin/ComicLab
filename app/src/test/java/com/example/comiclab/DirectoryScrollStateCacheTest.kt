package com.example.comiclab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryScrollStateCacheTest {

    @Test
    fun latestScrollSnapshotForDirectoryRetainsItsAnchorAndPixelOffset() {
        val directoryPath = File("comiclab-library").absolutePath
        val cache = DirectoryScrollStateCache()
        val earlierState = FileListScrollState(
            directoryPath = directoryPath,
            firstVisiblePosition = 4,
            firstVisibleTop = -18,
            anchorPath = File(directoryPath, "Earlier.zip").absolutePath,
            anchorIsParent = false
        )
        val latestState = FileListScrollState(
            directoryPath = directoryPath,
            firstVisiblePosition = 12,
            firstVisibleTop = -37,
            anchorPath = File(directoryPath, "[ABC] Series.zip").absolutePath,
            anchorIsParent = false
        )

        cache.save(earlierState)
        cache.save(latestState)

        assertEquals(latestState, cache.get(directoryPath))
    }

    @Test
    fun directoryScrollSnapshotsCanBeRestoredAfterActivityRecreation() {
        val directoryPath = File("comiclab-library").absolutePath
        val savedState = FileListScrollState(
            directoryPath = directoryPath,
            firstVisiblePosition = 7,
            firstVisibleTop = -29,
            anchorPath = File(directoryPath, "Volume 8.zip").absolutePath,
            anchorIsParent = false
        )
        val originalCache = DirectoryScrollStateCache()
        originalCache.save(savedState)
        val restoredCache = DirectoryScrollStateCache(originalCache.snapshot())

        assertEquals(savedState, restoredCache.get(directoryPath))
    }
}
