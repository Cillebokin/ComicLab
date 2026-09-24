package com.example.comiclab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookcaseListTouchTrackerTest {

    @Test
    fun handledDown_remainsActiveUntilTheTouchStreamEnds() {
        val tracker = BookcaseListTouchTracker()

        tracker.onDown()
        tracker.onDownDispatchResult(handled = true)
        assertTrue(tracker.isTouchActive)

        tracker.onEnd()
        assertFalse(tracker.isTouchActive)
    }

    @Test
    fun unhandledDown_doesNotLeaveAnActiveTouchStream() {
        val tracker = BookcaseListTouchTracker()

        tracker.onDown()
        tracker.onDownDispatchResult(handled = false)

        assertFalse(tracker.isTouchActive)
    }

    @Test
    fun detachedList_clearsAnUnfinishedTouchStream() {
        val tracker = BookcaseListTouchTracker()
        tracker.onDown()

        tracker.cancel()

        assertFalse(tracker.isTouchActive)
    }
}
