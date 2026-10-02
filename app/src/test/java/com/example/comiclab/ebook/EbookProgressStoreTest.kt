package com.example.comiclab.ebook

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookProgressStoreTest {

    @Test
    fun progressNormalizationClampsChapterAndScrollFraction() {
        assertEquals(EbookProgress(0, 0f), EbookProgress(-3, -0.5f).normalized())
        assertEquals(EbookProgress(4, 1f), EbookProgress(4, 1.5f).normalized())
    }

    @Test
    fun fileIdentityIsStableAndChangesWhenPathOrFileMetadataChanges() {
        val root = Files.createTempDirectory("comiclab-ebook-progress-identity").toFile()
        try {
            val file = File(root, "book.epub").apply { writeText("one") }
            val sameContentDifferentPath = File(root, "copy.epub").apply { writeText("one") }
            val originalIdentity = EbookProgressStore.fileIdentity(file)

            assertEquals(originalIdentity, EbookProgressStore.fileIdentity(file))
            assertNotEquals(originalIdentity, EbookProgressStore.fileIdentity(sameContentDifferentPath))

            file.writeText("changed size")
            assertNotEquals(originalIdentity, EbookProgressStore.fileIdentity(file))

            assertTrue(file.setLastModified(1_600_000_000_000L))
            val changedTimestampIdentity = EbookProgressStore.fileIdentity(file)
            assertNotEquals(originalIdentity, changedTimestampIdentity)
            assertEquals(changedTimestampIdentity, EbookProgressStore.fileIdentity(file))
        } finally {
            root.deleteRecursively()
        }
    }
}
