package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaCollectionCoverLoadTrackerTest {

    @Test
    fun duplicateRequestsShareOneLoadAndAllWaitingViewsAreReleased() {
        val tracker = MangaCollectionCoverLoadTracker<Any>()
        val firstView = Any()
        val secondView = Any()

        assertTrue(tracker.register("cover-key", firstView))
        assertFalse(tracker.register("cover-key", firstView))
        assertFalse(tracker.register("cover-key", secondView))
        assertEquals(listOf(firstView, secondView), tracker.complete("cover-key"))
        assertTrue(tracker.register("cover-key", secondView))
    }
}
