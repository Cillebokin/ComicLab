package com.example.comiclab.core.nas

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NasArchiveDownloadRobolectricTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun cancellationOnLastWriteLeavesExistingFileAndRemovesStaging() {
        val destination = temp.newFile("book.cbz").apply { writeText("previous") }
        val cancellation = NasDownloadCancellation()
        assertThrows(NasShareArchiveDownloader.DownloadCancelledException::class.java) {
            write(destination, cancellation, replaceExisting = true, onProgress = { bytes, total ->
                if (bytes == total) assertTrue(cancellation.cancel())
            })
        }
        assertEquals("previous", destination.readText())
        assertEquals(listOf(destination), temp.root.listFiles()?.toList())
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun closeFailureAndLateCancellationKeepThePublishedResult() {
        val destination = File(temp.root, "book.cbz")
        val cancellation = NasDownloadCancellation()
        val result = write(destination, cancellation, afterPublication = {
            assertEquals("sample", destination.readText())
            assertFalse(cancellation.cancel())
            throw IOException("SMB close failed")
        })
        assertEquals(destination, result)
        assertEquals(listOf(destination), temp.root.listFiles()?.toList())
    }

    @Test
    fun localPublicationKeepsAFileCreatedDuringDownload() {
        val destination = File(temp.root, "book.cbz")
        assertThrows(IOException::class.java) {
            write(destination, NasDownloadCancellation(), onProgress = { bytes, total ->
                if (bytes == total) destination.writeText("existing")
            })
        }
        assertEquals("existing", destination.readText())
        assertEquals(listOf(destination), temp.root.listFiles()?.toList())
    }

    private fun write(
        destination: File,
        cancellation: NasDownloadCancellation,
        replaceExisting: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        afterPublication: () -> Unit = {}
    ): File = NasShareArchiveDownloader.writeDownload(
        destination, 6, cancellation, null, replaceExisting, onProgress
    ) { receive ->
        "sample".byteInputStream().use { receive(6, it) }
        afterPublication()
    }
}
