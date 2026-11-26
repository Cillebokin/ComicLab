package com.example.comiclab.core.diagnostics

import android.content.Context
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.FileSystemException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

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
        val old = PdfReaderDiagnostics.begin(context, File(context.cacheDir, "old.pdf"))
        val current = PdfReaderDiagnostics.begin(context, File(context.cacheDir, "new.pdf"))
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
        assertFalse(text.contains("ORIGINAL_CRASH_FIXTURE"))
        assertEquals("ORIGINAL_CRASH_FIXTURE", log.readText())
    }

    @Test fun interruptionRecoveryPreservesPriorPdfTimingBeforeAnotherBookReplacesTheLatestSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val prior = PdfReaderDiagnostics.begin(context, File(context.cacheDir, "interrupted.pdf"))
        prior.queued(1, 7, "PREVIEW", false, 0)
        prior.started(1, 0)
        prior.phase(1, "native_render")
        PdfReaderDiagnostics.persist(context, prior)
        CrashLogManager.recordReaderSessionStarted(context, File(context.cacheDir, "interrupted.pdf"))
        CrashLogManager::class.java.getDeclaredMethod("recoverInterruptedReaderSession", android.content.Context::class.java)
            .apply { isAccessible = true }.invoke(CrashLogManager, context)
        val recovered = CrashLogManager.latestCrashLog(context)!!
        val before = recovered.readText()
        assertTrue(before.contains("interrupted.pdf"))
        assertTrue(before.contains("phase=native_render"))
        val next = PdfReaderDiagnostics.begin(context, File(context.cacheDir, "next.pdf"))
        PdfReaderDiagnostics.persist(context, next)
        val exported = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        assertTrue(exported.contains("next.pdf"))
        assertTrue(exported.contains("interrupted.pdf"))
        assertTrue(exported.contains("phase=native_render"))
    }

    @Test fun privateReaderRecordsAndNestedCrashMessagesAreRedactedBeforeWriting() {
        val context = RuntimeEnvironment.getApplication()
        val file = privateBook(context)
        CrashLogManager.recordReaderSessionStarted(context, file)
        CrashLogManager.recordReaderCheckpoint(context, file, 7, 42, durable = true)
        CrashLogManager.recordReaderRenderStarted(context, file, 7, "PREVIEW", 720, null)
        CrashLogManager.recordReaderRender(context, file, 7, "PREVIEW", 720, null)
        val pdf = PdfReaderDiagnostics.begin(context, file)
        pdf.queued(1, 7, "PREVIEW", false, 0)
        pdf.phase(1, "native_render", "debug=${file.name}")
        PdfReaderDiagnostics.persist(context, pdf)

        val nested = IllegalStateException(file.name, IllegalArgumentException("cause ${file.absolutePath}"))
        nested.addSuppressed(UnsupportedOperationException("suppressed ${file.name}"))
        nested.stackTrace = arrayOf(StackTraceElement("com.example.Reader", "draw", "Reader.kt", 42))
        writeCrash(context, nested)
        val crash = CrashLogManager.latestCrashLog(context)!!.readText()
        val export = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        val prefs = context.getSharedPreferences("reader_diagnostics", 0).all
        val savedPdf = context.getSharedPreferences("pdf_reader_timing", 0).getString("latest_pdf_timing", "")!!
        listOf(crash, export, prefs.toString(), savedPdf).forEach {
            assertFalse(it.contains(file.name))
            assertFalse(it.contains(file.parentFile!!.name))
            assertTrue(it.contains("file=[privacy-redacted]"))
            assertTrue(it.contains("page=8"))
        }
        assertTrue(crash.contains("IllegalStateException"))
        assertTrue(crash.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(crash.contains("Suppressed: java.lang.UnsupportedOperationException"))
        assertTrue(crash.contains("com.example.Reader.draw(Reader.kt:42)"))
    }

    @Test fun aLatePrivateMarkFiltersLegacyRecordsAndUntrustedCrashTextOnExportAndRecovery() {
        val context = RuntimeEnvironment.getApplication()
        val file = privateBook(context, mark = false)
        CrashLogManager.recordReaderSessionStarted(context, file)
        CrashLogManager.recordReaderCheckpoint(context, file, 3, 17, durable = true)
        CrashLogManager.recordReaderRenderStarted(context, file, 3, "PREVIEW", 720, null)
        val pdf = PdfReaderDiagnostics.begin(context, file)
        pdf.phase(1, "native_render", "debug=${file.name}")
        PdfReaderDiagnostics.persist(context, pdf)
        writeCrash(context, IllegalStateException(file.name))
        val raw = CrashLogManager.latestCrashLog(context)!!
        val before = raw.readText()
        assertTrue(before.contains(file.name))
        assertTrue(PrivacyStore.addPrivacyDirectory(context, file.parentFile!!))

        val export = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        assertFalse(export.contains(file.name))
        assertTrue(export.contains("page=4"))
        assertTrue(export.contains("java.lang.IllegalStateException"))
        assertEquals(before, raw.readText())

        CrashLogManager::class.java.getDeclaredMethod("recoverInterruptedReaderSession", Context::class.java)
            .apply { isAccessible = true }.invoke(CrashLogManager, context)
        val recovered = raw.parentFile!!.listFiles()!!.single { it.name.startsWith("reader_interrupted_") }.readText()
        assertFalse(recovered.contains(file.name))
        assertTrue(recovered.contains("file=[privacy-redacted]"))
        assertTrue(recovered.contains("offset=17"))
    }

    @Test fun ordinaryCrashKeepsOrdinaryMessagesAndDropsRecognizablePrivatePaths() {
        val context = RuntimeEnvironment.getApplication()
        val private = privateBook(context)
        PdfReaderDiagnostics.begin(context, private)
        val ordinary = File(context.cacheDir, "public.cbz").apply { writeText("fixture") }
        CrashLogManager.recordReaderSessionStarted(context, ordinary)
        val failure = IllegalStateException("ordinary details", IllegalArgumentException("failed at ${private.path}"))
        writeCrash(context, failure)
        val text = CrashLogManager.latestCrashLog(context)!!.readText()
        assertTrue(text.contains(ordinary.name))
        assertTrue(text.contains("ordinary details"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException"))
        assertFalse(text.contains(private.name))
        val exported = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        assertTrue(exported.contains("ordinary details"))
        assertFalse(exported.contains(private.name))
    }

    @Test fun crashMessagesRedactQuotedAliasesToDirectoriesWithBrackets() {
        val context = RuntimeEnvironment.getApplication()
        val ordinary = File(context.cacheDir, "public shelf").apply { mkdirs() }
        val private = File(context.cacheDir, "hidden shelf [edition] (v2)").apply { mkdirs() }
        val book = File(private, "hidden title.cbz").apply { writeText("fixture") }
        val alias = File(ordinary, "../${private.name}/${book.name}")
        assertEquals(book.canonicalPath, alias.canonicalPath)
        assertTrue(PrivacyStore.addPrivacyDirectory(context, private))
        CrashLogManager.recordReaderSessionStarted(context, File(ordinary, "ordinary.cbz"))

        writeCrash(context, IllegalStateException(
            "failed at \"${alias.path}\"",
            IllegalArgumentException("unquoted failure at ${alias.path}")
        ))
        val raw = CrashLogManager.latestCrashLog(context)!!.readText()
        val exported = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        listOf(raw, exported).forEach {
            assertFalse(it.contains(book.name))
            assertFalse(it.contains(private.name))
            assertTrue(it.contains("java.lang.IllegalStateException: [privacy-redacted]"))
        }
    }

    @Test fun crashMessagesKeepCompleteOrdinaryPathsWithSpaceSuffixes() {
        val context = RuntimeEnvironment.getApplication()
        val private = File(context.cacheDir, "hidden shelf").apply { mkdirs() }
        val ordinary = File(context.cacheDir, "hidden shelf public").apply { mkdirs() }
        val book = File(ordinary, "ordinary chapter.cbz").apply { writeText("fixture") }
        assertTrue(PrivacyStore.addPrivacyDirectory(context, private))
        CrashLogManager.recordReaderSessionStarted(context, book)
        val message = "index failed for \"${book.canonicalPath}\""

        val unquoted = "unquoted index failure for ${book.canonicalPath}"
        writeCrash(context, IllegalArgumentException(message, IllegalStateException(unquoted)))
        listOf(
            CrashLogManager.latestCrashLog(context)!!.readText(),
            CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        ).forEach {
            assertTrue(it.contains(message))
            assertTrue(it.contains(unquoted))
        }
    }

    @Test fun unquotedCrashPathsSeparateErrorNotesWithoutSplittingDirectoryNames() {
        val context = RuntimeEnvironment.getApplication()
        val hidden = File(context.cacheDir, "private [volume] (v2)").apply { mkdirs() }
        val ordinary = File(context.cacheDir, "private [volume] (v2) public (edition)").apply { mkdirs() }
        val hiddenAlias = File(ordinary, "../${hidden.name}")
        assertEquals(hidden.canonicalPath, hiddenAlias.canonicalPath)
        assertTrue(PrivacyStore.addPrivacyDirectory(context, hidden))
        CrashLogManager.recordReaderSessionStarted(context, File(ordinary, "ordinary.cbz"))

        val publicMessage = "Unable to list ${ordinary.canonicalPath} (Permission denied)"
        val failure = IllegalStateException(
            publicMessage,
            IllegalArgumentException("Unable to list ${hiddenAlias.path} (Permission denied)")
        )
        failure.addSuppressed(IllegalStateException(
            "Unable to read ${File(hiddenAlias, "missing page.cbz").path}: Permission denied"
        ))
        writeCrash(context, failure)

        listOf(
            CrashLogManager.latestCrashLog(context)!!.readText(),
            CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        ).forEach {
            assertTrue(it.contains(publicMessage))
            assertFalse(it.contains(hiddenAlias.path))
            assertFalse(it.contains("missing page.cbz"))
            assertTrue(it.contains("Caused by: java.lang.IllegalArgumentException: [privacy-redacted]"))
            assertTrue(it.contains("Suppressed: java.lang.IllegalStateException: [privacy-redacted]"))
        }
    }

    @Test fun fileSystemExceptionChecksBothPathsForPrivateAliases() {
        val context = RuntimeEnvironment.getApplication()
        val hidden = File(context.cacheDir, "private [volume] (v2)").apply { mkdirs() }
        val ordinary = File(context.cacheDir, "ordinary shelf").apply { mkdirs() }
        val hiddenAlias = File(ordinary, "../${hidden.name}")
        assertEquals(hidden.canonicalPath, hiddenAlias.canonicalPath)
        assertTrue(PrivacyStore.addPrivacyDirectory(context, hidden))
        val book = File(ordinary, "ordinary.cbz").apply { writeText("fixture") }
        CrashLogManager.recordReaderSessionStarted(context, book)

        val ordinaryFailure = FileSystemException(book.path, File(ordinary, "copy.cbz").path, "Permission denied")
        val privateSource = FileSystemException(hiddenAlias.path, ordinary.path, "Private source failure")
        val privateTarget = FileSystemException(ordinary.path, hiddenAlias.path, "Private target failure")
        ordinaryFailure.initCause(privateSource)
        ordinaryFailure.addSuppressed(privateTarget)
        writeCrash(context, ordinaryFailure)

        listOf(
            CrashLogManager.latestCrashLog(context)!!.readText(),
            CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        ).forEach {
            assertTrue(it.contains(ordinaryFailure.message!!))
            assertFalse(it.contains(hiddenAlias.path))
            assertTrue(it.contains("Caused by: java.nio.file.FileSystemException: [privacy-redacted]"))
            assertTrue(it.contains("Suppressed: java.nio.file.FileSystemException: [privacy-redacted]"))
        }
    }

    @Test fun relativeTargetDoesNotHidePrivateSourceFromCrashAndHistoricalExport() {
        val context = RuntimeEnvironment.getApplication()
        val hidden = File(context.cacheDir, "private [volume] (v2)").apply { mkdirs() }
        val ordinary = File(context.cacheDir, "ordinary shelf").apply { mkdirs() }
        val hiddenAlias = File(ordinary, "../${hidden.name}")
        val book = File(ordinary, "ordinary.cbz").apply { writeText("fixture") }
        assertEquals(hidden.canonicalPath, hiddenAlias.canonicalPath)
        CrashLogManager.recordReaderSessionStarted(context, book)

        val failure = FileSystemException(book.path, "ordinary copy.cbz", "Permission denied")
        failure.initCause(FileSystemException(hiddenAlias.path, "relative target.cbz", "Permission denied"))
        writeCrash(context, failure)
        val historical = CrashLogManager.latestCrashLog(context)!!
        val historicalText = historical.readText()
        assertTrue(historicalText.contains(hiddenAlias.path))
        assertTrue(historical.setLastModified(1L))

        assertTrue(PrivacyStore.addPrivacyDirectory(context, hidden))
        val historicalExport = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        assertEquals(historicalText, historical.readText())

        writeCrash(context, failure)
        listOf(
            historicalExport,
            CrashLogManager.latestCrashLog(context)!!.readText(),
            CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        ).forEach {
            assertTrue(it.contains(failure.message!!))
            assertFalse(it.contains(hiddenAlias.path))
            assertTrue(it.contains("Caused by: java.nio.file.FileSystemException: [privacy-redacted]"))
        }
    }

    @Test fun missingOrdinaryFileUnderDirectoryWithSpaceSuffixKeepsErrorMessage() {
        val context = RuntimeEnvironment.getApplication()
        val hidden = File(context.cacheDir, "private shelf").apply { mkdirs() }
        val ordinary = File(context.cacheDir, "private shelf public").apply { mkdirs() }
        val missingBook = File(ordinary, "chapter.cbz")
        val missingNestedBook = File(ordinary, "missing folder/chapter.cbz")
        val hiddenBook = File(hidden, "hidden chapter.cbz")
        assertFalse(missingBook.exists())
        assertFalse(missingNestedBook.parentFile!!.exists())
        assertTrue(PrivacyStore.addPrivacyDirectory(context, hidden))
        CrashLogManager.recordReaderSessionStarted(context, File(ordinary, "ordinary.cbz"))

        val failure = FileNotFoundException("${missingBook.path} (No such file or directory)")
        val nestedFailure = FileNotFoundException("${missingNestedBook.path} (No such file or directory)")
        failure.initCause(nestedFailure)
        failure.addSuppressed(FileNotFoundException("${hiddenBook.path} (No such file or directory)"))
        writeCrash(context, failure)

        listOf(
            CrashLogManager.latestCrashLog(context)!!.readText(),
            CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        ).forEach {
            assertTrue(it.contains(failure.message!!))
            assertTrue(it.contains(nestedFailure.message!!))
            assertFalse(it.contains(hiddenBook.name))
            assertTrue(it.contains("Suppressed: java.io.FileNotFoundException: [privacy-redacted]"))
        }
    }

    @Test fun backgroundCleanupKeepsItsPrivateSourceAfterAnOrdinaryReaderReplacesIt() {
        val context = RuntimeEnvironment.getApplication()
        val private = privateBook(context)
        val cleanup = CrashLogManager.readerCleanupAction(context, private) {
            throw IllegalStateException(private.name)
        }
        assertTrue(PrivacyStore.removePrivacyDirectory(context, private.parentFile!!))
        val ordinary = File(context.cacheDir, "ordinary successor.cbz")
        CrashLogManager.recordReaderSessionStarted(context, ordinary)
        val handlerFailure = AtomicReference<Throwable?>()
        val worker = Thread(cleanup).apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                handlerFailure.set(runCatching { writeCrash(context, throwable) }.exceptionOrNull())
            }
            start()
        }
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertNull(handlerFailure.get())

        val raw = CrashLogManager.latestCrashLog(context)!!.readText()
        assertTrue(raw.contains(ordinary.name))
        assertTrue(raw.contains("Throwable: java.lang.IllegalStateException"))
        assertFalse(raw.contains(private.name))
        assertFalse(CrashLogManager.createLatestCrashLogExport(context)!!.readText().contains(private.name))
    }

    @Test fun persistedPrivatePdfRemainsRedactedWhenNoLiveSessionOrMarkRemains() {
        val context = RuntimeEnvironment.getApplication()
        val file = privateBook(context)
        val pdf = PdfReaderDiagnostics.begin(context, file)
        pdf.started(1, 0, 4, "FULL")
        pdf.phase(1, "native_render", "failedName=${file.name}")
        PdfReaderDiagnostics.persist(context, pdf)
        ReflectionHelpers.setStaticField(PdfReaderDiagnostics::class.java, "latest", null)
        assertTrue(PrivacyStore.removePrivacyDirectory(context, file.parentFile!!))

        val text = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        assertTrue(text.contains("source=persisted"))
        assertTrue(text.contains("file=[privacy-redacted]"))
        assertTrue(text.contains("phase=native_render"))
        assertFalse(text.contains(file.name))
    }

    @Test fun legacyLogsAndPersistedSnapshotsAreRedactedWithoutRewritingTheOriginals() {
        val context = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(PdfReaderDiagnostics::class.java, "latest", null)
        val file = privateBook(context, mark = false)
        val render = formatReaderRender(file.path, 12, 100, 2, "FULL", 720, 720, 22, 10, 20)
        val pdf = "app=V1.2.3 code=1 build=release file=${file.path} size=12 modified=100\n" +
            "tMs=10.0 event=native_render page=3 preview=${file.name}"
        context.getSharedPreferences("reader_diagnostics", 0).edit().putString("latest_render", render).commit()
        context.getSharedPreferences("pdf_reader_timing", 0).edit().putString("latest_pdf_timing", pdf).commit()
        val raw = "ComicLab Crash Log\nReader render: $render\n$pdf\n" +
            "java.lang.IllegalStateException: ${file.name}\n" +
            "\tat com.example.Reader.draw(Reader.kt:42)\n" +
            "Suppressed: java.io.IOException: ${file.path}\n"
        val log = File(context.filesDir, "crash_logs/crash_old.txt").apply {
            parentFile!!.mkdirs()
            writeText(raw)
        }
        assertTrue(PrivacyStore.addPrivacyDirectory(context, file.parentFile!!))

        val exported = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        assertTrue(exported.contains("source=persisted"))
        assertFalse(exported.contains(file.name))
        assertFalse(exported.contains(file.parentFile!!.name))
        assertTrue(exported.contains("file=[privacy-redacted]"))
        assertTrue(exported.contains("event=native_render page=3"))
        assertTrue(exported.contains("java.lang.IllegalStateException"))
        assertTrue(exported.contains("Suppressed: java.io.IOException"))
        assertTrue(exported.contains("com.example.Reader.draw(Reader.kt:42)"))
        assertEquals(raw, log.readText())
        assertEquals(pdf, context.getSharedPreferences("pdf_reader_timing", 0).getString("latest_pdf_timing", null))
    }

    @Test fun activePrivacySpaceSuppressesFilenameOnlyCrashMessagesWithoutAReader() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(PrivacySessionManager.enterPrivacySpace(null, PrivacySessionManager.sessionEpoch))
        try {
            writeCrash(context, IllegalStateException("file-operation-title.cbz"))
            val text = CrashLogManager.latestCrashLog(context)!!.readText()
            assertTrue(text.contains("java.lang.IllegalStateException"))
            assertFalse(text.contains("file-operation-title.cbz"))
        } finally {
            PrivacySessionManager.exitPrivacySpace()
        }
    }

    @Test fun anActivePrivatePdfAloneSuppressesMessagesAndKeepsCustomThrowableTypes() {
        val context = RuntimeEnvironment.getApplication()
        val file = privateBook(context)
        PdfReaderDiagnostics.begin(context, file)
        writeCrash(context, RenderStopped(file.name))

        val raw = CrashLogManager.latestCrashLog(context)!!.readText()
        val exported = CrashLogManager.createLatestCrashLogExport(context)!!.readText()
        listOf(raw, exported).forEach {
            assertFalse(it.contains(file.name))
            assertTrue(it.contains(RenderStopped::class.java.name))
        }
    }

    private class RenderStopped(message: String) : RuntimeException(message)

    private fun privateBook(context: Context, mark: Boolean = true): File {
        val directory = File(context.cacheDir, "hidden shelf").apply { mkdirs() }
        if (mark) assertTrue(PrivacyStore.addPrivacyDirectory(context, directory))
        return File(directory, "private title.pdf").apply { writeText("fixture") }
    }

    private fun writeCrash(context: Context, failure: Throwable) {
        CrashLogManager::class.java.getDeclaredMethod(
            "writeCrashLog", Context::class.java, Thread::class.java, Throwable::class.java
        ).apply { isAccessible = true }.invoke(CrashLogManager, context, Thread.currentThread(), failure)
    }
}
