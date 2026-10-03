package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicLabPerformanceTest {

    @Test
    fun resolveStartedAtNanosKeepsEarlierPrepareStageStart() {
        assertEquals(
            10L,
            ComicLabPerformance.resolveStartedAtNanos(
                upstreamStartedAtNanos = 10L,
                fallbackStartedAtNanos = 20L
            )
        )
    }

    @Test
    fun resolveStartedAtNanosUsesFallbackWhenUpstreamStartIsInvalid() {
        assertEquals(
            20L,
            ComicLabPerformance.resolveStartedAtNanos(
                upstreamStartedAtNanos = 0L,
                fallbackStartedAtNanos = 20L
            )
        )
    }

    @Test
    fun performanceEventReportsElapsedMillisecondsAndFields() {
        assertEquals(
            "event=directory_worker elapsedMs=25 items=6",
            formatEvent(
                name = "directory_worker",
                startedAtNanos = 10_000_000L,
                finishedAtNanos = 35_000_000L,
                fields = "items=6"
            )
        )
    }

    @Test
    fun performanceEventClampsNegativeDurationAndKeepsFieldsOnOneLine() {
        assertEquals(
            "event=directory_worker elapsedMs=0 items=6 covers=2",
            formatEvent(
                name = "directory_worker",
                startedAtNanos = 35_000_000L,
                finishedAtNanos = 10_000_000L,
                fields = "items=6\ncovers=2"
            )
        )
    }

    private fun formatEvent(
        name: String,
        startedAtNanos: Long,
        finishedAtNanos: Long,
        fields: String
    ): String = ComicLabPerformance.formatEvent(
        event = name,
        startedAtNanos = startedAtNanos,
        finishedAtNanos = finishedAtNanos,
        fields = fields
    )
}
