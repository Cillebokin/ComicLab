package com.example.comiclab.core.diagnostics

import org.junit.Assert.*
import org.junit.Test

class PdfReaderDiagnosticsTest {
    @Test fun slowPlaceholderEvidenceSurvivesHistoryOverflowAndSeparatesCacheFromBinding() {
        var now = 0L
        val session = PdfReaderDiagnostics.Session("book.pdf", clock = { now })
        val view = session.placeholderStarted(7)
        session.queued(1, 7, "PREVIEW", false, 0)
        now = 100_000_000
        session.phase(1, "cache_accepted")
        now = 600_000_000
        session.phase(1, "cache_accepted")
        now = 800_000_000
        session.placeholderBound(view, "preview")
        now = 820_000_000
        session.drawn(view)
        repeat(1000) { session.note("noise", "other") }
        val text = session.snapshot()
        assertTrue(text.contains("Slow placeholder evidence"))
        assertTrue(text.contains("lastCacheToBindMs=200.0"))
        assertTrue(text.contains("event=cache_accepted"))
        assertTrue(text.contains("event=draw page=8"))
    }

    @Test fun exportSeparatesQueuedNativeBindingAndDrawStagesAndKeepsUnfinishedWorkVisible() {
        var now = 0L
        val session = PdfReaderDiagnostics.Session("book.pdf", clock = { now })
        val placeholder = session.placeholderStarted(7)
        session.queued(10, 7, "PREVIEW", false, 4)
        now = 20_000_000
        session.started(10, 20_000_000)
        session.phase(10, "native_wait")
        now = 50_000_000
        session.phase(10, "native_render", "gateWaitMs=30.0")
        val waiting = session.snapshot()
        assertTrue(waiting.contains("task=10 page=8 kind=PREVIEW phase=native_render"))
        assertTrue(waiting.contains("queueMaxMs=20.0"))
        session.phase(10, "native_done", "renderMs=50.0", 50_000_000)
        now = 100_000_000
        session.finished(10)
        session.placeholderBound(placeholder, "preview")
        val bound = session.snapshot()
        assertTrue(bound.contains("phase=awaiting_draw"))
        assertFalse(bound.contains("event=draw "))
        now = 130_000_000
        session.drawn(placeholder)
        val drawn = session.snapshot()
        assertTrue(drawn.contains("nativeMaxMs=50.0"))
        assertTrue(drawn.contains("drawAfterBindMaxMs=30.0"))
        assertTrue(drawn.contains("event=draw page=8"))
        assertTrue(drawn.contains("activeTasks=0 activePlaceholders=0"))
    }

    @Test fun historyAndActiveTrackingStayBoundedAndCancellationDoesNotRemainAsRunningWork() {
        val session = PdfReaderDiagnostics.Session("book.pdf", clock = { 100L })
        repeat(1000) { session.queued(it.toLong(), it, "PREVIEW", true, it) }
        session.cancelled(999, "viewport_changed")
        val text = session.snapshot()
        assertFalse(text.contains("task=999 page=1000 kind=PREVIEW phase=queued"))
        assertTrue(text.contains("reason=viewport_changed"))
        assertTrue(text.lineSequence().count() < 150)
        assertTrue(text.contains("dropped="))
        assertTrue(text.length < 32_000)
    }

    @Test fun closingFreezesUnfinishedEvidenceAndLateNativeResultsCannotRewriteIt() {
        var now = 10L
        val session = PdfReaderDiagnostics.Session("book.pdf", clock = { now })
        session.queued(1, 0, "FULL", false, 0)
        session.started(1, 0)
        session.phase(1, "native_render")
        now = 1000
        session.close()
        val before = session.snapshot()
        now = 100000
        session.finished(1)
        session.phase(1, "native_done")
        assertEquals(before, session.snapshot())
        assertTrue(before.contains("state=closed"))
        assertTrue(before.contains("phase=native_render"))
    }

    @Test fun taskScopeIsRemovedEvenWhenDecoderThrows() {
        val session = PdfReaderDiagnostics.Session("book.pdf", clock = { 1L })
        session.queued(1, 0, "PREVIEW", false, 0)
        assertThrows(IllegalStateException::class.java) {
            PdfReaderDiagnostics.withTask(session, 1) {
                PdfReaderDiagnostics.nativePhase("native_render")
                throw IllegalStateException("fake decoder")
            }
        }
        PdfReaderDiagnostics.nativePhase("unscoped_must_not_be_recorded")
        assertFalse(session.snapshot().contains("unscoped_must_not_be_recorded"))
        assertTrue(session.snapshot().contains("phase=native_render"))
    }

    @Test fun aLongNativeRenderSurvivesAThousandQueuedTasksInTheDiagnosticTracker() {
        val session = PdfReaderDiagnostics.Session("book.pdf", clock = { 1L })
        session.queued(1, 0, "FULL", false, 0)
        session.started(1, 0)
        session.phase(1, "native_render")
        repeat(1000) { session.queued(it + 2L, it, "PREVIEW", true, it) }
        assertTrue("overflow must not erase the native call that is blocking the worker",
            session.snapshot().contains("task=1 page=1 kind=FULL phase=native_render"))
    }
}
