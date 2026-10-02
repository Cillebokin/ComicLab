package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaCollectionCoverLoadTrackerTest {

    @Test
    fun duplicateRequestsShareOneLoadAndAllWaitingViewsAreReleased() {
        val tracker = MangaCollectionCoverLoadTracker<Any>()
        val firstView = Any()
        val secondView = Any()

        assertTrue(tracker.register("cover-key", firstView))
        val requestId = tracker.requestId("cover-key")!!
        assertFalse(tracker.register("cover-key", firstView))
        assertFalse(tracker.register("cover-key", secondView))
        assertEquals(listOf(firstView, secondView), tracker.complete("cover-key", requestId))
        assertTrue(tracker.register("cover-key", secondView))
    }

    @Test
    fun recyclingLastWaitingViewCancelsItsRequestAndOldCompletionCannotReleaseANewerRequest() {
        val tracker = MangaCollectionCoverLoadTracker<Any>()
        val recycledView = Any()
        val reboundView = Any()

        assertTrue(tracker.register("cover-key", recycledView))
        val oldRequestId = tracker.requestId("cover-key")!!
        assertEquals(oldRequestId, tracker.unregister("cover-key", recycledView))

        assertTrue(tracker.register("cover-key", reboundView))
        val newRequestId = tracker.requestId("cover-key")!!
        assertNotEquals(oldRequestId, newRequestId)
        assertNull(tracker.complete("cover-key", oldRequestId))
        assertEquals(listOf(reboundView), tracker.complete("cover-key", newRequestId))
    }

    @Test
    fun recyclingOneOfSeveralWaitingViewsKeepsSharedRequestAlive() {
        val tracker = MangaCollectionCoverLoadTracker<Any>()
        val firstView = Any()
        val secondView = Any()

        tracker.register("cover-key", firstView)
        val requestId = tracker.requestId("cover-key")!!
        tracker.register("cover-key", secondView)

        assertNull(tracker.unregister("cover-key", firstView))
        assertEquals(listOf(secondView), tracker.complete("cover-key", requestId))
    }
}
