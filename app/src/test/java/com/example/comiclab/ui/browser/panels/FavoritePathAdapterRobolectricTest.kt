package com.example.comiclab.ui.browser.panels

import com.example.comiclab.core.store.FavoritePathStore
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FavoritePathAdapterRobolectricTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearFavorites() {
        FavoritePathStore.clear(context)
    }

    @After
    fun clearFavoritesAfterTest() {
        FavoritePathStore.clear(context)
    }

    @Test
    fun bindsDirectoryNameAndPathAndClickReportsSelectedItemWithoutChangingStore() {
        val firstDirectory = temporaryFolder.newFolder("first-shelf")
        val selectedDirectory = temporaryFolder.newFolder("selected-shelf")
        FavoritePathStore.record(context, firstDirectory)
        FavoritePathStore.record(context, selectedDirectory)
        val storedItemsBeforeClick = FavoritePathStore.items(context)
        val clickedItems = mutableListOf<FavoritePathStore.Item>()
        var emptyChanged: Boolean? = null
        val adapter = FavoritePathAdapter(
            context = context,
            items = storedItemsBeforeClick.toMutableList(),
            onItemClick = clickedItems::add,
            onItemsEmptyChanged = { emptyChanged = it }
        )

        assertEquals(2, adapter.itemCount)
        withVisibleRecyclerViewHolder(adapter, 1) { holder ->
            assertEquals(selectedDirectory.name, holder.tvName.text.toString())
            assertEquals(selectedDirectory.absolutePath, holder.tvPath.text.toString())
            assertTrue(holder.content.performClick())
        }

        assertEquals(listOf(storedItemsBeforeClick[1]), clickedItems)
        assertEquals(storedItemsBeforeClick, FavoritePathStore.items(context))

        val emptyAdapter = FavoritePathAdapter(context, mutableListOf(), {}, { emptyChanged = it })
        assertEquals(0, emptyAdapter.itemCount)
        emptyAdapter.clearItems()
        assertEquals(true, emptyChanged)
    }
}
