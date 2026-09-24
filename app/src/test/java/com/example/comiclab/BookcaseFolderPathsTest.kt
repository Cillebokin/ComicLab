package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Test

class BookcaseFolderPathsTest {

    @Test
    fun setSelection_changesOnlyTheRequestedFolder() {
        val selectedPaths = setOf("/storage/comics/a", "/storage/comics/b")

        assertEquals(
            setOf("/storage/comics/a", "/storage/comics/b", "/storage/comics/c"),
            BookcaseFolderPaths.withSelection(selectedPaths, "/storage/comics/c", selected = true)
        )

        val updated = BookcaseFolderPaths.withSelection(
            selectedPaths,
            "/storage/comics/b",
            selected = false
        )

        assertEquals(setOf("/storage/comics/a"), updated)
    }

    @Test
    fun bulkSelection_changesOnlyProvidedFoldersAndPreservesNestedSelections() {
        val selectedPaths = setOf(
            "/storage/comics/parent/selected-child",
            "/storage/comics/parent/selected-child/grandchild-shelf",
            "/storage/comics/parent-extra/shelf"
        )
        val directChildren = setOf(
            "/storage/comics/parent/selected-child",
            "/storage/comics/parent/ordinary-child"
        )

        val selected = BookcaseFolderPaths.withSelections(
            selectedPaths,
            directChildren,
            selected = true
        )
        assertEquals(
            setOf(
                "/storage/comics/parent/selected-child",
                "/storage/comics/parent/selected-child/grandchild-shelf",
                "/storage/comics/parent/ordinary-child",
                "/storage/comics/parent-extra/shelf"
            ),
            selected
        )

        val deselected = BookcaseFolderPaths.withSelections(
            selected,
            directChildren,
            selected = false
        )
        assertEquals(
            setOf(
                "/storage/comics/parent/selected-child/grandchild-shelf",
                "/storage/comics/parent-extra/shelf"
            ),
            deselected
        )
    }

    @Test
    fun rename_transfersBookcaseSelectionWithoutSelectingOrdinaryFolders() {
        val selectedPaths = setOf(
            "/storage/comics/shelf",
            "/storage/comics/shelf/inner-shelf"
        )

        val renamed = BookcaseFolderPaths.afterRename(
            selectedPaths,
            "/storage/comics/shelf",
            "/storage/comics/new-shelf"
        )

        assertEquals(
            setOf("/storage/comics/new-shelf", "/storage/comics/new-shelf/inner-shelf"),
            renamed
        )
        assertEquals(
            setOf("/storage/comics/selected"),
            BookcaseFolderPaths.afterRename(
                setOf("/storage/comics/selected"),
                "/storage/comics/ordinary",
                "/storage/comics/renamed"
            )
        )
    }

    @Test
    fun removeDirectory_clearsItsBookcaseAndNestedBookcasesOnly() {
        val remaining = BookcaseFolderPaths.underDirectory(
            setOf(
                "/storage/comics/shelf",
                "/storage/comics/shelf/inner-shelf",
                "/storage/comics/shelf-extra"
            ),
            "/storage/comics/shelf"
        )

        assertEquals(setOf("/storage/comics/shelf-extra"), remaining)
    }
}
