package com.example.comiclab.ui.browser.panels

import android.view.View
import android.widget.TextView
import com.example.comiclab.R
import com.example.comiclab.core.store.ReadingHistoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadingHistoryAdapterRobolectricTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearHistory() {
        ReadingHistoryStore.clear(context)
    }

    @After
    fun clearHistoryAfterTest() {
        ReadingHistoryStore.clear(context)
    }

    @Test
    fun bindsDateComicNameAndPathAndClickReportsSelectedItemWithoutChangingStore() {
        val firstComic = comicFile("first.cbz")
        val selectedComic = comicFile("selected.cbz")
        ReadingHistoryStore.record(context, firstComic)
        ReadingHistoryStore.record(context, selectedComic)
        val storedItemsBeforeClick = ReadingHistoryStore.items(context)
        val clickedItems = mutableListOf<ReadingHistoryStore.Item>()
        var emptyChanged: Boolean? = null
        val adapter = ReadingHistoryAdapter(
            context = context,
            items = storedItemsBeforeClick.toMutableList(),
            onItemClick = clickedItems::add,
            onJumpToPath = {},
            onItemsEmptyChanged = { emptyChanged = it }
        )

        try {
            assertEquals(3, adapter.itemCount)
            withVisibleRecyclerViewHolder(adapter, 0) { holder ->
                val dateText = holder.itemView.findViewById<TextView>(R.id.tvReadingHistoryDate)
                assertEquals(context.getString(R.string.today), dateText.text.toString())
            }
            withVisibleRecyclerViewHolder(adapter, 2) { holder ->
                val itemView = holder.itemView
                assertEquals(
                    firstComic.name,
                    itemView.findViewById<TextView>(R.id.tvReadingHistoryName).text.toString()
                )
                assertEquals(
                    temporaryFolder.root.absolutePath,
                    itemView.findViewById<TextView>(R.id.tvReadingHistoryPath).text.toString()
                )
                assertTrue(itemView.findViewById<View>(R.id.layoutReadingHistoryForeground).performClick())
            }

            assertEquals(listOf(storedItemsBeforeClick[1]), clickedItems)
            assertEquals(storedItemsBeforeClick, ReadingHistoryStore.items(context))

            val emptyAdapter = ReadingHistoryAdapter(context, mutableListOf(), {}, {}, { emptyChanged = it })
            try {
                assertEquals(0, emptyAdapter.itemCount)
                emptyAdapter.clearItems()
                assertEquals(true, emptyChanged)
            } finally {
                emptyAdapter.close()
            }
        } finally {
            adapter.close()
        }
    }

    private fun comicFile(name: String): File = temporaryFolder.newFile(name).apply {
        writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
    }
}
