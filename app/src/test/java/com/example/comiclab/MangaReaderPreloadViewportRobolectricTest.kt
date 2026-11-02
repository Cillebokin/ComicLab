package com.example.comiclab

import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.ui.reader.manga.ReaderPageAdapterController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class MangaReaderPreloadViewportRobolectricTest {
    @Test fun aNewVisiblePageAndScrollModeReachTheAdapterEvenIfTheFirstPageHasNotChanged() {
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java).create()
        val calls = mutableListOf<List<Any>>()
        val adapter = object : ReaderPageAdapterController {
            override fun readerItemCount() = 100
            override fun preloadAround(firstVisiblePosition: Int, visibleItemCount: Int, scrollDirection: Int,
                isFastScroll: Boolean, isIdle: Boolean, isDiscreteJump: Boolean) {
                calls += listOf(firstVisiblePosition, visibleItemCount, scrollDirection, isFastScroll, isIdle, isDiscreteJump)
            }
            override fun applyDebugZoomToVisiblePages(recyclerView: RecyclerView, scale: Float, panX: Float, panY: Float) {}
            override fun trimMemory(level: Int) {}
            override fun flushPendingRefreshesIfIdle() {}
            override fun close() {}
        }
        try {
            val activity = controller.get()
            MangaReaderActivity::class.java.getDeclaredField("pageAdapter").apply { isAccessible = true; set(activity, adapter) }
            MangaReaderActivity::class.java.getDeclaredField("imageEntries").apply {
                isAccessible = true; set(activity, (0 until 100).map { "page-$it" })
            }
            val preload = MangaReaderActivity::class.java.getDeclaredMethod("preloadReaderAround",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            preload.invoke(activity, 0, 1, 1, false, false, false, false)
            preload.invoke(activity, 0, 2, 1, false, false, false, false)
            assertEquals("the second visible page must enter the decoder's visible range", 2, calls.size)
            preload.invoke(activity, 0, 2, 1, true, false, false, false)
            assertEquals("entering fast scroll must cancel idle work immediately", 3, calls.size)
            preload.invoke(activity, 0, 2, 1, true, false, false, false)
            assertEquals("identical requests should still be deduplicated", 3, calls.size)
            preload.invoke(activity, 0, 2, -1, true, false, false, false)
            assertEquals("changing direction must update the prefetch window", 4, calls.size)
            assertEquals(listOf(0, 2, -1, true, false, false), calls.last())
        } finally { controller.destroy() }
    }
}
