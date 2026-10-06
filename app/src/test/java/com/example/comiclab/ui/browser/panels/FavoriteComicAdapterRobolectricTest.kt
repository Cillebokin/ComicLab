package com.example.comiclab.ui.browser.panels

import com.example.comiclab.R
import com.example.comiclab.core.store.FavoriteComicStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FavoriteComicAdapterRobolectricTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearFavorites() {
        FavoriteComicStore.clear(context)
    }

    @After
    fun clearFavoritesAfterTest() {
        FavoriteComicStore.clear(context)
    }

    @Test
    fun bindsComicNameAndPathAndClickReportsSelectedItemWithoutChangingStore() {
        val firstComic = comicFile("first.cbz")
        val selectedComic = comicFile("selected.cbz")
        FavoriteComicStore.record(context, firstComic)
        FavoriteComicStore.record(context, selectedComic)
        val storedItemsBeforeClick = FavoriteComicStore.items(context)
        val clickedItems = mutableListOf<FavoriteComicStore.Item>()
        var emptyChanged: Boolean? = null
        val adapter = FavoriteComicAdapter(
            context = context,
            items = storedItemsBeforeClick.toMutableList(),
            onItemClick = clickedItems::add,
            onJumpToPath = {},
            onItemsEmptyChanged = { emptyChanged = it }
        )

        try {
            assertEquals(2, adapter.itemCount)
            withVisibleRecyclerViewHolder(adapter, 1) { holder ->
                assertEquals(selectedComic.name, holder.tvName.text.toString())
                assertEquals(temporaryFolder.root.absolutePath, holder.tvPath.text.toString())
                assertTrue(holder.content.performClick())
            }

            assertEquals(listOf(storedItemsBeforeClick[1]), clickedItems)
            assertEquals(storedItemsBeforeClick, FavoriteComicStore.items(context))

            val emptyAdapter = FavoriteComicAdapter(context, mutableListOf(), {}, {}, { emptyChanged = it })
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
