package com.example.comiclab.ui.reader.manga

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.ComicArchive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
class ReaderPreviewAdapterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun previewItemDisplaysPageNumberAndClickReportsBoundPosition() {
        val session = FakePreviewSession()
        val clickedPositions = mutableListOf<Int>()
        val adapter = newAdapter(session, listOf("first.png", "second.png"), clickedPositions::add)
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        val holder = adapter.onCreateViewHolder(recyclerView, 0)

        try {
            adapter.onBindViewHolder(holder, 1)

            assertEquals("2", holder.tvPage.text.toString())
            assertFalse(holder.itemView.isSelected)
            assertTrue(holder.itemView.performClick())
            assertEquals(listOf(1), clickedPositions)

            adapter.setSelectedPosition(1)
            adapter.onBindViewHolder(holder, 1)
            assertTrue(holder.itemView.isSelected)
        } finally {
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun rebindingHolderReplacesPreviousPageClickListener() {
        val session = FakePreviewSession()
        val clickedPositions = mutableListOf<Int>()
        val adapter = newAdapter(session, listOf("first.png", "second.png"), clickedPositions::add)
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        val holder = adapter.onCreateViewHolder(recyclerView, 0)

        try {
            adapter.onBindViewHolder(holder, 0)
            adapter.onBindViewHolder(holder, 1)

            assertEquals("2", holder.tvPage.text.toString())
            assertTrue(holder.itemView.performClick())
            assertEquals("a recycled holder must route only to its current page", listOf(1), clickedPositions)
        } finally {
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun transientThumbnailDecodeFailureIsRetriedOnceAndDisplayed() {
        val decodeCalls = AtomicInteger()
        val decodedBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val session = FakePreviewSession { _, _ ->
            if (decodeCalls.incrementAndGet() == 1) null else decodedBitmap
        }
        val adapter = newAdapter(session, listOf("page.png")) {}
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        val holder = adapter.onCreateViewHolder(recyclerView, 0)

        try {
            adapter.onBindViewHolder(holder, 0)

            val displayedBitmap = awaitDisplayedBitmap(adapter, holder, 0)

            assertSame("the one retry should render the recovered thumbnail", decodedBitmap, displayedBitmap)
            assertEquals("a transient failure gets only one retry", 2, decodeCalls.get())
        } finally {
            closeAndAwait(adapter, session)
            decodedBitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun trimmingMemoryNotifiesRowsAndAllowsCachedThumbnailToDecodeAgain() {
        val decodeCalls = AtomicInteger()
        val secondDecodeStarted = CountDownLatch(1)
        val decodedBitmaps = mutableListOf<Bitmap>()
        val session = FakePreviewSession { _, _ ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            decodedBitmaps += bitmap
            if (decodeCalls.incrementAndGet() == 2) {
                secondDecodeStarted.countDown()
            }
            bitmap
        }
        val adapter = newAdapter(session, listOf("page.png")) {}
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        val holder = adapter.onCreateViewHolder(recyclerView, 0)
        var dataSetChanged = false
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                dataSetChanged = true
            }
        })

        try {
            adapter.onBindViewHolder(holder, 0)
            assertNotNull("the first thumbnail should load", awaitDisplayedBitmap(adapter, holder, 0))

            adapter.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

            assertTrue("trim should request a visible-row rebind", dataSetChanged)
            assertNotNull(
                "the thumbnail should decode again after the cache is cleared",
                awaitDisplayedBitmap(adapter, holder, 0)
            )
            assertTrue("a fresh decode should follow memory trimming", secondDecodeStarted.await(5, TimeUnit.SECONDS))
            assertEquals(2, decodeCalls.get())
        } finally {
            closeAndAwait(adapter, session)
            decodedBitmaps.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    @Test
    fun repeatedThumbnailDecodeFailureStopsAfterOneRetry() {
        val decodeCalls = AtomicInteger()
        val session = FakePreviewSession { _, _ ->
            decodeCalls.incrementAndGet()
            null
        }
        val adapter = newAdapter(session, listOf("broken.png")) {}
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        val holder = adapter.onCreateViewHolder(recyclerView, 0)

        try {
            adapter.onBindViewHolder(holder, 0)

            assertTrue("the initial attempt and its single retry should complete", awaitCondition {
                failedPositions(adapter).contains(0)
            })
            adapter.onBindViewHolder(holder, 0)

            assertEquals("a persistently bad image must not retry without a bound", 2, decodeCalls.get())
        } finally {
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun closingPreviewAdapterClosesItsSessionExactlyOnce() {
        val session = FakePreviewSession()
        val adapter = newAdapter(session, listOf("page.png")) {}

        adapter.close()
        adapter.close()

        assertTrue("close should wait for the adapter decoder to stop", session.closeCompleted.await(5, TimeUnit.SECONDS))
        assertEquals(1, session.closeCount.get())
    }

    private fun newAdapter(
        session: FakePreviewSession,
        entries: List<String>,
        onPageClick: (Int) -> Unit
    ): ReaderPreviewAdapter = ReaderPreviewAdapter(
        context = RuntimeEnvironment.getApplication(),
        archiveFile = temporaryFolder.newFile("preview.cbz"),
        entries = entries,
        sharedSession = session,
        onPageClick = onPageClick
    )

    private fun closeAndAwait(adapter: ReaderPreviewAdapter, session: FakePreviewSession) {
        adapter.close()
        assertTrue("the reader session should close after adapter shutdown", session.closeCompleted.await(5, TimeUnit.SECONDS))
        assertEquals(1, session.closeCount.get())
    }

    private fun awaitDisplayedBitmap(
        adapter: ReaderPreviewAdapter,
        holder: ReaderPreviewAdapter.PreviewViewHolder,
        position: Int
    ): Bitmap? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            adapter.onBindViewHolder(holder, position)
            val bitmap = (holder.imgPreview.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                return bitmap
            }
            Thread.sleep(5)
        }
        return (holder.imgPreview.drawable as? BitmapDrawable)?.bitmap
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun failedPositions(adapter: ReaderPreviewAdapter): Set<*> = adapter.failedPositionsForTest

    private class FakePreviewSession(
        private val previewDecoder: (String, Int) -> Bitmap? = { _, _ -> null }
    ) : ComicArchive.ImageReaderSession {
        val closeCount = AtomicInteger()
        val closeCompleted = CountDownLatch(1)

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? = null

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? =
            previewDecoder(entryName, targetWidth)

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
            closeCompleted.countDown()
        }
    }
}
