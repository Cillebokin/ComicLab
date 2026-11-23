package com.example.comiclab.core.nas

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NasPublicDownloadPartJournalTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun recoveryKeepsActiveDownloadAndOnlyDeletesItsRegisteredRemainder() {
        val root = temporary.newFolder("public").canonicalFile
        val firstDirectory = File(root, "first").apply { mkdir() }
        val secondDirectory = File(root, "second").apply { mkdir() }
        val journal = NasPublicDownloadPartJournal(context, root)
        val firstPart = part(firstDirectory)
        val otherPart = part(secondDirectory).apply { writeText("user staging file") }
        val archive = File(firstDirectory, "user.cbz").apply { writeText("user archive") }
        journal.createPart(firstPart).use {
            firstPart.writeText("interrupted")
            assertEquals(0, NasPublicDownloadPartJournal(context, root).recover())
            assertTrue(firstPart.isFile)
        }

        assertEquals(1, NasPublicDownloadPartJournal(context, root).recover())
        assertFalse(firstPart.exists())
        assertEquals("user staging file", otherPart.readText())
        assertEquals("user archive", archive.readText())
        assertEquals(0, journal.recover())
    }

    @Test
    fun publishedDestinationAndUnregisteredExactNameArePreserved() {
        val root = temporary.newFolder("storage").canonicalFile
        val journal = NasPublicDownloadPartJournal(context, root)
        val temporaryFile = part(root)
        val finalFile = File(root, "book.cbz")
        journal.createPart(temporaryFile).use {
            temporaryFile.writeText("complete")
            Files.move(temporaryFile.toPath(), finalFile.toPath())
        }
        temporaryFile.writeText("user reused this name")
        assertThrows(IOException::class.java) { journal.createPart(temporaryFile) }
        assertEquals(0, journal.recover())
        assertEquals("complete", finalFile.readText())
        assertEquals("user reused this name", temporaryFile.readText())
    }

    @Test
    fun refusesStagingOutsideThePublicRoot() {
        val root = temporary.newFolder("root").canonicalFile
        val outside = part(temporary.newFolder("outside").canonicalFile)
        val journal = NasPublicDownloadPartJournal(context, root)
        assertThrows(IOException::class.java) { journal.createPart(outside) }
        assertFalse(outside.exists())
    }

    private fun part(directory: File) = File(directory, ".comiclab-nas-${UUID.randomUUID()}.part")
}
