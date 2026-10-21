package com.example.comiclab

import com.example.comiclab.core.settings.ComicLabMangaProgress
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import com.example.comiclab.ui.reader.manga.MangaPageAdapter
import com.example.comiclab.ui.reader.manga.ReaderPreviewAdapter
import com.example.comiclab.ui.reader.manga.ReaderSubsamplingImageView
import com.example.comiclab.ui.reader.manga.SubsamplingMangaPageAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class MangaReaderCacheLifecycleRobolectricTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun staleReaderCallbackDoesNotDeleteCacheUsedByNewReader() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("shared-reader.cbz")
        val preparedCache = temporaryFolder.newFolder("shared-prepared-cache")
        populatePreparedCache(archive, preparedCache, listOf("page.png"))
        val pageFile = ComicArchive.preparedImageFile(preparedCache, 0, "page.png")

        val oldExecutor = ManualExecutorService()
        val controller = buildReaderController(application, archive, preparedCache, oldExecutor)
        val oldActivity = controller.get()

        try {
            controller.create()
            assertEquals(1, oldExecutor.pendingTaskCount)
            oldExecutor.runNextOnWorker()
            val oldViewModel = oldActivity.preparedReaderViewModelForTest

            // The posted bounds callback is still pending while Robolectric recreates the Activity.
            controller.recreate()
            val newActivity = controller.get()
            assertTrue("configuration change must create a new Activity", oldActivity !== newActivity)
            assertSame(
                "the cache owner must survive Activity recreation",
                oldViewModel,
                newActivity.preparedReaderViewModelForTest
            )
            assertTrue(pageFile.exists())

            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(
                "An old Activity callback must not delete a cache already handed to a new reader",
                pageFile.exists()
            )
        } finally {
            if (!controller.get().isDestroyed) {
                runCatching { controller.destroy() }
            }
            oldExecutor.shutdownNow()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            preparedCache.deleteRecursively()
        }
    }

    @Test
    fun startFromBeginningAppliesOnlyToFreshCreation() {
        val application = RuntimeEnvironment.getApplication()
        val (archive, preparedCache) = createPreparedReaderFixture(
            archiveName = "start-from-beginning.cbz",
            cacheName = "start-from-beginning-cache"
        )
        MangaReaderActivity.restoreReadingProgress(
            application,
            archive,
            ComicLabMangaProgress(archive.absolutePath, position = 5, offset = 24)
        )

        val executor = ManualExecutorService()
        val controller = buildReaderController(
            application = application,
            archive = archive,
            preparedCache = preparedCache,
            executor = executor,
            startFromBeginning = true
        )
        val originalActivity = controller.get()

        try {
            controller.create()
            assertEquals("a fresh start command clears older progress", null,
                MangaReaderActivity.savedReadingProgress(application, archive))
            executor.runNextOnWorker()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, currentReaderPosition(originalActivity))

            jumpReaderToPage(originalActivity, 3)
            setPendingReaderProgress(originalActivity, position = 3, offset = 29)
            saveReaderPosition(originalActivity)
            awaitReaderProgressWrite(originalActivity)
            assertEquals(
                ComicLabMangaProgress(archive.absolutePath, position = 3, offset = 29),
                MangaReaderActivity.savedReadingProgress(application, archive)
            )

            controller.recreate()
            val recreatedActivity = controller.get()
            assertTrue("configuration recreation must replace the Activity", originalActivity !== recreatedActivity)
            assertTrue("the recreated Activity must finish loading its prepared entries", awaitReaderEntries(recreatedActivity))
            assertEquals("the start command must not clear progress again", 3, currentReaderPosition(recreatedActivity))
            assertEquals(
                ComicLabMangaProgress(archive.absolutePath, position = 3, offset = 29),
                MangaReaderActivity.savedReadingProgress(application, archive)
            )
        } finally {
            if (!controller.get().isDestroyed) {
                runCatching { controller.destroy() }
            }
            executor.shutdownNow()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            preparedCache.deleteRecursively()
        }
    }

    @Test
    fun explicitStartPageAppliesOnFirstLaunchButSavedPositionWinsAfterRecreation() {
        val application = RuntimeEnvironment.getApplication()
        val (archive, preparedCache) = createPreparedReaderFixture(
            archiveName = "explicit-start-page.cbz",
            cacheName = "explicit-start-page-cache"
        )
        MangaReaderActivity.restoreReadingProgress(
            application,
            archive,
            ComicLabMangaProgress(archive.absolutePath, position = 5, offset = 12)
        )

        val executor = ManualExecutorService()
        val controller = buildReaderController(
            application = application,
            archive = archive,
            preparedCache = preparedCache,
            executor = executor,
            startPageIndex = 2
        )
        val originalActivity = controller.get()

        try {
            controller.create()
            executor.runNextOnWorker()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("a fresh explicit start page takes precedence over stored progress", 2,
                currentReaderPosition(originalActivity))

            jumpReaderToPage(originalActivity, 4)
            awaitReaderProgressWrite(originalActivity)
            assertEquals(4, MangaReaderActivity.savedReadingProgress(application, archive)?.position)

            controller.recreate()
            val recreatedActivity = controller.get()
            assertTrue("the recreated Activity must finish loading its prepared entries", awaitReaderEntries(recreatedActivity))
            assertEquals("the original start page must not override later saved progress", 4,
                currentReaderPosition(recreatedActivity))
        } finally {
            if (!controller.get().isDestroyed) {
                runCatching { controller.destroy() }
            }
            executor.shutdownNow()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            preparedCache.deleteRecursively()
        }
    }

    @Test
    fun progressWritesKeepQueueOrderAndFinishAfterActivityShutdown() {
        val application = RuntimeEnvironment.getApplication()
        val (archive, preparedCache) = createPreparedReaderFixture(
            archiveName = "ordered-progress.cbz",
            cacheName = "ordered-progress-cache"
        )
        application.getSharedPreferences("reader_prefs", 0).edit().clear().commit()
        application.getSharedPreferences("reader_diagnostics", 0)
            .edit().remove("latest_checkpoint").commit()

        val loadExecutor = ManualExecutorService()
        val progressExecutor = ManualExecutorService()
        val controller = buildReaderController(application, archive, preparedCache, loadExecutor)
        val activity = controller.get()
        activity.readerProgressExecutorForTest = progressExecutor

        try {
            controller.create()
            loadExecutor.runNextOnWorker()
            assertTrue("the prepared reader entries must load", awaitReaderEntries(activity))

            setPendingReaderProgress(activity, position = 2, offset = 13)
            saveReaderPosition(activity, forceCommit = false)
            setPendingReaderProgress(activity, position = 4, offset = 27)
            saveReaderPosition(activity, forceCommit = true)

            assertEquals(2, progressExecutor.pendingTaskCount)
            assertEquals(null, MangaReaderActivity.savedReadingProgress(application, archive))
            assertEquals(
                null,
                application.getSharedPreferences("reader_diagnostics", 0)
                    .getString("latest_checkpoint", null)
            )

            progressExecutor.runNextOnWorker()
            assertEquals(
                ComicLabMangaProgress(archive.absolutePath, position = 2, offset = 13),
                MangaReaderActivity.savedReadingProgress(application, archive)
            )
            assertEquals(
                "file=${archive.absolutePath} size=${archive.length()} modified=${archive.lastModified()} " +
                    "page=3 position=2 offset=13",
                application.getSharedPreferences("reader_diagnostics", 0)
                    .getString("latest_checkpoint", null)
            )

            progressExecutor.runNextOnWorker()
            assertEquals(
                ComicLabMangaProgress(archive.absolutePath, position = 4, offset = 27),
                MangaReaderActivity.savedReadingProgress(application, archive)
            )
            assertTrue(
                application.getSharedPreferences("reader_diagnostics", 0)
                    .getString("latest_checkpoint", null)
                    ?.endsWith("page=5 position=4 offset=27") == true
            )

            controller.destroy()
            assertTrue("onDestroy must shut down the writer without dropping queued work", progressExecutor.isShutdown)
            while (progressExecutor.pendingTaskCount > 0) {
                progressExecutor.runNextOnWorker()
            }
            assertEquals(
                ComicLabMangaProgress(archive.absolutePath, position = 4, offset = 27),
                MangaReaderActivity.savedReadingProgress(application, archive)
            )
            assertTrue(progressExecutor.isTerminated)
        } finally {
            if (!controller.get().isDestroyed) {
                runCatching { controller.destroy() }
            }
            loadExecutor.shutdownNow()
            progressExecutor.shutdownNow()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            preparedCache.deleteRecursively()
        }
    }

    @Test
    fun emptyPreparedManifestFallsBackToImagesInOriginalArchive() {
        val application = RuntimeEnvironment.getApplication()
        val archive = File(temporaryFolder.root, "fallback.cbz")
        writeZip(archive, "page.png" to createPng())
        val preparedCache = temporaryFolder.newFolder("empty-prepared-cache")
        ComicArchive.writePreparedReaderManifest(preparedCache, emptyList())
        val executor = ManualExecutorService()
        val controller = buildReaderController(application, archive, preparedCache, executor)
        val activity = controller.get()

        try {
            controller.create()

            assertEquals("fallback must use the normal archive loader", 1, executor.pendingTaskCount)
            executor.runNextOnWorker()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf("page.png"), activity.imageEntriesForTest)
            assertTrue("fallback must preserve the original archive", archive.isFile)
        } finally {
            if (!activity.isDestroyed) {
                runCatching { controller.destroy() }
            }
            executor.shutdownNow()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            preparedCache.deleteRecursively()
        }
    }

    @Test
    fun previewCloseWaitsForActiveDecodeBeforeClosingSession() {
        val archive = temporaryFolder.newFile("preview.cbz")
        val session = BlockingPreviewSession()
        val adapter = ReaderPreviewAdapter(
            context = RuntimeEnvironment.getApplication(),
            archiveFile = archive,
            entries = listOf("page.png"),
            sharedSession = session,
            onPageClick = {}
        )
        try {
            adapter.loadThumbnailForTest(0)
            assertTrue(session.decodeStarted.await(5, TimeUnit.SECONDS))

            adapter.close()

            assertFalse("The preview session must remain open during an active decode", session.closed.get())
            session.finishDecode.countDown()
            assertTrue(session.closeCompleted.await(5, TimeUnit.SECONDS))
            assertTrue(session.closed.get())
        } finally {
            session.finishDecode.countDown()
            adapter.close()
        }
    }

    @Test
    fun previewTimeoutReleasesSessionAfterDecodeEventuallyStops() {
        val archive = temporaryFolder.newFile("preview-timeout.cbz")
        val session = BlockingPreviewSession()
        val executor = TimedOutExecutorService()
        val adapter = ReaderPreviewAdapter(
            context = RuntimeEnvironment.getApplication(),
            archiveFile = archive,
            entries = listOf("page.png"),
            sharedSession = session,
            onPageClick = {}
        )
        adapter.decodeExecutorForTest = executor

        try {
            adapter.loadThumbnailForTest(0)
            assertTrue(session.decodeStarted.await(5, TimeUnit.SECONDS))

            adapter.close()
            assertTrue(executor.awaitCalled.await(5, TimeUnit.SECONDS))
            session.finishDecode.countDown()
            assertTrue(executor.taskFinished.await(5, TimeUnit.SECONDS))

            assertTrue(
                "A timed-out close needs a late release after the decoder task eventually stops",
                session.closeCompleted.await(2, TimeUnit.SECONDS)
            )
        } finally {
            session.finishDecode.countDown()
            adapter.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun archiveTimeoutClosesSessionAfterDecodeEventuallyStops() {
        val session = BlockingPreviewSession()
        val adapter = newMangaPageAdapter(session)
        val executor = TimedOutThreadPoolExecutor()
        adapter.decodeExecutorForTest = executor
        adapter.ensurePreviewForTest(0, 0, 0, false)

        try {
            assertTrue(session.decodeStarted.await(5, TimeUnit.SECONDS))

            adapter.close()

            assertTrue("close should record the timeout before the decode is released", executor.firstAwaitReturned.await(5, TimeUnit.SECONDS))
            assertFalse("the archive session must stay open while decode still reads it", session.closed.get())
            session.finishDecode.countDown()
            assertTrue(session.closeCompleted.await(5, TimeUnit.SECONDS))
            assertEquals("late completion must close the session exactly once", 1, session.closeCount.get())
        } finally {
            session.finishDecode.countDown()
            adapter.close()
            executor.shutdownNow()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun subsamplingCloseWaitsForLateInitializationAndRecyclesDecoderOnce() {
        val context = RuntimeEnvironment.getApplication()
        val imageFile = temporaryFolder.newFile("subsampling-late-init.png")
        val decoder = BlockingRegionDecoder()
        val view = ReaderSubsamplingImageView(context)
        view.setRegionDecoderFactory(object : DecoderFactory<ImageRegionDecoder> {
            override fun make(): ImageRegionDecoder = decoder
        })
        view.setMaxTileSize(64)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 48, 48)
        view.setReaderImage(ImageSource.uri(Uri.fromFile(imageFile)).tilingEnabled())

        assertTrue("the fake region decoder should begin initialization", pumpMainUntil {
            decoder.initStarted.count == 0L
        })
        val closeResult = AtomicReference<Result<Unit>?>(null)
        val closeCompleted = CountDownLatch(1)
        view.closeReaderImage {
            closeResult.set(it)
            closeCompleted.countDown()
        }

        assertEquals("close must wait for in-flight decoder initialization", 1L, closeCompleted.count)
        decoder.finishInit.countDown()
        assertTrue("late initialization must be drained before close finishes", pumpMainUntil {
            closeCompleted.count == 0L
        })

        assertTrue(closeResult.get()?.isSuccess == true)
        assertEquals("the attached native decoder is recycled once", 1, decoder.recycleCount.get())
        assertEquals("queued tile reads are canceled before decoder recycle", 0, decoder.decodeCount.get())
    }

    @Test
    fun subsamplingCloseWaitsForActiveTileDecodeBeforeRecycle() {
        val context = RuntimeEnvironment.getApplication()
        val imageFile = temporaryFolder.newFile("subsampling-active-tile.png")
        val decoder = BlockingTileRegionDecoder()
        val view = ReaderSubsamplingImageView(context)
        view.setRegionDecoderFactory(object : DecoderFactory<ImageRegionDecoder> {
            override fun make(): ImageRegionDecoder = decoder
        })
        view.setMaxTileSize(64)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 48, 48)
        view.setReaderImage(ImageSource.uri(Uri.fromFile(imageFile)).tilingEnabled())

        assertTrue("a real tile task should enter the fake region decoder", pumpMainUntil {
            decoder.decodeStarted.count == 0L
        })
        val closeResult = AtomicReference<Result<Unit>?>(null)
        val closeCompleted = CountDownLatch(1)
        view.closeReaderImage {
            closeResult.set(it)
            closeCompleted.countDown()
        }

        assertEquals("recycle cannot run while decodeRegion is active", 0, decoder.recycleCount.get())
        decoder.finishDecode.countDown()
        assertTrue("tile callback completion must precede decoder recycle", pumpMainUntil {
            closeCompleted.count == 0L
        })

        assertTrue(closeResult.get()?.isSuccess == true)
        assertTrue(decoder.completedDecodeCount.get() > 0)
        assertEquals("recycle runs only after every active native read ended", 0, decoder.activeAtRecycle.get())
        assertEquals(1, decoder.recycleCount.get())
    }

    @Test
    fun subsamplingRebindWaitsForOldInitializationAndUsesLatestRequestedSource() {
        val context = RuntimeEnvironment.getApplication()
        val firstFile = temporaryFolder.newFile("subsampling-first.png")
        temporaryFolder.newFile("subsampling-skipped.png")
        val latestFile = temporaryFolder.newFile("subsampling-latest.png")
        val firstDecoder = BlockingRegionDecoder()
        val latestDecoder = BlockingRegionDecoder()
        val factoryCalls = AtomicInteger()
        val view = ReaderSubsamplingImageView(context)
        view.setRegionDecoderFactory(object : DecoderFactory<ImageRegionDecoder> {
            override fun make(): ImageRegionDecoder {
                return if (factoryCalls.incrementAndGet() == 1) firstDecoder else latestDecoder
            }
        })
        view.setMaxTileSize(64)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 48, 48)
        view.setReaderImage(ImageSource.uri(Uri.fromFile(firstFile)).tilingEnabled())

        assertTrue(pumpMainUntil { firstDecoder.initStarted.count == 0L })
        view.setReaderImage(
            ImageSource.uri(Uri.fromFile(temporaryFolder.root.resolve("subsampling-skipped.png")))
                .tilingEnabled()
        )
        view.setReaderImage(ImageSource.uri(Uri.fromFile(latestFile)).tilingEnabled())
        assertEquals("the old decoder stays alive while initialization is active", 0, firstDecoder.recycleCount.get())

        firstDecoder.finishInit.countDown()
        assertTrue("the latest source should initialize after the old decoder drains", pumpMainUntil {
            latestDecoder.initStarted.count == 0L
        })
        assertTrue(latestDecoder.initializedUri.get().toString().endsWith(latestFile.name))
        assertEquals("intermediate holder bindings should coalesce", 2, factoryCalls.get())

        val closeCompleted = CountDownLatch(1)
        val closeResult = AtomicReference<Result<Unit>?>(null)
        view.closeReaderImage {
            closeResult.set(it)
            closeCompleted.countDown()
        }
        latestDecoder.finishInit.countDown()
        assertTrue(pumpMainUntil { closeCompleted.count == 0L })

        assertTrue(closeResult.get()?.isSuccess == true)
        assertEquals(1, firstDecoder.recycleCount.get())
        assertEquals(1, latestDecoder.recycleCount.get())
    }

    @Test
    fun preparedCacheLeaseWaitsForSubsamplingDecoderCloseBeforePendingDelete() {
        val context = RuntimeEnvironment.getApplication()
        val preparedCache = temporaryFolder.newFolder("prepared-subsampling-close")
        val imageFile = File(preparedCache, "page.png").apply { writeBytes(createPng()) }
        val deletionExecutor = ManualDeletionExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor = deletionExecutor)
        val readerOwner = checkNotNull(registry.acquireReaderOwner(preparedCache))
        val cacheUseLease = checkNotNull(registry.acquireUsageLease(preparedCache))
        readerOwner.requestDeleteAndClose()

        val adapter = newPreparedMangaPageAdapter(preparedCache, imageFile, cacheUseLease)
        val decoder = BlockingRegionDecoder()
        val view = newTiledReaderView(context, imageFile, decoder)
        adapter.activeSubsamplingViewsForTest.add(view)

        assertTrue("the prepared image should enter decoder initialization", pumpMainUntil {
            decoder.initStarted.count == 0L
        })
        adapter.close()

        assertTrue("the pending-delete cache must remain while native init is running", imageFile.exists())
        assertEquals("the cache lease has not reached its deletion executor", 0, deletionExecutor.pendingTaskCount)

        decoder.finishInit.countDown()
        assertTrue("the lease releases after decoder recycle", pumpMainUntil {
            deletionExecutor.deletionScheduled.count == 0L
        })
        assertEquals(1, decoder.recycleCount.get())
        assertTrue("deletion remains the final registry action", imageFile.exists())
        deletionExecutor.runNext()
        assertFalse(imageFile.exists())
    }

    @Test
    fun preparedCacheLeaseWaitsForExtractorAfterCloseTimeout() {
        val preparedCache = temporaryFolder.newFolder("prepared-extractor-timeout")
        val imageFile = File(preparedCache, "page.png").apply { writeBytes(createPng()) }
        val deletionExecutor = ManualDeletionExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor = deletionExecutor)
        val readerOwner = checkNotNull(registry.acquireReaderOwner(preparedCache))
        val cacheUseLease = checkNotNull(registry.acquireUsageLease(preparedCache))
        readerOwner.requestDeleteAndClose()
        val adapter = newPreparedMangaPageAdapter(
            preparedCache,
            imageFile,
            cacheUseLease,
            closeWaitTimeoutMs = 0L
        )
        val extractorExecutor = TimedOutThreadPoolExecutor()
        adapter.extractExecutorForTest = extractorExecutor
        val extractionStarted = CountDownLatch(1)
        val finishExtraction = CountDownLatch(1)
        extractorExecutor.execute {
            extractionStarted.countDown()
            var finished = false
            while (!finished) {
                try {
                    finishExtraction.await()
                    finished = true
                } catch (_: InterruptedException) {
                    // Keep the simulated archive extraction active until the test releases it.
                }
            }
        }

        try {
            assertTrue(extractionStarted.await(5, TimeUnit.SECONDS))
            adapter.close()

            assertTrue("the configured close timeout should be observed", extractorExecutor.firstAwaitReturned.await(5, TimeUnit.SECONDS))
            assertTrue("the cache stays leased while extraction still reads it", imageFile.exists())
            assertEquals(0, deletionExecutor.pendingTaskCount)

            finishExtraction.countDown()
            assertTrue("pending delete starts after extractor termination", pumpMainUntil {
                deletionExecutor.deletionScheduled.count == 0L
            })
            assertTrue("delete runs only after the late extractor close", imageFile.exists())
            deletionExecutor.runNext()
            assertFalse(imageFile.exists())
        } finally {
            finishExtraction.countDown()
            extractorExecutor.shutdownNow()
        }
    }

    @Test
    fun preparedCacheLeaseStaysHeldWhenSubsamplingRecycleFails() {
        val context = RuntimeEnvironment.getApplication()
        val preparedCache = temporaryFolder.newFolder("prepared-recycle-failure")
        val imageFile = File(preparedCache, "page.png").apply { writeBytes(createPng()) }
        val deletionExecutor = ManualDeletionExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor = deletionExecutor)
        val readerOwner = checkNotNull(registry.acquireReaderOwner(preparedCache))
        val cacheUseLease = checkNotNull(registry.acquireUsageLease(preparedCache))
        readerOwner.requestDeleteAndClose()
        val adapter = newPreparedMangaPageAdapter(preparedCache, imageFile, cacheUseLease)
        val decoder = BlockingRegionDecoder(failOnRecycle = true).apply { finishInit.countDown() }
        val view = newTiledReaderView(context, imageFile, decoder)
        adapter.activeSubsamplingViewsForTest.add(view)
        assertTrue("the failing fake decoder must initialize before close", pumpMainUntil {
            decoder.initStarted.count == 0L
        })

        adapter.close()
        val closeResult = AtomicReference<Result<Unit>?>(null)
        val closeCompleted = CountDownLatch(1)
        view.closeReaderImage {
            closeResult.set(it)
            closeCompleted.countDown()
        }
        assertTrue("recycle failure is returned to the adapter close gate", pumpMainUntil {
            closeCompleted.count == 0L
        })

        assertTrue(closeResult.get()?.isFailure == true)
        assertTrue("an uncertain decoder close must retain the cache", imageFile.exists())
        assertEquals("a failed close must not schedule cache deletion", 0, deletionExecutor.pendingTaskCount)
    }

    private fun pumpMainUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        while (!condition() && System.nanoTime() < deadline) {
            mainLooper.idle()
            Thread.yield()
        }
        mainLooper.idle()
        return condition()
    }

    private fun newMangaPageAdapter(session: ComicArchive.ImageReaderSession): MangaPageAdapter {
        val context = RuntimeEnvironment.getApplication()
        return MangaPageAdapter(
            context = context,
            recyclerView = androidx.recyclerview.widget.RecyclerView(context),
            archiveFile = temporaryFolder.newFile("archive-close.cbz"),
            session = session,
            entries = listOf("page.png"),
            baseDisplayWidth = 100,
            baseDisplayHeight = 100,
            decodeWidth = 100,
            decodeHeight = 100,
            readingDirection = "LTR",
            doublePageReading = false,
            coverSinglePage = false,
            onPageReady = { _: Int -> },
            onPageTap = {},
            spreadAnchorSourcePosition = null
        )
    }

    private fun newPreparedMangaPageAdapter(
        preparedCache: File,
        imageFile: File,
        cacheUseLease: PreparedReaderCacheLeaseRegistry.Lease,
        closeWaitTimeoutMs: Long = 8_000L
    ): SubsamplingMangaPageAdapter {
        val context = RuntimeEnvironment.getApplication()
        return SubsamplingMangaPageAdapter(
            context = context,
            recyclerView = androidx.recyclerview.widget.RecyclerView(context),
            archiveFile = temporaryFolder.newFile("prepared-adapter-close.cbz"),
            entryNames = listOf("page.png"),
            pageFiles = listOf(imageFile),
            boundsByPosition = emptyMap<Int, ComicArchive.ImageBounds>(),
            preparedCacheDir = preparedCache,
            cacheUseLease = cacheUseLease,
            baseDisplayWidth = 48,
            baseDisplayHeight = 48,
            readingDirection = AppSettings.READING_DIRECTION_LEFT_TO_RIGHT,
            doublePageReading = false,
            coverSinglePage = false,
            onPageReady = { _: Int -> },
            onPageTap = {},
            closeWaitTimeoutMs = closeWaitTimeoutMs,
            spreadAnchorSourcePosition = null
        )
    }

    private fun newTiledReaderView(
        context: android.content.Context,
        imageFile: File,
        decoder: BlockingRegionDecoder
    ): ReaderSubsamplingImageView {
        val view = ReaderSubsamplingImageView(context)
        view.setRegionDecoderFactory(object : DecoderFactory<ImageRegionDecoder> {
            override fun make(): ImageRegionDecoder = decoder
        })
        view.setMaxTileSize(64)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 48, 48)
        view.setReaderImage(ImageSource.uri(Uri.fromFile(imageFile)).tilingEnabled())
        return view
    }

    private fun buildReaderController(
        application: android.app.Application,
        archive: File,
        preparedCache: File,
        executor: ManualExecutorService,
        startFromBeginning: Boolean = false,
        startPageIndex: Int = -1
    ) = Robolectric.buildActivity(
        MangaReaderActivity::class.java,
        Intent(application, MangaReaderActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
            putExtra(MangaReaderActivity.EXTRA_PREPARED_READER_CACHE_DIR, preparedCache.absolutePath)
            if (startFromBeginning) {
                putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, true)
            }
            if (startPageIndex >= 0) {
                putExtra(MangaReaderActivity.EXTRA_START_PAGE_INDEX, startPageIndex)
            }
        }
    ).also { controller ->
        controller.get().readerLoadExecutorForTest = executor
    }

    private fun createPreparedReaderFixture(
        archiveName: String,
        cacheName: String,
        pageCount: Int = 6
    ): Pair<File, File> {
        val archive = temporaryFolder.newFile(archiveName)
        val preparedCache = temporaryFolder.newFolder(cacheName)
        val entries = (0 until pageCount).map { "page-$it.png" }
        populatePreparedCache(archive, preparedCache, entries)
        return archive to preparedCache
    }

    private fun populatePreparedCache(archive: File, preparedCache: File, entries: List<String>) {
        val imageBytes = createPng()
        writeZip(archive, *entries.map { it to imageBytes }.toTypedArray())
        ComicArchive.writePreparedReaderManifest(preparedCache, entries)
        val preparedEntries = entries.mapIndexed { index, name -> ComicArchive.PreparedImageEntry(index, name) }
        val files = ComicArchive.extractPreparedImagesToDirectory(archive, preparedEntries, preparedCache) { _, _ -> }
        assertEquals(entries.size, files.size)
    }

    private fun currentReaderPosition(activity: MangaReaderActivity): Int =
        activity.currentReaderPositionForTest

    private fun setPendingReaderProgress(activity: MangaReaderActivity, position: Int, offset: Int) {
        activity.setPendingReaderProgressForTest(position, offset)
    }

    private fun saveReaderPosition(activity: MangaReaderActivity, forceCommit: Boolean = true) {
        activity.saveReaderPositionForTest(forceCommit)
    }

    private fun jumpReaderToPage(activity: MangaReaderActivity, position: Int) {
        activity.jumpReaderToPageForTest(position)
    }

    private fun awaitReaderProgressWrite(activity: MangaReaderActivity) {
        val executor = activity.readerProgressExecutorForTest
        val completed = CountDownLatch(1)
        executor.execute { completed.countDown() }
        assertTrue("reader progress writes must finish before recreation", completed.await(5, TimeUnit.SECONDS))
    }

    private fun awaitReaderEntries(activity: MangaReaderActivity): Boolean = pumpMainUntil {
        activity.imageEntriesForTest.size == 6
    }

    private fun createPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun writeZip(file: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private class ManualExecutorService : AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var shutdown = false

        val pendingTaskCount: Int
            get() = tasks.size

        @Synchronized
        override fun execute(command: Runnable) {
            check(!shutdown)
            tasks += command
        }

        fun runNextOnWorker() {
            val task = synchronized(this) { tasks.removeAt(0) }
            val finished = CountDownLatch(1)
            Thread({
                try {
                    task.run()
                } finally {
                    finished.countDown()
                }
            }, "MangaReaderCacheLifecycleTest").apply { isDaemon = true }.start()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }

        @Synchronized
        override fun shutdown() {
            shutdown = true
        }

        @Synchronized
        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        @Synchronized
        override fun isShutdown(): Boolean = shutdown

        @Synchronized
        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }

    private class BlockingPreviewSession : ComicArchive.ImageReaderSession {
        val decodeStarted = CountDownLatch(1)
        val finishDecode = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val closeCount = AtomicInteger()

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? = null

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            decodeStarted.countDown()
            var finished = false
            while (!finished) {
                try {
                    finishDecode.await()
                    finished = true
                } catch (_: InterruptedException) {
                    // Keep the simulated archive read active until the test releases it.
                }
            }
            return null
        }

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? = null

        override fun decodeImageForPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? = null

        override fun decodeRegionForWidth(
            entryName: String,
            sourceRect: Rect,
            targetWidth: Int
        ): Bitmap? = null

        override fun close() {
            closeCount.incrementAndGet()
            closed.set(true)
            closeCompleted.countDown()
        }
    }

    private class BlockingRegionDecoder(
        private val failOnRecycle: Boolean = false
    ) : ImageRegionDecoder {
        val initStarted = CountDownLatch(1)
        val finishInit = CountDownLatch(1)
        val initCompleted = CountDownLatch(1)
        val recycleCount = AtomicInteger()
        val decodeCount = AtomicInteger()
        val initializedUri = AtomicReference<Uri?>(null)
        private val ready = AtomicBoolean(false)

        override fun init(context: android.content.Context, uri: Uri): Point {
            initStarted.countDown()
            initializedUri.set(uri)
            var finished = false
            while (!finished) {
                try {
                    finishInit.await()
                    finished = true
                } catch (_: InterruptedException) {
                    // The test releases initialization explicitly to model a non-cancelable native call.
                }
            }
            ready.set(true)
            initCompleted.countDown()
            return Point(128, 128)
        }

        override fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap {
            decodeCount.incrementAndGet()
            check(ready.get()) { "tile decode started after decoder recycle" }
            return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        }

        override fun isReady(): Boolean = ready.get()

        override fun recycle() {
            recycleCount.incrementAndGet()
            ready.set(false)
            if (failOnRecycle) {
                throw IllegalStateException("simulated native recycle failure")
            }
        }
    }

    private class BlockingTileRegionDecoder : ImageRegionDecoder {
        val decodeStarted = CountDownLatch(1)
        val finishDecode = CountDownLatch(1)
        val recycleCount = AtomicInteger()
        val completedDecodeCount = AtomicInteger()
        val activeAtRecycle = AtomicInteger(-1)
        private val activeDecodeCount = AtomicInteger()
        private val ready = AtomicBoolean(false)

        override fun init(context: android.content.Context, uri: Uri): Point {
            ready.set(true)
            return Point(128, 128)
        }

        override fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap {
            activeDecodeCount.incrementAndGet()
            decodeStarted.countDown()
            try {
                var finished = false
                while (!finished) {
                    try {
                        finishDecode.await()
                        finished = true
                    } catch (_: InterruptedException) {
                        // Model a native region read that completes only when the test releases it.
                    }
                }
                check(ready.get()) { "tile decode began after decoder recycle" }
                return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            } finally {
                completedDecodeCount.incrementAndGet()
                activeDecodeCount.decrementAndGet()
            }
        }

        override fun isReady(): Boolean = ready.get()

        override fun recycle() {
            activeAtRecycle.set(activeDecodeCount.get())
            recycleCount.incrementAndGet()
            ready.set(false)
        }
    }

    private class TimedOutThreadPoolExecutor : java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        java.util.concurrent.PriorityBlockingQueue()
    ) {
        val firstAwaitReturned = CountDownLatch(1)
        private val awaitCount = AtomicInteger()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            if (awaitCount.incrementAndGet() == 1) {
                firstAwaitReturned.countDown()
                return false
            }
            return super.awaitTermination(timeout, unit)
        }
    }

    private class ManualDeletionExecutor : java.util.concurrent.Executor {
        private val tasks = mutableListOf<Runnable>()
        val deletionScheduled = CountDownLatch(1)

        val pendingTaskCount: Int
            get() = synchronized(tasks) { tasks.size }

        override fun execute(command: Runnable) {
            synchronized(tasks) { tasks += command }
            deletionScheduled.countDown()
        }

        fun runNext() {
            val task = synchronized(tasks) { tasks.removeAt(0) }
            task.run()
        }
    }

    private class TimedOutExecutorService : AbstractExecutorService() {
        val awaitCalled = CountDownLatch(1)
        val taskFinished = CountDownLatch(1)
        val awaitCount = AtomicInteger()
        @Volatile
        private var shutdown = false
        @Volatile
        private var worker: Thread? = null

        override fun execute(command: Runnable) {
            worker = Thread({
                try {
                    command.run()
                } finally {
                    taskFinished.countDown()
                }
            }, "MangaReaderCacheTimeoutTest").apply { isDaemon = true; start() }
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            worker?.interrupt()
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && taskFinished.count == 0L

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            awaitCalled.countDown()
            if (awaitCount.incrementAndGet() == 1) {
                return false
            }
            return taskFinished.await(timeout, unit)
        }
    }
}
