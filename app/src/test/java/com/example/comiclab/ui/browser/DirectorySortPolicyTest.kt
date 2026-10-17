package com.example.comiclab.ui.browser

import com.example.comiclab.ui.browser.DirectorySortMetadata
import com.example.comiclab.ui.browser.DirectorySortOrder
import com.example.comiclab.ui.browser.DirectorySortPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectorySortPolicyTest {

    @Test
    fun sizeSortReadsEachEntryMetadataOnceAndKeepsDirectoriesFirst() {
        val directoryZ = File("/library/z")
        val fileB = File("/library/b.cbz")
        val directoryA = File("/library/a")
        val fileA = File("/library/a.cbz")
        val metadataReads = mutableMapOf<File, Int>()
        val metadata = mapOf(
            directoryZ to DirectorySortMetadata(isDirectory = true, name = "z", modifiedAt = 50L, sizeBytes = 0L),
            fileB to DirectorySortMetadata(isDirectory = false, name = "b.cbz", modifiedAt = 10L, sizeBytes = 20L),
            directoryA to DirectorySortMetadata(isDirectory = true, name = "a", modifiedAt = 60L, sizeBytes = 0L),
            fileA to DirectorySortMetadata(isDirectory = false, name = "a.cbz", modifiedAt = 20L, sizeBytes = 30L)
        )

        val sorted = DirectorySortPolicy.sort(
            files = listOf(directoryZ, fileB, directoryA, fileA),
            order = DirectorySortOrder.SIZE_DESC
        ) { file ->
            metadataReads[file] = metadataReads.getOrDefault(file, 0) + 1
            metadata.getValue(file)
        }

        assertEquals(listOf(directoryA, directoryZ, fileA, fileB), sorted)
        assertEquals(mapOf(directoryZ to 1, fileB to 1, directoryA to 1, fileA to 1), metadataReads)
    }

    @Test
    fun modifiedTimeSortUsesNameAsStableTieBreaker() {
        val fileB = File("/library/b.cbz")
        val fileA = File("/library/a.cbz")
        val sorted = DirectorySortPolicy.sort(
            files = listOf(fileB, fileA),
            order = DirectorySortOrder.MODIFIED_DESC
        ) { file ->
            DirectorySortMetadata(
                isDirectory = false,
                name = file.name,
                modifiedAt = 100L,
                sizeBytes = 0L
            )
        }

        assertEquals(listOf(fileA, fileB), sorted)
    }
}
