package com.example.comiclab.ui.browser

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.AppSettings
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
