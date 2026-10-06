package com.example.comiclab.ui.reader.manga

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.ComicArchive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    private class FakePreviewSession : ComicArchive.ImageReaderSession {
        val closeCount = AtomicInteger()
        val closeCompleted = CountDownLatch(1)

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? = null

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? = null

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
