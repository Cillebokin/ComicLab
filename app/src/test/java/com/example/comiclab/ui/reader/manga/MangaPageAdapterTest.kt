package com.example.comiclab.ui.reader.manga

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaPageAdapterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
    fun fastPdfScrollUsesCompactPreviewAndIdleRestoresRegularPreviewWidth() {
        val firstPreviewStarted = CountDownLatch(1)
        val releaseFirstPreview = CountDownLatch(1)
        val decodedBitmaps = CopyOnWriteArrayList<Bitmap>()
        val entries = (0..8).map { "page-$it.pdf" }
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
                session.previewWidths.contains(entries[0] to 1_080)
            })

            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = true,
                isIdle = false
            )
            setScrollStateForTest(recyclerView, RecyclerView.SCROLL_STATE_IDLE)
            adapter.preloadAround(
                firstVisiblePosition = 0,
                visibleItemCount = 1,
                scrollDirection = 1,
                isFastScroll = false,
                isIdle = true
            )
            releaseFirstPreview.countDown()
            assertTrue("queued fast-scroll previews should retain their compact target width", awaitCondition {
                session.previewWidths.contains(entries[1] to 180)
            })

            bind(adapter, holder, 8)
            assertTrue("idle reading should restore the regular PDF preview width", awaitCondition {
                session.previewWidths.contains(entries[8] to 1_080)
            })
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
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
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
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            assertTrue("full decode should remain deferred during the idle debounce", adapter.loadingFullPagesForTest.isEmpty())
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
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

    private class FakeReaderSession(
        private val pdfSource: Boolean = false,
        private val previewDecoder: (String) -> Bitmap? = { null }
    ) : ComicArchive.ImageReaderSession {
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        val boundsReads = CopyOnWriteArrayList<String>()
        val previewWidths = CopyOnWriteArrayList<Pair<String, Int>>()

        override fun isPdfSource(): Boolean = pdfSource

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? {
            boundsReads.add(entryName)
            return null
        }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            previewWidths.add(entryName to targetWidth)
            return previewDecoder(entryName)
        }

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? = null

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? = null

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? = null

        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }
}
