package com.example.comiclab.ui.browser

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.AppSettings
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ebook.EbookReaderProgressMapper
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverRecyclerView
import com.example.comiclab.R
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchResultAdapterTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun bindsDirectoryAndFileRowsWithTheirOwnIconsAndDetails() = fixture { root ->
        val directory = File(root, "folder").apply { mkdir() }
        val file = File(root, "notes.bin").apply { writeText("fixture") }
        val adapter = SearchResultAdapter(context, listOf(directory, file))
        try {
            val parent = FrameLayout(context)
            val directoryRow = adapter.getView(0, null, parent)
            val directoryIcon = directoryRow.findViewById<ImageView>(R.id.imgIcon).drawable
            assertNotNull(directoryIcon)
            assertEquals("folder", text(directoryRow, R.id.tvName))
            assertEquals("(D)", text(directoryRow, R.id.tvTypeMarker))

            val fileRow = adapter.getView(1, directoryRow, parent)
            val fileIcon = fileRow.findViewById<ImageView>(R.id.imgIcon).drawable
            assertSame(directoryRow, fileRow)
            assertNotNull(fileIcon)
            assertNotSame(directoryIcon.constantState, fileIcon.constantState)
            assertEquals("notes.bin", text(fileRow, R.id.tvName))
            assertEquals("(F)", text(fileRow, R.id.tvTypeMarker))
            assertEquals(root.absolutePath, text(fileRow, R.id.tvInfo))
        } finally {
            adapter.close()
        }
    }

    @Test
    fun bookcaseRowClickRetargetsAndClearsRecycledNestedListeners() = fixture { root ->
        val first = File(root, "first").apply { mkdir() }
        val second = File(root, "second").apply { mkdir() }
        AppSettings.setBookcaseDirectory(context, first, true)
        AppSettings.setBookcaseDirectory(context, second, true)
        val opened = mutableListOf<File>()
        val staleActions = mutableListOf<String>()
        val adapter = SearchResultAdapter(context, listOf(first, second), opened::add)
        try {
            val parent = FrameLayout(context)
            val row = adapter.getView(0, null, parent)
            val covers = row.findViewById<RecyclerView>(R.id.collectionCoverList)
            assertEquals(R.layout.file_item_manga_collection, row.tag)
            assertTrue(covers is MangaCollectionCoverRecyclerView)
            assertTrue(row.performClick())

            covers.setOnClickListener { staleActions += "click" }
            covers.setOnLongClickListener { staleActions += "long-click"; true }
            covers.isLongClickable = true

            val rebound = adapter.getView(1, row, parent)
            assertSame(row, rebound)
            assertTrue(rebound.performClick())
            assertFalse(covers.performClick())
            assertFalse(covers.performLongClick())
            assertEquals(listOf(first, second), opened)
            assertTrue(staleActions.isEmpty())
        } finally {
            adapter.close()
            AppSettings.setBookcaseDirectory(context, first, false)
            AppSettings.setBookcaseDirectory(context, second, false)
        }
    }

    @Test
    fun rebindingRegularResultClearsOldNestedClickAndLongClickListeners() = fixture { root ->
        val ebook = File(root, "book.epub").apply { writeText("fixture") }
        val file = File(root, "notes.bin").apply { writeText("fixture") }
        val staleActions = mutableListOf<String>()
        val adapter = SearchResultAdapter(context, listOf(ebook, file))
        try {
            val parent = FrameLayout(context)
            val row = adapter.getView(0, null, parent)
            val info = row.findViewById<View>(R.id.itemInfoContainer)
            val covers = row.findViewById<RecyclerView>(R.id.collectionCoverList)
            info.setOnClickListener { staleActions += "info-click" }
            info.setOnLongClickListener { staleActions += "info-long-click"; true }
            covers.setOnClickListener { staleActions += "covers-click" }
            covers.setOnLongClickListener { staleActions += "covers-long-click"; true }
            covers.isLongClickable = true

            assertSame(row, adapter.getView(1, row, parent))
            assertFalse(info.performClick())
            assertFalse(info.performLongClick())
            assertFalse(covers.performClick())
            assertFalse(covers.performLongClick())
            assertTrue(staleActions.isEmpty())
        } finally {
            adapter.close()
        }
    }

    @Test
    fun searchResultsShowReadingProgressBarsOnlyWhenEnabledForSupportedFiles() = fixture { root ->
        val directory = File(root, "folder").apply { mkdir() }
        val unsupported = File(root, "notes.bin").apply { writeText("fixture") }
        val comic = File(root, "comic.cbz").apply { writeBytes(byteArrayOf(1, 2)) }
        val ebook = File(root, "book.epub").apply { writeText("epub") }
        val unread = File(root, "unread.cbz").apply { writeBytes(byteArrayOf(3, 4)) }
        ReaderProgressStore.prepareSave(context, comic, 4, 0, totalCount = 20).commit()
        EbookProgressStore.save(context, ebook, EbookProgress(2, 0.0f), chapterCount = 4)
        AppSettings.setShowFileReadingProgressEnabled(context, false)

        val adapter = SearchResultAdapter(context, listOf(directory, unsupported, comic, ebook, unread))
        try {
            val parent = FrameLayout(context)
            assertEquals(View.GONE, adapter.getView(2, null, parent).findViewById<ProgressBar>(R.id.progressFileReading).visibility)

            AppSettings.setShowFileReadingProgressEnabled(context, true)
            val comicBar = adapter.getView(2, null, parent).findViewById<ProgressBar>(R.id.progressFileReading)
            assertEquals(View.VISIBLE, comicBar.visibility)
            assertEquals(20, comicBar.max)
            assertEquals(5, comicBar.progress)

            val ebookBar = adapter.getView(3, null, parent).findViewById<ProgressBar>(R.id.progressFileReading)
            assertEquals(View.VISIBLE, ebookBar.visibility)
            assertEquals(EbookReaderProgressMapper.SLIDER_MAX, ebookBar.max)
            assertEquals(500, ebookBar.progress)

            val unreadBar = adapter.getView(4, null, parent).findViewById<ProgressBar>(R.id.progressFileReading)
            assertEquals(View.VISIBLE, unreadBar.visibility)
            assertEquals(0, unreadBar.progress)

            assertEquals(View.GONE, adapter.getView(0, null, parent).findViewById<ProgressBar>(R.id.progressFileReading).visibility)
            assertEquals(View.GONE, adapter.getView(1, null, parent).findViewById<ProgressBar>(R.id.progressFileReading).visibility)
        } finally {
            adapter.close()
            AppSettings.setShowFileReadingProgressEnabled(context, false)
            ReaderProgressStore.clearSavedReadingProgress(context, comic)
            EbookProgressStore.clear(context, ebook)
        }
    }

    private fun text(row: View, id: Int) = row.findViewById<TextView>(id).text.toString()

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("comiclab-search-adapter-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
