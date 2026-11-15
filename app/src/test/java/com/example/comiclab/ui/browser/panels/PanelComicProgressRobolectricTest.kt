package com.example.comiclab.ui.browser.panels

import android.widget.ProgressBar
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.core.store.ReaderProgressStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PanelComicProgressRobolectricTest {
    @Test fun openPanelsRefreshAfterDelayedProgressWriteAndStopListeningWhenClosed() {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("panel-refresh", ".cbz", context.cacheDir)
        val item = FavoriteComicStore.Item(file, 1, file.length(), file.lastModified())
        val historyItem = ReadingHistoryStore.Item(file, 1, file.length(), file.lastModified())
        val favorite = FavoriteComicAdapter(context, mutableListOf(item), {}, {}, {})
        val history = ReadingHistoryAdapter(context, mutableListOf(historyItem), {}, {}, {})
        var favoriteChanges = 0
        var historyChanges = 0
        favorite.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() { favoriteChanges++ }
        })
        history.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() { historyChanges++ }
        })
        try {
            ReaderProgressStore.prepareSave(context, file, 2, 0).commit()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("favorite panel should refresh", favoriteChanges > 0)
            assertTrue("history panel should refresh", historyChanges > 0)
            favorite.close()
            history.close()
            val previous = favoriteChanges to historyChanges
            ReaderProgressStore.prepareSave(context, file, 3, 0).commit()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(previous, favoriteChanges to historyChanges)
        } finally {
            favorite.close()
            history.close()
            ReaderProgressStore.clearSavedReadingProgress(context, file)
            file.delete()
        }
    }

    @Test fun favoriteAndHistoryShowSavedProgressAndUnknownAsEmpty() {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("panel-progress", ".cbz", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(4) { index ->
                zip.putNextEntry(ZipEntry("$index.jpg"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        val bad = File.createTempFile("panel-invalid", ".cbz", context.cacheDir)
        ReaderProgressStore.prepareSave(context, file, 1, 0).commit()
        FavoriteComicStore.clear(context)
        ReadingHistoryStore.clear(context)
        FavoriteComicStore.record(context, file)
        ReadingHistoryStore.record(context, file)
        val favorite = FavoriteComicAdapter(context, FavoriteComicStore.items(context).toMutableList(), {}, {}, {})
        val history = ReadingHistoryAdapter(context, ReadingHistoryStore.items(context).toMutableList(), {}, {}, {})
        try {
            fun checkProgress(list: androidx.recyclerview.widget.RecyclerView, position: Int, id: Int, expected: Int = 2) {
                val deadline = System.currentTimeMillis() + 5000
                var bar: ProgressBar? = null
                while (bar?.progress != expected && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    list.measure(android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY))
                    list.layout(0, 0, 480, 800)
                    bar = list.findViewHolderForAdapterPosition(position)?.itemView?.findViewById(id)
                }
                assertNotNull(bar)
                assertEquals(4, bar!!.max)
                assertEquals(expected, bar.progress)
            }
            val id = context.resources.getIdentifier("progressPanelReading", "id", context.packageName)
            assertNotEquals("row must have progress bar", 0, id)
            withVisibleRecyclerViewHolder(favorite, 0) {
                val list = it.itemView.parent as androidx.recyclerview.widget.RecyclerView
                checkProgress(list, 0, id)
                ReaderProgressStore.prepareSave(context, file, 3, 0).commit()
                checkProgress(list, 0, id, 4)
            }
            ReaderProgressStore.prepareSave(context, file, 1, 0).commit()
            withVisibleRecyclerViewHolder(history, 1) {
                val list = it.itemView.parent as androidx.recyclerview.widget.RecyclerView
                checkProgress(list, 1, id)
                ReaderProgressStore.prepareSave(context, file, 3, 0).commit()
                checkProgress(list, 1, id, 4)
            }
            ReaderProgressStore.prepareSave(context, file, 3, 0).commit()
            withVisibleRecyclerViewHolder(favorite, 0) {
                val progress = it.itemView.findViewById<ProgressBar>(id)
                assertEquals(4, progress.max)
                assertEquals(4, progress.progress)
            }
            ReaderProgressStore.clearSavedReadingProgress(context, file)
            withVisibleRecyclerViewHolder(favorite, 0) {
                assertEquals(0, it.itemView.findViewById<ProgressBar>(id).progress)
            }
            val badItem = FavoriteComicStore.Item(bad, 1, 0, 0)
            val badAdapter = FavoriteComicAdapter(context, mutableListOf(badItem), {}, {}, {})
            try {
                withVisibleRecyclerViewHolder(badAdapter, 0) {
                    assertEquals(0, it.itemView.findViewById<ProgressBar>(id).progress)
                }
            } finally { badAdapter.close() }
        } finally {
            favorite.close()
            history.close()
            ReaderProgressStore.clearSavedReadingProgress(context, file)
            FavoriteComicStore.clear(context)
            ReadingHistoryStore.clear(context)
            file.delete()
            bad.delete()
        }
    }
}
