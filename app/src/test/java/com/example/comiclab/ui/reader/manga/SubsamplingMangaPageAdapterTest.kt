package com.example.comiclab.ui.reader.manga

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.AppSettings
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubsamplingMangaPageAdapterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failedExtractionRemainsVisibleAndDoesNotRestartOnBindOrPreload() {
        val context = RuntimeEnvironment.getApplication()
        val cache = temporaryFolder.newFolder("failed-page")
        val lease = checkNotNull(PreparedReaderCacheLeaseRegistry().acquireUsageLease(cache))
        val recycler = RecyclerView(context)
        val adapter = SubsamplingMangaPageAdapter(
            context = context,
            recyclerView = recycler,
            archiveFile = temporaryFolder.newFile("invalid.cbz"),
            entryNames = listOf("page.png"),
            pageFiles = listOf(File(cache, "missing.png")),
            boundsByPosition = emptyMap(),
            preparedCacheDir = cache,
            cacheUseLease = lease,
            baseDisplayWidth = 400,
            baseDisplayHeight = 600,
            readingDirection = AppSettings.READING_DIRECTION_TOP_TO_BOTTOM,
            doublePageReading = false,
            coverSinglePage = false,
            onPageReady = {},
            onPageTap = {}
        )
        val executor = adapter.extractExecutorForTest
        val holder = adapter.onCreateViewHolder(recycler, 0)
        try {
            adapter.onBindViewHolder(holder, 0)
            assertTrue(awaitCondition { executor.completedTaskCount == 1L })
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            repeat(3) {
                adapter.onBindViewHolder(holder, 0)
                adapter.preloadAround(0, 1, 1, false, true, false)
            }
            assertEquals(View.VISIBLE, holder.statusText.visibility)
            assertTrue(holder.statusText.text.isNotBlank())
            assertEquals(1L, executor.taskCount)
        } finally {
            adapter.close()
            assertTrue(awaitCondition { lease.isClosed })
        }
    }

    @Test
    fun doublePageCoverSlotDoesNotChangePreparedSourceEntryCount() {
        val context = RuntimeEnvironment.getApplication()
        val preparedCacheDir = temporaryFolder.newFolder("prepared-reader")
        val cacheUseLease = checkNotNull(
            PreparedReaderCacheLeaseRegistry().acquireUsageLease(preparedCacheDir)
        )
        val entries = listOf("page1.png", "page2.png", "page3.png")
        val adapter = SubsamplingMangaPageAdapter(
            context = context,
            recyclerView = RecyclerView(context),
            archiveFile = temporaryFolder.newFile("source.cbz"),
            entryNames = entries,
            pageFiles = entries.indices.map { File(preparedCacheDir, "page$it.png") },
            boundsByPosition = emptyMap(),
            preparedCacheDir = preparedCacheDir,
            cacheUseLease = cacheUseLease,
            baseDisplayWidth = 1080,
            baseDisplayHeight = 1920,
            readingDirection = AppSettings.READING_DIRECTION_LEFT_TO_RIGHT,
            doublePageReading = true,
            coverSinglePage = true,
            onPageReady = {},
            onPageTap = {}
        )

        try {
            assertEquals(4, adapter.itemCount)
            assertEquals(3, adapter.readerItemCount())
            assertEquals(3L, adapter.getItemId(3))
        } finally {
            adapter.close()
            assertTrue("adapter close should release its cache lease", awaitCondition {
                cacheUseLease.isClosed
            })
        }
    }

    @Test
    fun horizontalPreparedPageKeepsTheCapturedViewportHeight() {
        val context = RuntimeEnvironment.getApplication()
        val preparedCacheDir = temporaryFolder.newFolder("prepared-horizontal-reader")
        val cacheUseLease = checkNotNull(
            PreparedReaderCacheLeaseRegistry().acquireUsageLease(preparedCacheDir)
        )
        val adapter = SubsamplingMangaPageAdapter(
            context = context,
            recyclerView = RecyclerView(context),
            archiveFile = temporaryFolder.newFile("source-horizontal.cbz"),
            entryNames = listOf("page.png"),
            pageFiles = listOf(File(preparedCacheDir, "page.png")),
            boundsByPosition = emptyMap(),
            preparedCacheDir = preparedCacheDir,
            cacheUseLease = cacheUseLease,
            baseDisplayWidth = 1080,
            baseDisplayHeight = 1920,
            readingDirection = AppSettings.READING_DIRECTION_LEFT_TO_RIGHT,
            doublePageReading = false,
            coverSinglePage = false,
            onPageReady = {},
            onPageTap = {}
        )
        val container = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
        }

        try {
            adapter.applyPageLayoutForTest(container, null)

            assertEquals(1920, container.layoutParams.height)
        } finally {
            adapter.close()
            assertTrue("adapter close should release its cache lease", awaitCondition {
                cacheUseLease.isClosed
            })
        }
    }

    private fun awaitCondition(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.yield()
        }
        return condition()
    }
}
