package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MangaCollectionCoverClickHandlerTest {

    @Test
    fun dispatch_invokesDirectoryCallback() {
        val directory = File("/comic-collection")
        var clickedDirectory: File? = null

        val handled = MangaCollectionCoverClickHandler.dispatch(directory) {
            clickedDirectory = it
        }

        assertTrue(handled)
        assertEquals(directory, clickedDirectory)
    }

    @Test
    fun dispatch_returnsFalseWhenCallbackIsMissing() {
        assertFalse(MangaCollectionCoverClickHandler.dispatch(File("/comic-collection"), null))
    }

    @Test
    fun dispatchLongClick_invokesDirectoryMenuCallback() {
        val directory = File("/comic-collection")
        var longPressedDirectory: File? = null

        val handled = MangaCollectionCoverClickHandler.dispatchLongClick(directory) {
            longPressedDirectory = it
        }

        assertTrue(handled)
        assertEquals(directory, longPressedDirectory)
    }

    @Test
    fun dispatchLongClick_returnsFalseWhenCallbackIsMissing() {
        assertFalse(MangaCollectionCoverClickHandler.dispatchLongClick(File("/comic-collection"), null))
    }
}
