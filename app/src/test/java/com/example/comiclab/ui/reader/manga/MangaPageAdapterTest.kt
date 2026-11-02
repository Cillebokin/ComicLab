package com.example.comiclab.ui.reader.manga

import android.content.ComponentCallbacks2
import org.junit.Assert.assertSame
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.AppSettings
import com.example.comiclab.ComicArchive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaPageAdapterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun rejectedPdfFirstImageDoesNotContinuouslyRequeueItsUpgrade() {
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        })
        val adapter = newAdapter(session, listOf("0.pdf", "1.pdf"))
        val cache = MangaPageAdapter::class.java.getDeclaredField("previewBitmapCache").apply { isAccessible = true }
            .get(adapter) as android.util.LruCache<Int, Bitmap>
        cache.resize(1)
        val visible = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        MangaPageAdapter::class.java.getDeclaredMethod("putPreviewBitmap", Int::class.javaPrimitiveType,
            Bitmap::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(adapter, 0, visible, 360)
        for (field in listOf("visibleWindowStart", "visibleWindowEnd")) {
            MangaPageAdapter::class.java.getDeclaredField(field).apply { isAccessible = true }.setInt(adapter, 0)
        }
        @Suppress("UNCHECKED_CAST")
        val bound = MangaPageAdapter::class.java.getDeclaredField("boundPositions").apply { isAccessible = true }
            .get(adapter) as MutableSet<Int>
        bound.add(1)
        try {
            requestPreview(adapter, 1)
            assertTrue(awaitCondition { session.previewWidths.isNotEmpty() && adapter.loadingPreviewPagesForTest.isEmpty() })
            repeat(10) { Shadows.shadowOf(Looper.getMainLooper()).idle() }
            assertEquals("rejection must wait for viewport changes instead of requeueing", 1, session.previewWidths.size)
        } finally { closeAndAwait(adapter, session); visible.recycle() }
    }

    @Test fun distantPdfPreviewsCannotEvictABoundFirstImageUnderCachePressure() {
        val session = FakeReaderSession(pdfSource = true)
        val adapter = newAdapter(session, (0..30).map { "$it.pdf" })
        val cache = MangaPageAdapter::class.java.getDeclaredField("previewBitmapCache").apply { isAccessible = true }
            .get(adapter) as android.util.LruCache<Int, Bitmap>
        cache.resize(4)
        @Suppress("UNCHECKED_CAST")
        val bound = MangaPageAdapter::class.java.getDeclaredField("boundPositions").apply { isAccessible = true }
            .get(adapter) as MutableSet<Int>
        bound.add(1)
        val images = listOf(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888))
        val put = MangaPageAdapter::class.java.getDeclaredMethod("putPreviewBitmap", Int::class.javaPrimitiveType,
            Bitmap::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        try {
            put.invoke(adapter, 1, images[0], 360)
            bound.add(20)
            put.invoke(adapter, 20, images[1], 360)
            val upgrade = Bitmap.createBitmap(64, 16, Bitmap.Config.ARGB_8888)
            put.invoke(adapter, 20, upgrade, 1080)
            assertTrue("offscreen HD replacement cannot borrow the old compact priority", upgrade.isRecycled)
            assertSame(images[0], adapter.cachedPreviewForTest(1))
            put.invoke(adapter, 20, images[1], 1080)
            put.invoke(adapter, 21, images[2], 360)
            assertSame("distant writes must not evict the bound first image", images[0], adapter.cachedPreviewForTest(1))
            assertNull("offscreen large previews must give way to new compact first images", adapter.cachedPreviewForTest(20))
            assertTrue(cache.size() <= cache.maxSize())
            val distant = adapter.cachedPreviewForTest(21)
            val rejected = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            put.invoke(adapter, 22, rejected, 1080)
            assertTrue("rejected result must be released", rejected.isRecycled)
            assertSame("failed admission must leave the old cache intact", distant, adapter.cachedPreviewForTest(21))
            assertSame(images[0], adapter.cachedPreviewForTest(1))
        } finally { closeAndAwait(adapter, session); images.forEach { if (!it.isRecycled) it.recycle() } }
    }

    @Test fun aMissingPreviewRechecksItsSizeIfFastScrollBeginsWhileAcquiringTheSession() {
        val acquiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeReaderSession(pdfSource = true)
        val adapter = newAdapter(session, listOf("page.pdf"))
        MangaPageAdapter::class.java.getDeclaredField("decodeSessionForWorker").apply {
            isAccessible = true
            set(adapter, object : ThreadLocal<ComicArchive.ImageReaderSession>() {
                override fun initialValue(): ComicArchive.ImageReaderSession {
                    acquiring.countDown(); awaitUninterruptibly(release); return session
                }
            })
        }
        try {
            requestPreview(adapter, 0)
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            adapter.preloadAround(0, 1, isFastScroll = true)
            release.countDown()
            assertTrue(awaitCondition { adapter.loadingPreviewPagesForTest.isEmpty() })
            assertEquals(360, session.previewWidths.first().second)
        } finally { release.countDown(); closeAndAwait(adapter, session) }
    }

    @Test fun anIdleUpgradeBlockedOnSessionAcquisitionIsSkippedIfMotionBegins() {
        val acquiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeReaderSession(pdfSource = true)
        val recycler = RecyclerView(RuntimeEnvironment.getApplication())
        val adapter = newAdapter(session, listOf("page.pdf"), recyclerView = recycler)
        val compact = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        MangaPageAdapter::class.java.getDeclaredMethod("putPreviewBitmap", Int::class.javaPrimitiveType,
            Bitmap::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(adapter, 0, compact, 360)
        MangaPageAdapter::class.java.getDeclaredField("decodeSessionForWorker").apply {
            isAccessible = true
            set(adapter, object : ThreadLocal<ComicArchive.ImageReaderSession>() {
                override fun initialValue(): ComicArchive.ImageReaderSession {
                    acquiring.countDown(); awaitUninterruptibly(release); return session
                }
            })
        }
        try {
            requestPreview(adapter, 0)
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            setScrollStateForTest(recycler, RecyclerView.SCROLL_STATE_SETTLING)
            adapter.preloadAround(0, 1, isFastScroll = true)
            release.countDown()
            assertTrue(awaitCondition { adapter.loadingPreviewPagesForTest.isEmpty() })
            assertTrue("an upgrade must recheck motion after acquiring its session", session.previewWidths.isEmpty())
            assertFalse(adapter.failedPreviewPagesForTest.contains(0))
            setScrollStateForTest(recycler, RecyclerView.SCROLL_STATE_IDLE)
            adapter.preloadAround(0, 1, isIdle = true)
            assertTrue(awaitCondition { session.previewWidths.any { it.second == 1080 } })
        } finally { release.countDown(); closeAndAwait(adapter, session); compact.recycle() }
    }

    @Test fun aPreviewThatBecomesObsoleteDuringSessionAcquisitionDoesNotEnterNativeRendering() {
        val acquiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeReaderSession(pdfSource = true)
        val adapter = newAdapter(session, (0..20).map { "page-$it.pdf" })
        val local = object : ThreadLocal<ComicArchive.ImageReaderSession>() {
            override fun initialValue(): ComicArchive.ImageReaderSession {
                acquiring.countDown(); awaitUninterruptibly(release); return session
            }
        }
        MangaPageAdapter::class.java.getDeclaredField("decodeSessionForWorker").apply {
            isAccessible = true; set(adapter, local)
        }
        try {
            requestPreview(adapter, 0)
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            adapter.preloadAround(10, 1, isFastScroll = true)
            release.countDown()
            assertTrue(awaitCondition { !adapter.loadingPreviewPagesForTest.contains(0) })
            assertFalse("an obsolete request must not consume a native page render", session.previewWidths.any { it.first == "page-0.pdf" })
            assertFalse("cancellation must not poison retry state", adapter.failedPreviewPagesForTest.contains(0))
        } finally { release.countDown(); closeAndAwait(adapter, session) }
    }

    @Test fun cachedVisiblePreviewDoesNotQueueQualityWorkDuringMotionButUpgradesAtIdle() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            if (it == "busy.pdf") { started.countDown(); awaitUninterruptibly(release) }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmaps::add)
        })
        val recycler = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recycler, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, listOf("busy.pdf", "visible.pdf", "next.pdf"), recyclerView = recycler)
        try {
            requestPreview(adapter, 0)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val compact = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmaps::add)
            MangaPageAdapter::class.java.getDeclaredMethod("putPreviewBitmap", Int::class.javaPrimitiveType,
                Bitmap::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(adapter, 1, compact, 360)
            adapter.preloadAround(1, 1, isFastScroll = true)
            assertFalse("quality work must not occupy the queue while pages are moving", adapter.loadingPreviewPagesForTest.contains(1))
            assertSame(compact, adapter.cachedPreviewForTest(1))
            setScrollStateForTest(recycler, RecyclerView.SCROLL_STATE_IDLE)
            adapter.preloadAround(1, 1, isIdle = true)
            release.countDown()
            assertTrue(awaitCondition { session.previewWidths.contains("visible.pdf" to 1080) })
        } finally {
            release.countDown(); closeAndAwait(adapter, session)
            bitmaps.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test fun aLateCreatedPdfAdapterWaitsForAnExplicitResumeBeforeStartingPeriodicDiagnostics() {
        val session = FakeReaderSession(pdfSource = true)
        val adapter = newAdapter(session, listOf("page.pdf"))
        val context = RuntimeEnvironment.getApplication()
        fun pipelineCount() = Regex("pipeline:[0-9]+").find(
            com.example.comiclab.core.diagnostics.PdfReaderDiagnostics.snapshot(context)!!)!!.value
        try {
            val initial = pipelineCount()
            // Indexing may finish after Activity.onPause, before an adapter exists to pause.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
            assertEquals("construction must not imply that the reader is resumed", initial, pipelineCount())
            adapter.onReaderResumed()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
            assertFalse("an explicitly resumed reader should refresh diagnostics", initial == pipelineCount())
            adapter.onReaderPaused()
            val paused = pipelineCount()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
            assertEquals(paused, pipelineCount())
        } finally { closeAndAwait(adapter, session) }
    }

    @Test fun rapidReverseJumpsAndMemoryPressureKeepLatestVisiblePageFirstAndDiagnosticsBounded() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..100).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entry ->
            if (entry == entries[0]) {
                com.example.comiclab.core.diagnostics.PdfReaderDiagnostics.nativePhase("native_render")
                started.countDown()
                awaitUninterruptibly(release)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmaps::add)
        })
        val recycler = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recycler, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recycler)
        try {
            requestPreview(adapter, 0)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(120) { step ->
                val forward = step % 2 == 0
                adapter.preloadAround(if (forward) 70 + step % 20 else 10 + step % 20, 1,
                    scrollDirection = if (forward) 1 else -1, isFastScroll = true, isIdle = false)
            }
            adapter.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            adapter.onReaderResumed()
            adapter.preloadAround(50, 1, scrollDirection = -1, isFastScroll = true, isIdle = false)
            val context = RuntimeEnvironment.getApplication()
            val waiting = com.example.comiclab.core.diagnostics.PdfReaderDiagnostics.snapshot(context)!!
            assertTrue(waiting.contains("task=0 page=1 kind=PREVIEW phase=native_render"))
            assertTrue("diagnostic memory must not grow with each scroll", waiting.length < 32_000)
            release.countDown()
            assertTrue("the final viewport must get a preview while still scrolling", awaitCondition {
                cachedPreview(adapter, 50) != null && adapter.loadingPreviewPagesForTest.isEmpty() &&
                    com.example.comiclab.core.diagnostics.PdfReaderDiagnostics.snapshot(RuntimeEnvironment.getApplication())!!.contains("activeTasks=0")
            })
            assertEquals("latest visible page must precede background work", entries[50], session.previewWidths[1].first)
            adapter.onReaderPaused()
            val before = com.example.comiclab.core.diagnostics.PdfReaderDiagnostics.snapshot(context)!!
            assertTrue(before.contains("activeTasks=0"))
            assertTrue(before.contains("cancelled:"))
            assertTrue(before.contains("direction=-1 fast=true"))
            val pipelineCount = Regex("pipeline:[0-9]+").find(before)!!.value
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
            val after = com.example.comiclab.core.diagnostics.PdfReaderDiagnostics.snapshot(context)!!
            assertEquals("paused readers must stop periodic snapshots", pipelineCount, Regex("pipeline:[0-9]+").find(after)!!.value)
        } finally {
            release.countDown()
            closeAndAwait(adapter, session)
            bitmaps.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test fun missingPdfPageRunsBeforeAnAlreadyVisiblePreviewQualityUpgrade() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoded = CopyOnWriteArrayList<String>()
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entry ->
            decoded.add(entry)
            if (entry == "busy.pdf") { started.countDown(); awaitUninterruptibly(release) }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmaps::add)
        })
        val adapter = newAdapter(session, listOf("upgrade.pdf", "missing.pdf", "busy.pdf"))
        try {
            val low = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmaps::add)
            MangaPageAdapter::class.java.getDeclaredMethod("putPreviewBitmap", Int::class.javaPrimitiveType,
                Bitmap::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(adapter, 0, low, 360)
            requestPreview(adapter, 2)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            adapter.ensurePreviewForTest(0, 101, 0, false)
            adapter.ensurePreviewForTest(1, 101, 0, false)
            release.countDown()
            assertTrue(awaitCondition { decoded.size == 3 && adapter.loadingPreviewPagesForTest.isEmpty() })
            assertEquals(listOf("busy.pdf", "missing.pdf", "upgrade.pdf"), decoded.toList())
        } finally {
            release.countDown()
            closeAndAwait(adapter, session)
            bitmaps.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test fun fastViewportRequestsMissingPreviewEvenWhenPrefetchWasCancelledByMemoryPressure() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            if (it == "current.pdf") started.countDown()
            awaitUninterruptibly(release)
            null
        })
        val recycler = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recycler, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, listOf("previous.pdf", "current.pdf", "next.pdf"), recyclerView = recycler)
        try {
            adapter.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            // RecyclerView can reuse an already bound prefetch holder without another onBind.
            adapter.preloadAround(1, 1, isFastScroll = true, isIdle = false)
            assertTrue("a visible page must not wait until scrolling stops", started.await(2, TimeUnit.SECONDS))
            assertTrue("even an unbound fast viewport must start with a compact first image",
                session.previewWidths.contains("current.pdf" to 360))
            assertFalse("memory pressure must still suppress background work", session.previewWidths.any { it.first == "next.pdf" })
        } finally {
            release.countDown()
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun doublePageCoverSlotDoesNotChangeSourceEntryCount() {
        val session = FakeReaderSession()
        val adapter = newAdapter(
            session = session,
            entries = listOf("cover.png", "page-1.png", "page-2.png"),
            doublePageReading = true,
            coverSinglePage = true
        )

        try {
            assertEquals(4, adapter.itemCount)
            assertEquals(3, adapter.readerItemCount())
            assertEquals(1L, adapter.getItemId(1))
        } finally {
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun horizontalPagesKeepTheCapturedViewportHeightInBothDirections() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            AppSettings.READING_DIRECTION_LEFT_TO_RIGHT,
            AppSettings.READING_DIRECTION_RIGHT_TO_LEFT
        ).forEach { direction ->
            val session = FakeReaderSession()
            val adapter = newAdapter(
                session = session,
                entries = listOf("page.png"),
                readingDirection = direction,
                archiveFileName = "reader-$direction.cbz"
            )
            val holder = createHolder(adapter, RecyclerView(context))

            try {
                bind(adapter, holder, 0)

                assertEquals(
                    "horizontal page height should use the captured reader viewport for $direction",
                    1_800,
                    holder.itemView.layoutParams.height
                )
            } finally {
                closeAndAwait(adapter, session)
            }
        }
    }

    @Test
    fun successfulPreviewIsStoredUnderItsSourceEntryPosition() {
        val decodedBitmap = AtomicReference<Bitmap>()
        val session = FakeReaderSession(previewDecoder = {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmap::set)
        })
        val adapter = newAdapter(session, listOf("page.png"))

        try {
            requestPreview(adapter, 0)

            assertTrue("decoded preview should enter the source-position cache", awaitCondition {
                val bitmap = decodedBitmap.get()
                bitmap != null && cachedPreview(adapter, 0) === bitmap
            })
        } finally {
            closeAndAwait(adapter, session)
            decodedBitmap.get()?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    fun pdfPlaceholderAndFirstBitmapBindingAreTimed() {
        val previewStarted = CountDownLatch(1)
        val releasePreview = CountDownLatch(1)
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            previewStarted.countDown()
            awaitUninterruptibly(releasePreview)
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        val adapter = newAdapter(session, listOf("page.pdf"), recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)
        ShadowLog.clear()

        try {
            bind(adapter, holder, 0)

            assertTrue("PDF placeholder binding should be logged", ShadowLog.getLogs().any {
                it.tag == "ComicLabPdfTiming" && it.msg.contains("event=page_placeholder page=1")
            })
            assertTrue("the preview decode should start", previewStarted.await(5, TimeUnit.SECONDS))

            releasePreview.countDown()
            assertTrue("the preview should be cached before the holder refreshes", awaitCondition {
                adapter.cachedPreviewForTest(0) != null
            })
            bind(adapter, holder, 0)
            assertTrue("the first bitmap binding should report placeholder duration", awaitCondition {
                ShadowLog.getLogs().any {
                    it.tag == "ComicLabPdfTiming" &&
                        it.msg.contains("event=page_placeholder_resolved page=1") &&
                        it.msg.contains("placeholderMs=")
                }
            })
            val context = RuntimeEnvironment.getApplication()
            val bound = com.example.comiclab.core.diagnostics.PdfReaderDiagnostics.snapshot(context)!!
            assertTrue(bound.contains("event=bound page=1"))
            assertTrue("bitmap binding CPU work must have its own timing", bound.contains("event=bind_cpu page=1"))
            assertTrue(bound.contains("phase=awaiting_draw"))
            assertFalse(bound.contains("event=draw page=1"))
            holder.imageView.layout(0, 0, 20, 20)
            holder.imageView.draw(android.graphics.Canvas())
            val drawn = com.example.comiclab.core.diagnostics.PdfReaderDiagnostics.snapshot(context)!!
            assertTrue("binding alone is not a draw observation", drawn.contains("event=draw page=1"))
            assertFalse(drawn.contains("phase=awaiting_draw"))
            val exported = com.example.comiclab.core.diagnostics.CrashLogManager.createLatestCrashLogExport(context)!!
            assertTrue(exported.readText().contains("event=draw page=1"))
        } finally {
            releasePreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun uncachedVisiblePdfPageShowsFirstStagePreviewBeforeScreenWidthUpgrade() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val screenWidthPreviewStarted = CountDownLatch(1)
        val previewCalls = AtomicInteger()
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            when (previewCalls.getAndIncrement()) {
                0 -> {
                    firstPreviewStarted.countDown()
                    awaitUninterruptibly(releaseFirstPreview)
                }
                1 -> screenWidthPreviewStarted.countDown()
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, listOf("page.pdf"), recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the first visible preview should start", firstPreviewStarted.await(5, TimeUnit.SECONDS))
            assertEquals("first show a quick preview before the full-width render", 720, session.previewWidths.first().second)

            releaseFirstPreview.countDown()
            assertTrue("the first preview should remain available during movement", awaitCondition {
                adapter.cachedPreviewForTest(0) != null
            })
            assertEquals(1L, screenWidthPreviewStarted.count)
            setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_IDLE)
            adapter.preloadAround(0, 1, isIdle = true)
            assertTrue("the screen-width upgrade should follow the first preview", awaitCondition {
                screenWidthPreviewStarted.count == 0L && session.previewWidths.contains("page.pdf" to 1_080)
            })
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun failedQuickPdfPreviewRetriesAtScreenWidth() {
        val previewCalls = AtomicInteger()
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            if (previewCalls.incrementAndGet() == 1) {
                null
            } else {
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
            }
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, listOf("page.pdf"), recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the quick preview failure should retry once at screen width", awaitCondition {
                previewCalls.get() == 2 && adapter.cachedPreviewForTest(0) != null &&
                    session.previewWidths.contains("page.pdf" to 1_080)
            })
        } finally {
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun missingAndCorruptPreviewsAreRecordedWithoutStoppingOtherDecodeTasks() {
        val requests = CountDownLatch(2)
        val session = FakeReaderSession(previewDecoder = { entryName ->
            requests.countDown()
            when (entryName) {
                "missing.png" -> null
                "corrupt.png" -> error("corrupt image")
                else -> error("unexpected entry: $entryName")
            }
        })
        val adapter = newAdapter(session, listOf("missing.png", "corrupt.png"))

        try {
            requestPreview(adapter, 0)
            requestPreview(adapter, 1)

            assertTrue("both entries should reach the fake reader session", requests.await(5, TimeUnit.SECONDS))
            assertTrue("failed previews should be recorded and loading state cleared", awaitCondition {
                adapter.failedPreviewPagesForTest.containsAll(listOf(0, 1)) &&
                    adapter.loadingPreviewPagesForTest.isEmpty()
            })
        } finally {
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun recycledHolderDoesNotShowPreviewThatFinishesForItsPreviousPage() {
        val decodeStarted = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val oldPageBitmap = AtomicReference<Bitmap>()
        val session = FakeReaderSession(previewDecoder = { entryName ->
            if (entryName == "old-page.png") {
                decodeStarted.countDown()
                awaitUninterruptibly(releaseDecode)
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(oldPageBitmap::set)
            } else {
                null
            }
        })
        val adapter = newAdapter(session, listOf("old-page.png", "current-page.png"))
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the old page decode should start", decodeStarted.await(5, TimeUnit.SECONDS))

            recycle(adapter, holder)
            bind(adapter, holder, 1)
            releaseDecode.countDown()

            assertTrue("late decode should finish and clear loading state", awaitCondition {
                adapter.loadingPreviewPagesForTest.isEmpty()
            })
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val imageView = holder.imageView
            val displayedBitmap = (imageView.drawable as? BitmapDrawable)?.bitmap
            assertNull("the current page has no preview in this fixture", displayedBitmap)
        } finally {
            releaseDecode.countDown()
            closeAndAwait(adapter, session)
            oldPageBitmap.get()?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    fun rebindingDuringFastPdfScrollDropsQueuedPreviewsForPagesAlreadyPassed() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val decodedEntries = CopyOnWriteArrayList<String>()
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..5).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            decodedEntries.add(entryName)
            if (entryName == entries.first()) {
                firstPreviewStarted.countDown()
                awaitUninterruptibly(releaseFirstPreview)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the first PDF preview should start", firstPreviewStarted.await(5, TimeUnit.SECONDS))

            entries.indices.drop(1).forEach { position -> bind(adapter, holder, position) }
            releaseFirstPreview.countDown()

            assertTrue("the final visible page preview should complete", awaitCondition {
                decodedEntries.contains(entries.last()) && adapter.loadingPreviewPagesForTest.isEmpty()
            })
            assertEquals(
                "queued preview work for pages passed during a fast scroll should be discarded",
                listOf(entries.first(), entries.last()),
                decodedEntries.toList()
            )
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun fastPdfScrollDropsQueuedVisiblePreviewsOutsideTheCurrentWindow() {
        val firstPreviewStarted = CountDownLatch(1)
        val currentPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val decodedEntries = CopyOnWriteArrayList<String>()
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..5).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            decodedEntries.add(entryName)
            when (entryName) {
                entries.first() -> {
                    firstPreviewStarted.countDown()
                    awaitUninterruptibly(releaseFirstPreview)
                }

                entries.last() -> currentPreviewStarted.countDown()
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)

        try {
            entries.indices.forEach { entryPosition ->
                bind(adapter, createHolder(adapter, recyclerView), entryPosition)
                if (entryPosition == 0) {
                    assertTrue(
                        "the first PDF preview should start",
                        firstPreviewStarted.await(5, TimeUnit.SECONDS)
                    )
                }
            }
            adapter.preloadAround(
                firstVisiblePosition = entries.lastIndex,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = true,
                isIdle = false
            )
            releaseFirstPreview.countDown()

            assertTrue(
                "the current PDF page should not wait for queued pages outside its window",
                currentPreviewStarted.await(5, TimeUnit.SECONDS)
            )
            assertEquals(entries.first(), decodedEntries[0])
            assertEquals(entries.last(), decodedEntries[1])
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun pdfPreviewPreloadsSurviveGenerationChangesWhileStillInsideWindow() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val decodedEntries = CopyOnWriteArrayList<String>()
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..8).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            decodedEntries.add(entryName)
            if (entryName == entries.first()) {
                firstPreviewStarted.countDown()
                awaitUninterruptibly(releaseFirstPreview)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the first visible preview should start", firstPreviewStarted.await(5, TimeUnit.SECONDS))

            adapter.preloadAround(
                firstVisiblePosition = 1,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = true,
                isIdle = false
            )
            val firstGenerationTasks = adapter.decodeExecutorForTest.queue.toList()
            assertTrue("the first fast-scroll window should queue future previews", firstGenerationTasks.size >= 4)

            adapter.preloadAround(
                firstVisiblePosition = 2,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = true,
                isIdle = false
            )
            bind(adapter, holder, 2)
            val secondGenerationTasks = adapter.decodeExecutorForTest.queue.toList()
            val retainedTasks = firstGenerationTasks.count { previous ->
                secondGenerationTasks.any { current -> current === previous }
            }
            assertTrue(
                "queued PDF previews still inside the rolling window should not be discarded",
                retainedTasks >= 3
            )

            releaseFirstPreview.countDown()
            assertTrue("the visible page and useful prefetched pages should finish", awaitCondition {
                decodedEntries.contains(entries[2]) && decodedEntries.contains(entries[6]) &&
                    adapter.loadingPreviewPagesForTest.isEmpty()
            })
            assertEquals("the newly visible page should run before preloads", entries[2], decodedEntries[1])
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun preloadQueueSnapshotDoesNotRaceWhenWorkerRemovesLastQueuedTask() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = listOf("page-0.pdf", "page-1.pdf")
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            if (entryName == entries[0]) {
                firstPreviewStarted.countDown()
                awaitUninterruptibly(releaseFirstPreview)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val adapter = newAdapter(session, entries)
        adapter.decodeExecutorForTest.shutdownNow()
        val queue = DrainOnNextIteratorQueue()
        adapter.decodeExecutorForTest = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            queue
        )

        try {
            adapter.requestPreviewForTest(0)
            assertTrue("the first preview should hold the worker", firstPreviewStarted.await(5, TimeUnit.SECONDS))
            adapter.ensurePreviewForTest(1, priority = 30, generation = 0, isPreload = true)
            assertEquals("one task should remain queued behind the active decode", 1, queue.size)
            queue.drainOneItemOnNextIterator = true

            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = true,
                isIdle = false
            )

            assertTrue("the queue should drain at the snapshot boundary", queue.drainedOneItem)
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun queuedPdfPreviewUsesTheCurrentScrollModeWhenDecodingStarts() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..40).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            if (entryName == entries[0]) {
                firstPreviewStarted.countDown()
                awaitUninterruptibly(releaseFirstPreview)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the first PDF preview should start", firstPreviewStarted.await(5, TimeUnit.SECONDS))
            assertTrue("the initial PDF preview should decode", awaitCondition {
                session.previewWidths.contains(entries[0] to 720)
            })

            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = false,
                isIdle = false
            )
            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = true,
                isIdle = false
            )
            releaseFirstPreview.countDown()
            assertTrue("a queued PDF preview should use the current compact target width", awaitCondition {
                session.previewWidths.contains(entries[1] to 360)
            })

            setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_IDLE)
            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = false,
                isIdle = true
            )
            bind(adapter, holder, 40)
            assertTrue("idle reading should restore the regular PDF preview width", awaitCondition {
                session.previewWidths.contains(entries[40] to 1_080)
            })
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun idlePdfPreloadsUseCompactWidthButVisiblePreviewKeepsRegularWidth() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..20).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            if (entryName == entries[0]) {
                firstPreviewStarted.countDown()
                awaitUninterruptibly(releaseFirstPreview)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the active PDF preview should block the worker", firstPreviewStarted.await(5, TimeUnit.SECONDS))
            setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_IDLE)
            adapter.preloadAround(0, 1, 1, isFastScroll = false, isIdle = true)
            bind(adapter, createHolder(adapter, recyclerView), 20)

            releaseFirstPreview.countDown()
            assertTrue("the next page should be prefetched at screen width while distant pages stay compact", awaitCondition {
                session.previewWidths.contains(entries[1] to 1_080) &&
                    session.previewWidths.contains(entries[2] to 360) &&
                    session.previewWidths.contains(entries[20] to 1_080)
            })
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun idlePdfKeepsTheAdjacentScreenWidthPreviewAfterDistantPreloads() {
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..30).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            val position = entryName.removePrefix("page-").removeSuffix(".pdf").toInt()
            val (width, height) = if (position == 0 || position == 1) {
                1_080 to 1_400
            } else {
                360 to 466
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)

        try {
            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = false,
                isIdle = true
            )

            assertTrue("idle PDF previews should drain", awaitCondition {
                adapter.loadingPreviewPagesForTest.isEmpty() &&
                    session.previewWidths.contains(entries[1] to 1_080)
            })
            assertEquals(
                "the next page's screen-width preview must survive the distant compact preloads",
                1_080,
                adapter.cachedPreviewForTest(1)?.width
            )
        } finally {
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun idlePdfPreloadUpgradesAnInFlightCompactAdjacentPreview() {
        val compactPreviewStarted = CountDownLatch(1)
        val releaseCompactPreview = CountDownLatch(1)
        val pageOnePreviewCalls = AtomicInteger(0)
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..20).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            if (entryName == entries[1] && pageOnePreviewCalls.getAndIncrement() == 0) {
                compactPreviewStarted.countDown()
                awaitUninterruptibly(releaseCompactPreview)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)

        try {
            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = true,
                isIdle = false
            )
            assertTrue("the adjacent compact preview should start", compactPreviewStarted.await(5, TimeUnit.SECONDS))

            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = false,
                isIdle = true
            )
            releaseCompactPreview.countDown()

            assertTrue("the adjacent preview must be upgraded even if its compact decode was already running", awaitCondition {
                session.previewWidths.contains(entries[1] to 1_080)
            })
        } finally {
            releaseCompactPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun visiblePdfPageKeepsInFlightCompactPreviewUntilFastScrollingSettles() {
        val compactPreviewStarted = CountDownLatch(1)
        val releaseCompactPreview = CountDownLatch(1)
        val pageOnePreviewCalls = AtomicInteger(0)
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..20).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            if (entryName == entries[1] && pageOnePreviewCalls.getAndIncrement() == 0) {
                compactPreviewStarted.countDown()
                awaitUninterruptibly(releaseCompactPreview)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)

        try {
            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = true,
                isIdle = false
            )
            assertTrue("the nearby fast-scroll preview should start compact", compactPreviewStarted.await(5, TimeUnit.SECONDS))

            bind(adapter, holder, 1)
            releaseCompactPreview.countDown()
            assertTrue("the compact preview should finish", awaitCondition {
                adapter.cachedPreviewForTest(1) != null
            })
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
            assertFalse("a moving page must retain its compact preview instead of competing for first images",
                session.previewWidths.contains(entries[1] to 1_080))
            setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_IDLE)
            adapter.preloadAround(1, 1, isIdle = true)
            assertTrue("screen quality must resume after scrolling settles", awaitCondition {
                session.previewWidths.contains(entries[1] to 1_080)
            })
        } finally {
            releaseCompactPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun activePdfPreviewIsNotRenderedAgainWhenASecondWorkerRequestsTheSamePage() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val duplicatePreviewStarted = CountDownLatch(1)
        val previewCalls = AtomicInteger(0)
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            if (previewCalls.incrementAndGet() == 1) {
                firstPreviewStarted.countDown()
                awaitUninterruptibly(releaseFirstPreview)
            } else {
                duplicatePreviewStarted.countDown()
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val adapter = newAdapter(session, listOf("page.pdf"))
        adapter.decodeExecutorForTest.shutdownNow()
        adapter.decodeExecutorForTest = ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            PriorityBlockingQueue<Runnable>()
        )

        try {
            adapter.requestPreviewForTest(0)
            assertTrue("the first preview should begin", firstPreviewStarted.await(5, TimeUnit.SECONDS))
            adapter.requestPreviewForTest(0)
            assertFalse(
                "a second worker must not render a page that is already being decoded",
                duplicatePreviewStarted.await(150, TimeUnit.MILLISECONDS)
            )

            releaseFirstPreview.countDown()
            assertTrue("the active preview should finish", awaitCondition {
                previewCalls.get() == 1 && cachedPreview(adapter, 0) != null
            })
            assertEquals(1, previewCalls.get())
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun queuedFastPdfPreviewsArePromotedAsTheyApproachTheViewport() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val decodeOrder = CopyOnWriteArrayList<String>()
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..40).map { "page-$it.pdf" }
        val session = FakeReaderSession(pdfSource = true, previewDecoder = { entryName ->
            if (entryName == entries[0]) {
                firstPreviewStarted.countDown()
                awaitUninterruptibly(releaseFirstPreview)
            } else {
                decodeOrder.add(entryName)
            }
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmaps::add)
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        val adapter = newAdapter(session, entries, recyclerView = recyclerView)
        adapter.decodeExecutorForTest.shutdownNow()
        adapter.decodeExecutorForTest = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            PriorityBlockingQueue<Runnable>()
        )
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the active preview should block the single worker", firstPreviewStarted.await(5, TimeUnit.SECONDS))
            adapter.preloadAround(0, 1, 1, isFastScroll = true, isIdle = false)
            adapter.preloadAround(4, 1, 1, isFastScroll = true, isIdle = false)
            adapter.ensurePreviewForTest(4, priority = 90, generation = 0, isPreload = false)

            releaseFirstPreview.countDown()
            assertTrue("both approaching-page previews should finish", awaitCondition {
                entries[15] in decodeOrder && entries[17] in decodeOrder
            })
            assertTrue(
                "the preview that moved closer should be promoted ahead of the next distant page",
                decodeOrder.indexOf(entries[15]) < decodeOrder.indexOf(entries[17])
            )
        } finally {
            releaseFirstPreview.countDown()
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun rapidPdfScrollCancelsDeferredIdleFullDecodeUntilViewportSettles() {
        val previewStarted = CountDownLatch(1)
        val releasePreview = CountDownLatch(1)
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            previewStarted.countDown()
            awaitUninterruptibly(releasePreview)
            null
        })
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_IDLE)
        val adapter = newAdapter(session, listOf("page.pdf"), recyclerView = recyclerView)
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the visible PDF preview should start", previewStarted.await(5, TimeUnit.SECONDS))
            assertTrue("PDF full decode should wait for a stable idle window", adapter.loadingFullPagesForTest.isEmpty())
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(40, TimeUnit.MILLISECONDS)
            assertTrue("PDF full decode should remain deferred", adapter.loadingFullPagesForTest.isEmpty())

            setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                isFastScroll = true,
                isIdle = false
            )
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertTrue("starting a scroll should cancel the pending full decode", session.boundsReads.isEmpty())

            setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_IDLE)
            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                isIdle = true
            )
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(40, TimeUnit.MILLISECONDS)
            assertTrue("full decode should remain deferred during the idle debounce", adapter.loadingFullPagesForTest.isEmpty())
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(80, TimeUnit.MILLISECONDS)
            assertTrue("full decode should resume after the viewport settles", adapter.loadingFullPagesForTest.contains(0))
            releasePreview.countDown()
            assertTrue("the settled full decode should reach the reader session", awaitCondition {
                session.boundsReads.contains("page.pdf")
            })
        } finally {
            releasePreview.countDown()
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun closeWaitsForInflightPreviewAndDiscardsItsLateBitmap() {
        val decodeStarted = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val lateBitmap = AtomicReference<Bitmap>()
        val session = FakeReaderSession(previewDecoder = {
            decodeStarted.countDown()
            awaitUninterruptibly(releaseDecode)
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(lateBitmap::set)
        })
        val adapter = newAdapter(session, listOf("slow.png"))

        try {
            requestPreview(adapter, 0)
            assertTrue("preview decode should start", decodeStarted.await(5, TimeUnit.SECONDS))

            adapter.close()
            adapter.close()
            assertFalse("the reader session must remain open while decode is active", session.closed.await(50, TimeUnit.MILLISECONDS))

            releaseDecode.countDown()
            assertTrue("the session should close after decode stops", session.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1, session.closeCount.get())
            assertTrue("the late bitmap must be discarded after close", lateBitmap.get()?.isRecycled == true)
        } finally {
            releaseDecode.countDown()
            closeAndAwait(adapter, session)
            lateBitmap.get()?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    fun leavingPdfPageRemovesItsQueuedVisibleFullDecode() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            started.countDown()
            awaitUninterruptibly(release)
            null
        })
        val adapter = newAdapter(session, listOf("old.pdf", "current.pdf"))
        try {
            requestPreview(adapter, 0)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            adapter.ensureFullOrTiledPageForTest(0, 0, 0, false)
            assertTrue(adapter.loadingFullPagesForTest.contains(0))
            adapter.preloadAround(1, 1)
            assertFalse("an old full render must not delay the new page", adapter.loadingFullPagesForTest.contains(0))
            requestPreview(adapter, 1)
            release.countDown()
            assertTrue(awaitCondition { session.previewWidths.any { it.first == "current.pdf" } })
            assertFalse(session.boundsReads.contains("old.pdf"))
        } finally {
            release.countDown()
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun hidingPdfUiKeepsReadyPreviewAndDoesNotDisablePreloads() {
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        val session = FakeReaderSession(pdfSource = true, previewDecoder = {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmaps::add)
        })
        val adapter = newAdapter(session, listOf("visible.pdf", "next.pdf"))
        try {
            requestPreview(adapter, 0)
            assertTrue(awaitCondition { cachedPreview(adapter, 0) != null })
            val ready = cachedPreview(adapter, 0)
            adapter.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertSame("returning from background must retain the ready page", ready, cachedPreview(adapter, 0))
            adapter.preloadAround(0, 1, isIdle = true)
            assertTrue("UI hidden must not permanently stop adjacent preloads", awaitCondition {
                session.previewWidths.any { it.first == "next.pdf" }
            })
        } finally {
            closeAndAwait(adapter, session)
            bitmaps.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun returningToReaderRestoresPreloadsAfterActualMemoryPressure() {
        val session = FakeReaderSession(pdfSource = true)
        val adapter = newAdapter(session, listOf("visible.pdf", "next.pdf"))
        try {
            adapter.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            adapter.preloadAround(0, 1)
            assertTrue(session.previewWidths.isEmpty())
            adapter.onReaderResumed()
            adapter.preloadAround(0, 1)
            assertTrue(awaitCondition { session.previewWidths.any { it.first == "next.pdf" } })
        } finally {
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun activeOldPdfFullRequestStopsBeforeRenderingAfterBoundsReturn() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeReaderSession(pdfSource = true, boundsReader = {
            started.countDown()
            awaitUninterruptibly(release)
            ComicArchive.ImageBounds(1000, 1400)
        })
        val adapter = newAdapter(session, listOf("old.pdf", "new.pdf"))
        try {
            adapter.ensureFullOrTiledPageForTest(0, 0, 0, false)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            adapter.ensureFullOrTiledPageForTest(0, 0, 0, false)
            assertTrue("an active PDF full render must not be enqueued twice", adapter.decodeExecutorForTest.queue.isEmpty())
            adapter.preloadAround(1, 1)
            release.countDown()
            assertTrue(awaitCondition { !adapter.loadingFullPagesForTest.contains(0) })
            assertTrue("an obsolete request must not enter native full rendering", session.fullReads.isEmpty())
        } finally {
            release.countDown()
            closeAndAwait(adapter, session)
        }
    }

    private fun newAdapter(
        session: FakeReaderSession,
        entries: List<String>,
        doublePageReading: Boolean = false,
        coverSinglePage: Boolean = false,
        readingDirection: String = AppSettings.READING_DIRECTION_LEFT_TO_RIGHT,
        archiveFileName: String = "reader-test.cbz",
        recyclerView: RecyclerView = RecyclerView(RuntimeEnvironment.getApplication()),
    ): MangaPageAdapter {
        val context = RuntimeEnvironment.getApplication()
        return MangaPageAdapter(
            context,
            recyclerView,
            temporaryFolder.newFile(archiveFileName),
            session,
            entries,
            2_400,
            1_800,
            7_200,
            5_400,
            readingDirection,
            doublePageReading,
            coverSinglePage,
            {},
            {}
        )
    }

    private fun requestPreview(adapter: MangaPageAdapter, position: Int) {
        adapter.requestPreviewForTest(position)
    }

    private fun cachedPreview(adapter: MangaPageAdapter, position: Int): Bitmap? {
        return adapter.cachedPreviewForTest(position)
    }

    private fun createHolder(adapter: MangaPageAdapter, parent: ViewGroup): MangaPageAdapter.PageViewHolder {
        return adapter.onCreateViewHolder(parent, 0)
    }

    private fun bind(adapter: MangaPageAdapter, holder: MangaPageAdapter.PageViewHolder, position: Int) {
        adapter.onBindViewHolder(holder, position)
    }

    private fun recycle(adapter: MangaPageAdapter, holder: MangaPageAdapter.PageViewHolder) {
        adapter.onViewRecycled(holder)
    }

    private fun setScrollStateForTest(recyclerView: RecyclerView, scrollState: Int) {
        RecyclerView::class.java.getDeclaredField("mScrollState").apply {
            isAccessible = true
            setInt(recyclerView, scrollState)
        }
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Keep the fake decode active to exercise the adapter's shutdown drain.
            }
        }
    }

    private fun closeAndAwait(adapter: MangaPageAdapter, session: FakeReaderSession) {
        adapter.close()
        assertTrue("reader session should close", session.closed.await(5, TimeUnit.SECONDS))
        assertEquals(1, session.closeCount.get())
    }

    private class DrainOnNextIteratorQueue : PriorityBlockingQueue<Runnable>() {
        @Volatile
        var drainOneItemOnNextIterator = false
        @Volatile
        var drainedOneItem = false

        override fun iterator(): MutableIterator<Runnable> {
            if (drainOneItemOnNextIterator) {
                drainOneItemOnNextIterator = false
                drainedOneItem = poll() != null
            }
            return super.iterator()
        }
    }

    private class FakeReaderSession(
        private val pdfSource: Boolean = false,
        private val previewDecoder: (String) -> Bitmap? = { null },
        private val boundsReader: (String) -> ComicArchive.ImageBounds? = { null }
    ) : ComicArchive.ImageReaderSession {
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        val boundsReads = CopyOnWriteArrayList<String>()
        val previewWidths = CopyOnWriteArrayList<Pair<String, Int>>()
        val fullReads = CopyOnWriteArrayList<String>()

        override fun isPdfSource(): Boolean = pdfSource

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? {
            boundsReads.add(entryName)
            return boundsReader(entryName)
        }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            previewWidths.add(entryName to targetWidth)
            return previewDecoder(entryName)
        }

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? {
            fullReads.add(entryName)
            return null
        }

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? {
            fullReads.add(entryName)
            return null
        }

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? = null

        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }
}
