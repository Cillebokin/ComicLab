package com.example.comiclab

import com.example.comiclab.ui.browser.DirectoryChildCountCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DirectoryChildCountCacheTest {

    @Test
    fun returnsCountWhenDirectoryTimestampIsUnchanged() {
        val cache = DirectoryChildCountCache(maxEntries = 2)
        cache.put("/books/manga", lastModified = 100L, childCount = 4)

        assertEquals(4, cache.get("/books/manga", lastModified = 100L))
    }

    @Test
    fun invalidatesCountWhenDirectoryTimestampChanges() {
        val cache = DirectoryChildCountCache(maxEntries = 2)
        cache.put("/books/manga", lastModified = 100L, childCount = 4)

        assertNull(cache.get("/books/manga", lastModified = 101L))
        assertNull(cache.get("/books/manga", lastModified = 100L))
    }

    @Test
    fun explicitlyInvalidatesCountAfterKnownDirectoryMutation() {
        val cache = DirectoryChildCountCache(maxEntries = 2)
        cache.put("/books/manga", lastModified = 100L, childCount = 4)

        cache.invalidate("/books/manga")

        assertNull(cache.get("/books/manga", lastModified = 100L))
    }

    @Test
    fun invalidationPreventsOlderInFlightCountFromBeingCached() {
        val cache = DirectoryChildCountCache(maxEntries = 2)
        val requestGeneration = cache.generation()

        cache.invalidate("/books/manga")

        assertEquals(
            false,
            cache.putIfUnchangedSince(
                "/books/manga",
                lastModified = 100L,
                childCount = 4,
                expectedGeneration = requestGeneration
            )
        )
        assertNull(cache.get("/books/manga", lastModified = 100L))
    }

    @Test
    fun timestampMismatchPreventsOlderInFlightCountFromBeingCached() {
        val cache = DirectoryChildCountCache(maxEntries = 2)
        cache.put("/books/manga", lastModified = 100L, childCount = 4)
        val requestGeneration = cache.generation()

        assertNull(cache.get("/books/manga", lastModified = 101L))

        assertFalse(
            cache.putIfUnchangedSince(
                "/books/manga",
                lastModified = 101L,
                childCount = 5,
                expectedGeneration = requestGeneration
            )
        )
        assertNull(cache.get("/books/manga", lastModified = 101L))
    }

    @Test
    fun evictsLeastRecentlyUsedEntryWhenCapacityIsReached() {
        val cache = DirectoryChildCountCache(maxEntries = 2)
        cache.put("/books/one", lastModified = 1L, childCount = 1)
        cache.put("/books/two", lastModified = 1L, childCount = 2)
        cache.get("/books/one", lastModified = 1L)
        cache.put("/books/three", lastModified = 1L, childCount = 3)

        assertEquals(1, cache.get("/books/one", lastModified = 1L))
        assertNull(cache.get("/books/two", lastModified = 1L))
        assertEquals(3, cache.get("/books/three", lastModified = 1L))
    }
}
