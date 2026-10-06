package com.example.comiclab.ui.browser

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.AppSettings
import com.example.comiclab.BoundedKeyedTaskQueue
import com.example.comiclab.CommonFunc
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverAdapter
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverLoadTracker
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverSource
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverRecyclerView
import com.example.comiclab.R
import com.example.comiclab.ui.browser.model.FileItem
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileListAdapterBindingTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun regularRowReuseClearsPreviousStateAndBindsFolderFileParentAndUnknown() = fixture { root ->
        val folder = File(root, "folder").apply { mkdir() }
        val file = File(root, "notes.bin").apply { writeText("abc") }
        FavoritePathStore.record(context, folder)
        val items = mutableListOf(FileItem(folder, childCount = 2), FileItem(file), FileItem(isParent = true), FileItem())
        val adapter = FileListAdapter(context, items)
        try {
            val parent = FrameLayout(context)
            val row = adapter.getView(0, null, parent)
            assertEquals(R.layout.file_item, row.tag)
            assertEquals("folder", text(row, R.id.tvName))
            assertEquals("(D)", text(row, R.id.tvTypeMarker))
            assertEquals(context.getString(R.string.item_count, 2), text(row, R.id.tvInfo))
            assertEquals(View.VISIBLE, row.findViewById<ImageView>(R.id.imgFavoriteMarker).visibility)
            assertFalse(row.isClickable)
            row.isPressed = true
            row.isSelected = true
            row.isActivated = true
            row.setOnClickListener { fail("stale click") }
            row.setOnLongClickListener { fail("stale long click"); true }
            row.findViewById<ImageView>(R.id.imgIcon).setImageBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
            assertSame(row, adapter.getView(1, row, parent))
            assertEquals("notes.bin", text(row, R.id.tvName))
            assertEquals("(F)", text(row, R.id.tvTypeMarker))
            assertEquals(CommonFunc.formatFileSize(3), text(row, R.id.tvInfo))
            assertFalse(row.isPressed || row.isSelected || row.isActivated || row.isClickable || row.isLongClickable)
            assertFalse(row.performClick())
            assertEquals(View.GONE, row.findViewById<ImageView>(R.id.imgFavoriteMarker).visibility)
            assertNull(row.findViewById<ImageView>(R.id.imgFavoriteMarker).drawable)
            assertNull(row.findViewById<ImageView>(R.id.imgIcon).tag)
            assertEquals(ImageView.ScaleType.CENTER_INSIDE, row.findViewById<ImageView>(R.id.imgIcon).scaleType)
            assertSame(row, adapter.getView(2, row, parent))
            assertEquals("..", text(row, R.id.tvName))
            assertEquals(context.getString(R.string.parent_directory), text(row, R.id.tvInfo))
            assertEquals("", text(row, R.id.tvDate))
            assertSame(row, adapter.getView(3, row, parent))
            assertEquals(context.getString(R.string.unknown_item), text(row, R.id.tvName))
            assertEquals("", text(row, R.id.tvTypeMarker))
            assertNull(row.findViewById<RecyclerView>(R.id.collectionCoverList).adapter)
            assertEquals(View.GONE, row.findViewById<RecyclerView>(R.id.collectionCoverList).visibility)
        } finally { adapter.close(); FavoritePathStore.clear(context) }
    }

    @Test
    fun bookcaseReuseRetargetsCallbacksAndDifferentLayoutsNeverReuseRows() = fixture { root ->
        val first = File(root, "first").apply { mkdir() }
        val second = File(root, "second").apply { mkdir() }
        AppSettings.setBookcaseDirectory(context, first, true)
        AppSettings.setBookcaseDirectory(context, second, true)
        val clicks = mutableListOf<File>()
        val longClicks = mutableListOf<File>()
        val adapter = FileListAdapter(context, mutableListOf(FileItem(first, childCount = 0), FileItem(second, childCount = 0), FileItem(isParent = true)), clicks::add, longClicks::add)
        try {
            val parent = FrameLayout(context)
            val row = adapter.getView(0, null, parent)
            assertEquals(2, adapter.viewTypeCount)
            assertNotEquals(adapter.getItemViewType(0), adapter.getItemViewType(2))
            assertEquals(R.layout.file_item_manga_collection, row.tag)
            assertTrue(row.findViewById<RecyclerView>(R.id.collectionCoverList) is MangaCollectionCoverRecyclerView)
            row.performClick(); assertTrue(row.performLongClick())
            assertSame(row, adapter.getView(1, row, parent))
            assertEquals("second", text(row, R.id.tvName))
            row.performClick(); assertTrue(row.performLongClick())
            assertEquals(listOf(first, second), clicks)
            assertEquals(listOf(first, second), longClicks)
            val regular = adapter.getView(2, row, parent)
            assertNotSame(row, regular)
            assertEquals(R.layout.file_item, regular.tag)
            assertFalse(regular.isClickable || regular.isLongClickable)
            assertNotSame(regular, adapter.getView(0, regular, parent))
        } finally { adapter.close(); AppSettings.setBookcaseDirectory(context, first, false); AppSettings.setBookcaseDirectory(context, second, false) }
    }

    @Test
    fun countCompletionAfterRetargetUpdatesOnlyMatchingModelAndRebindsCount() = fixture { root ->
        val first = File(root, "first").apply { mkdir() }
        val second = File(root, "second").apply { mkdir() }
        val requests = mutableListOf<File>()
        val items = mutableListOf(FileItem(first), FileItem(second))
        val adapter = FileListAdapter(context, items, onDirectoryChildCountRequested = requests::add)
        try {
            val list = ListView(context)
            val container = FrameLayout(context).apply {
                addView(
                    list,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
            list.adapter = adapter
            container.measure(
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            )
            container.layout(0, 0, 480, 800)

            val firstVisibleRow = list.getChildAt(0)
            val secondVisibleRow = list.getChildAt(1)
            assertNotNull(firstVisibleRow)
            assertNotNull(secondVisibleRow)
            adapter.updateDirectoryChildCount(first, 7, list)
            assertEquals(context.getString(R.string.item_count, 7), text(firstVisibleRow, R.id.tvInfo))
            assertEquals(
                context.getString(R.string.item_count_pending),
                text(secondVisibleRow, R.id.tvInfo)
            )
            adapter.updateDirectoryChildCount(File(root, "absent"), 99, list)
            assertEquals(7, items[0].childCount)
            assertNull(items[1].childCount)
            assertEquals(context.getString(R.string.item_count, 7), text(firstVisibleRow, R.id.tvInfo))
            adapter.getView(0, firstVisibleRow, list)
            assertEquals(context.getString(R.string.item_count, 7), text(firstVisibleRow, R.id.tvInfo))
            assertEquals(listOf(first, second), requests)
        } finally { adapter.close() }
    }

    @Test fun recycledCoverRejectsOldResultAndNewBindingAcceptsNewResult() = coverFixture { adapter, nested, holder, tracker ->
        val oldKey = holder.imageView.tag as String
        val oldId = tracker.requestId(oldKey)!!
        nested.onViewRecycled(holder)
        assertNull(holder.imageView.tag)
        assertNull(holder.imageView.drawable)
        assertNull(tracker.requestId(oldKey))
        nested.onBindViewHolder(holder, 0)
        val newId = tracker.requestId(oldKey)!!
        assertNotEquals(oldId, newId)
        val placeholder = holder.imageView.drawable
        deliver(adapter, oldKey, oldId)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(placeholder, holder.imageView.drawable)
        val bitmap = deliver(adapter, oldKey, newId)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(bitmap, (holder.imageView.drawable as BitmapDrawable).bitmap)
        assertEquals(ImageView.ScaleType.CENTER_CROP, holder.imageView.scaleType)
        assertNull(holder.imageView.imageTintList)
    }

    @Test fun postedCoverResultCannotOverwriteRetargetedView() = coverFixture { adapter, _, holder, tracker ->
        val key = holder.imageView.tag as String
        val placeholder = holder.imageView.drawable
        deliver(adapter, key, tracker.requestId(key)!!)
        holder.imageView.tag = "another-cover"
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(placeholder, holder.imageView.drawable)
    }

    @Test fun postedCoverResultCannotUpdateAfterClose() = coverFixture { adapter, _, holder, tracker ->
        val key = holder.imageView.tag as String
        val placeholder = holder.imageView.drawable
        deliver(adapter, key, tracker.requestId(key)!!)
        adapter.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(placeholder, holder.imageView.drawable)
    }

    private fun coverFixture(block: (FileListAdapter, MangaCollectionCoverAdapter, MangaCollectionCoverAdapter.CoverViewHolder, MangaCollectionCoverLoadTracker<ImageView>) -> Unit) = fixture { root ->
        val directory = File(root, "bookcase").apply { mkdir() }
        val archive = File(directory, "comic.cbz").apply { writeText("fixture") }
        AppSettings.setBookcaseDirectory(context, directory, true)
        val adapter = FileListAdapter(context, mutableListOf(FileItem(directory, childCount = 1)))
        val release = CountDownLatch(1)
        try {
            val started = CountDownLatch(2)
            val queue = field<BoundedKeyedTaskQueue>(adapter, "archiveCoverTasks")
            repeat(2) { index -> queue.execute("fixture-$index") { started.countDown(); release.await() } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            field<LruCache<String, List<MangaCollectionCoverSource>>>(adapter, "collectionSourceCache").put("collection:${directory.absolutePath}:${directory.lastModified()}", listOf(MangaCollectionCoverSource(archive, "cover.png")))
            val row = adapter.getView(0, null, FrameLayout(context))
            val recycler = row.findViewById<RecyclerView>(R.id.collectionCoverList)
            assertEquals(View.VISIBLE, recycler.visibility)
            assertEquals(View.GONE, row.findViewById<ImageView>(R.id.imgIcon).visibility)
            val nested = recycler.adapter as MangaCollectionCoverAdapter
            val holder = nested.onCreateViewHolder(recycler, 0)
            // Attach the view so View.post uses the real main-looper queue.
            val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            activity.setContentView(holder.imageView)
            try {
                nested.onBindViewHolder(holder, 0)
                assertEquals(1, nested.itemCount)
                assertTrue(holder.imageView.contentDescription.toString().contains(archive.name))
                block(adapter, nested, holder, field(adapter, "collectionCoverLoadTracker"))
            } finally { activity.finish() }
        } finally { adapter.close(); release.countDown(); AppSettings.setBookcaseDirectory(context, directory, false) }
    }

    // Only decoded-result delivery is controlled; registration and recycle use actual adapter paths.
    private fun deliver(adapter: FileListAdapter, key: String, requestId: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val method = FileListAdapter::class.java.declaredMethods.single { it.name == "handleCollectionCoverResult" }
        method.isAccessible = true
        method.invoke(adapter, key, requestId, "fixture-bookcase", null, 0, bitmap)
        return bitmap
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(adapter: FileListAdapter, name: String): T = FileListAdapter::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(adapter) as T }
    private fun text(row: View, id: Int) = row.findViewById<TextView>(id).text.toString()
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("comiclab-binding-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
