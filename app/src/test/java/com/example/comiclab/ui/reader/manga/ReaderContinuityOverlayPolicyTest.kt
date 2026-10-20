package com.example.comiclab.ui.reader.manga

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContinuityOverlayPolicyTest {
    @Test
    fun overlayStaysUntilScrolledTargetPagesAreReady() {
        assertFalse(
            shouldRemoveReaderContinuityOverlay(
                hasScrolled = false,
                visiblePagesReady = true,
                elapsedMs = 100L
            )
        )
        assertFalse(
            shouldRemoveReaderContinuityOverlay(
                hasScrolled = true,
                visiblePagesReady = false,
                elapsedMs = 100L
            )
        )
        assertTrue(
            shouldRemoveReaderContinuityOverlay(
                hasScrolled = true,
                visiblePagesReady = true,
                elapsedMs = 100L
            )
        )
    }

    @Test
    fun overlayAlwaysExpiresAfterItsMaximumLifetime() {
        assertTrue(
            shouldRemoveReaderContinuityOverlay(
                hasScrolled = false,
                visiblePagesReady = false,
                elapsedMs = READER_CONTINUITY_OVERLAY_MAX_MS
            )
        )
    }

    @Test
    fun unreadyTargetStaysCoveredDuringMeasuredOneSecondPreviewLatency() {
        assertFalse(
            shouldRemoveReaderContinuityOverlay(
                hasScrolled = true,
                visiblePagesReady = false,
                elapsedMs = 1_000L
            )
        )
        assertTrue(
            shouldRemoveReaderContinuityOverlay(
                hasScrolled = true,
                visiblePagesReady = false,
                elapsedMs = 1_200L
            )
        )
    }

    @Test
    fun visiblePageReadinessRequiresEveryVisiblePageToBeRenderable() {
        assertFalse(areVisibleReaderPagesRenderable(emptyList()) { true })
        assertFalse(areVisibleReaderPagesRenderable(listOf(2, 3)) { it == 2 })
        assertTrue(areVisibleReaderPagesRenderable(listOf(2, 3)) { true })
    }
}
