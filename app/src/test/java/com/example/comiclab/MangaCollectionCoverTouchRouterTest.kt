package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaCollectionCoverTouchRouterTest {

    private val router = MangaCollectionCoverTouchRouter(touchSlop = 8f)

    @Test
    fun blankLongPress_staysEligibleUntilMovementOrConsumption() {
        router.onDown(isBlank = true, x = 10f, y = 10f)
        assertTrue(router.isBlankLongPressEligible)

        router.onMove(x = 20f, y = 10f)
        assertFalse(router.isBlankLongPressEligible)

        router.onDown(isBlank = true, x = 10f, y = 10f)
        router.onLongClick()
        assertFalse(router.isBlankLongPressEligible)
    }

    @Test
    fun blankLongPress_isNotEligibleForCoverOrMultitouch() {
        router.onDown(isBlank = false, x = 10f, y = 10f)
        assertFalse(router.isBlankLongPressEligible)

        router.onDown(isBlank = true, x = 10f, y = 10f)
        router.onPointerDown()
        assertFalse(router.isBlankLongPressEligible)
    }

    @Test
    fun pressFeedback_staysUntilMovementExceedsTouchSlop() {
        router.onDown(isBlank = false, x = 10f, y = 10f)

        assertTrue(router.isPressFeedbackActive)
        router.onMove(x = 14f, y = 13f)
        assertTrue(router.isPressFeedbackActive)

        router.onMove(x = 20f, y = 10f)
        assertFalse(router.isPressFeedbackActive)
        router.onUp()
        assertFalse(router.isPressFeedbackActive)
    }

    @Test
    fun pressFeedback_remainsDuringLongPressUntilRelease() {
        router.onDown(isBlank = false, x = 10f, y = 10f)
        router.onLongClick()

        assertTrue(router.isPressFeedbackActive)
        router.onUp()
        assertFalse(router.isPressFeedbackActive)
    }

    @Test
    fun pressFeedback_clearsWhenGestureIsCancelledOrMultitouchBegins() {
        router.onDown(isBlank = false, x = 10f, y = 10f)
        router.onPointerDown()
        assertFalse(router.isPressFeedbackActive)

        router.onDown(isBlank = false, x = 10f, y = 10f)
        router.cancel()
        assertFalse(router.isPressFeedbackActive)
    }

    @Test
    fun horizontalDrag_isRoutedToCoverList() {
        router.onDown(isBlank = true, x = 10f, y = 10f)

        assertEquals(
            BookcaseGestureDirection.HORIZONTAL,
            router.onMove(x = 30f, y = 12f)
        )
        assertTrue(router.isTouchActive)
        assertFalse(router.onUp())
        assertFalse(router.isTouchActive)
    }

    @Test
    fun verticalDrag_isYieldedToOuterList() {
        router.onDown(isBlank = true, x = 10f, y = 10f)

        assertEquals(
            BookcaseGestureDirection.VERTICAL,
            router.onMove(x = 12f, y = 30f)
        )
        assertFalse(router.onUp())
    }

    @Test
    fun earlyHorizontalDrift_doesNotCaptureGestureThatBecomesVertical() {
        router.onDown(isBlank = true, x = 0f, y = 0f)

        assertEquals(
            BookcaseGestureDirection.UNDECIDED,
            router.onMove(x = 12f, y = 4f)
        )
        assertEquals(
            BookcaseGestureDirection.VERTICAL,
            router.onMove(x = 12f, y = 18f)
        )
    }

    @Test
    fun horizontalDrag_requiresClearDominancePastParentTouchSlop() {
        router.onDown(isBlank = true, x = 0f, y = 0f)

        assertEquals(
            BookcaseGestureDirection.HORIZONTAL,
            router.onMove(x = 20f, y = 4f)
        )
    }

    @Test
    fun horizontalGestureThatTurnsVertical_isYieldedToOuterList() {
        router.onDown(isBlank = true, x = 0f, y = 0f)
        assertEquals(
            BookcaseGestureDirection.HORIZONTAL,
            router.onMove(x = 20f, y = 4f)
        )

        assertEquals(
            BookcaseGestureDirection.VERTICAL,
            router.onMove(x = 21f, y = 28f)
        )
    }

    @Test
    fun ambiguousMovementBeyondTapSlop_isNotTreatedAsBlankTap() {
        router.onDown(isBlank = true, x = 0f, y = 0f)

        assertEquals(
            BookcaseGestureDirection.UNDECIDED,
            router.onMove(x = 10f, y = 10f)
        )
        assertFalse(router.onUp())
    }

    @Test
    fun blankTap_isRecognized_butCoverTapIsNot() {
        router.onDown(isBlank = true, x = 10f, y = 10f)
        assertTrue(router.onUp())

        router.onDown(isBlank = false, x = 10f, y = 10f)
        assertFalse(router.onUp())
    }

    @Test
    fun longPress_suppressesBlankTapOnRelease() {
        router.onDown(isBlank = true, x = 10f, y = 10f)
        router.onLongClick()

        assertFalse(router.onUp())
    }

    @Test
    fun movementWithinTouchSlop_stillCountsAsBlankTap() {
        router.onDown(isBlank = true, x = 10f, y = 10f)
        assertEquals(
            BookcaseGestureDirection.UNDECIDED,
            router.onMove(x = 14f, y = 13f)
        )

        assertTrue(router.onUp())
    }

    @Test
    fun pointerCancellation_doesNotBecomeTap_andRemainsActiveUntilUp() {
        router.onDown(isBlank = true, x = 10f, y = 10f)
        router.onPointerDown()

        assertTrue(router.isTouchActive)
        assertFalse(router.onUp())
        assertFalse(router.isTouchActive)
    }

    @Test
    fun cancelledTouch_clearsGestureState() {
        router.onDown(isBlank = true, x = 10f, y = 10f)
        router.cancel()

        assertFalse(router.isTouchActive)
        assertFalse(router.onUp())
    }

    @Test
    fun horizontalScrollPolicy_blocksNativeRecyclerViewCaptureUntilRouted() {
        assertFalse(canBookcaseCoverScrollHorizontally(BookcaseGestureDirection.UNDECIDED, false))
        assertFalse(canBookcaseCoverScrollHorizontally(BookcaseGestureDirection.VERTICAL, false))
        assertTrue(canBookcaseCoverScrollHorizontally(BookcaseGestureDirection.HORIZONTAL, false))
        assertTrue(canBookcaseCoverScrollHorizontally(BookcaseGestureDirection.UNDECIDED, true))
    }
}
