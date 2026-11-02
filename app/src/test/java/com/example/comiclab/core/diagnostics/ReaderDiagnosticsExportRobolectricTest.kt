package com.example.comiclab.core.diagnostics

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderDiagnosticsExportRobolectricTest {
    @Test fun aReaderThatHasNotCrashedCanStillExportItsLatestRenderEvidence() {
        val context = RuntimeEnvironment.getApplication()
        val pdf = File(context.cacheDir, "black-page.pdf").apply { writeText("fixture") }
        CrashLogManager.recordReaderRender(context, pdf, 7, "PREVIEW", 720, null)
        val export = CrashLogManager.createLatestCrashLogExport(context)
        assertNotNull("a black page does not necessarily crash; its diagnostic evidence must be exportable", export)
        val text = export!!.readText()
        assertTrue(text.contains("page=8"))
        assertTrue(text.contains("kind=PREVIEW"))
        assertTrue(text.contains("App:"))
        assertTrue(text.contains("Device:"))
    }

    @Test fun olderSessionFlushCannotReplaceTheNewBookAndExistingCrashFilesRemainIntact() {
        val context = RuntimeEnvironment.getApplication()
        val old = PdfReaderDiagnostics.begin(File(context.cacheDir, "old.pdf"))
        val current = PdfReaderDiagnostics.begin(File(context.cacheDir, "new.pdf"))
        current.note("viewport", "first=8 last=9 direction=-1 fast=true")
        PdfReaderDiagnostics.persist(context, current)
        assertFalse("large periodic snapshots must not inflate the per-render checkpoint preferences",
            context.getSharedPreferences("reader_diagnostics", 0).contains("latest_pdf_timing"))
        old.close()
        PdfReaderDiagnostics.persist(context, old)
        val persisted = context.getSharedPreferences("pdf_reader_timing", 0).getString("latest_pdf_timing", "")!!
        assertTrue(persisted.contains("new.pdf"))
        assertFalse(persisted.contains("old.pdf"))
        val log = File(context.filesDir, "crash_logs/crash_fixture.txt").apply {
            parentFile!!.mkdirs(); writeText("ORIGINAL_CRASH_FIXTURE")
        }
        val export = CrashLogManager.createLatestCrashLogExport(context)!!
        val text = export.readText()
        assertTrue(text.contains("new.pdf"))
        assertTrue(text.contains("direction=-1 fast=true"))
        assertTrue(text.contains("ORIGINAL_CRASH_FIXTURE"))
        assertEquals("ORIGINAL_CRASH_FIXTURE", log.readText())
    }

    @Test fun interruptionRecoveryPreservesPriorPdfTimingBeforeAnotherBookReplacesTheLatestSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val prior = PdfReaderDiagnostics.begin(File(context.cacheDir, "interrupted.pdf"))
        prior.queued(1, 7, "PREVIEW", false, 0)
        prior.started(1, 0)
        prior.phase(1, "native_render")
        PdfReaderDiagnostics.persist(context, prior)
        context.getSharedPreferences("reader_diagnostics", 0).edit()
            .putString("active_reader_session", "state=active file=interrupted.pdf token=prior").commit()
        CrashLogManager::class.java.getDeclaredMethod("recoverInterruptedReaderSession", android.content.Context::class.java)
            .apply { isAccessible = true }.invoke(CrashLogManager, context)
        val recovered = CrashLogManager.latestCrashLog(context)!!
        val before = recovered.readText()
        assertTrue(before.contains("interrupted.pdf"))
        assertTrue(before.contains("phase=native_render"))
        val next = PdfReaderDiagnostics.begin(File(context.cacheDir, "next.pdf"))
        PdfReaderDiagnostics.persist(context, next)
        val exported = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        assertTrue(exported.contains("next.pdf"))
        assertTrue(exported.contains(before))
    }
}
