package com.example.comiclab.core.nas

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.comiclab.core.store.PrivacyStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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
import org.robolectric.util.ReflectionHelpers

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
    fun versionCheckAfterCopyPreventsReplacingThePreviousFile() {
        val destination = temp.newFile("book.cbz").apply { writeText("previous") }
        var bytesWritten = 0L
        assertThrows(NasShareArchiveDownloader.RemoteVersionChangedException::class.java) {
            NasShareArchiveDownloader.writeDownload(
                destination, 6, NasDownloadCancellation(), null, true,
                onProgress = { bytes, _ -> bytesWritten = bytes }
            ) { receive ->
                "sample".byteInputStream().use {
                    receive(6, it) {
                        assertEquals(6L, bytesWritten)
                        throw NasShareArchiveDownloader.RemoteVersionChangedException()
                    }
                }
            }
        }
        assertEquals("previous", destination.readText())
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

    @Test
    fun localDownloadDoesNotReuseAnOrdinaryFileThatAppearsBeforeCopying() {
        val destination = File(temp.root, "book.cbz")
        assertThrows(IOException::class.java) {
            NasShareArchiveDownloader.writeDownload(
                destination, 6, NasDownloadCancellation(), null, false, onProgress = { _, _ -> }
            ) { receive ->
                destination.writeText("other!")
                "sample".byteInputStream().use { receive(6, it) {} }
            }
        }
        assertEquals("other!", destination.readText())
        assertEquals(listOf(destination), temp.root.listFiles()?.toList())
    }

    @Test
    fun queuedPublicDownloadRejectsMarkedDestinationBeforeCreatingDirectory() {
        val app = RuntimeEnvironment.getApplication()
        val parent = temp.newFolder("library")
        val targetDirectory = File(parent, "downloads")
        val journal = NasPublicDownloadPartJournal(app, temp.root)
        assertTrue(PrivacyStore.addPrivacyDirectory(app, parent))
        assertThrows(IOException::class.java) {
            NasShareArchiveDownloader.writeDownload(
                File(targetDirectory, "book.cbz"), 6, NasDownloadCancellation(), null, false,
                onProgress = { _, _ -> error("Must not start copying") }, publicParts = journal
            ) { error("Must not open the remote file") }
        }
        assertFalse(targetDirectory.exists())
    }

    @Test
    fun publicDownloadRechecksBeforeCreatingStagingAfterOpeningTheRemoteFile() {
        val app = RuntimeEnvironment.getApplication()
        val directory = temp.newFolder("downloads")
        val journal = NasPublicDownloadPartJournal(app, temp.root)
        assertThrows(IOException::class.java) {
            NasShareArchiveDownloader.writeDownload(
                File(directory, "book.cbz"), 6, NasDownloadCancellation(), null, false,
                onProgress = { bytes, _ ->
                    assertEquals(0, bytes)
                    assertTrue(PrivacyStore.addPrivacyDirectory(app, directory))
                }, publicParts = journal
            ) { receive ->
                "sample".byteInputStream().use { receive(6, it) { error("Must not start copying") } }
            }
        }
        assertEquals(emptyList<File>(), directory.listFiles()?.toList())
        assertTrue(app.getSharedPreferences("nas_public_download_parts", 0).all.isEmpty())
    }

    @Test
    fun publicDownloadRechecksAfterRemoteVersionCheckAndCleansStagingOnDenial() {
        val app = RuntimeEnvironment.getApplication()
        val directory = temp.newFolder("downloads")
        val destination = File(directory, "book.cbz")
        val journal = NasPublicDownloadPartJournal(app, temp.root)
        assertThrows(IOException::class.java) {
            NasShareArchiveDownloader.writeDownload(
                destination, 6, NasDownloadCancellation(), null, false,
                onProgress = { _, _ -> }, publicParts = journal
            ) { receive ->
                "sample".byteInputStream().use { input ->
                    receive(6, input) {
                        assertEquals(1, directory.listFiles()?.count { it.name.endsWith(".part") })
                        assertTrue(PrivacyStore.addPrivacyDirectory(app, directory))
                    }
                }
            }
        }
        assertEquals(emptyList<File>(), directory.listFiles()?.toList())
        assertTrue(app.getSharedPreferences("nas_public_download_parts", 0).all.isEmpty())
    }

    @Test
    fun markCommittedDuringStagingRegistrationPreventsCreatingAndWritingThePart() {
        val app = RuntimeEnvironment.getApplication()
        val directory = temp.newFolder("downloads")
        val destination = File(directory, "book.cbz")
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val preferences = super.getSharedPreferences(name, mode)
                if (name != "nas_public_download_parts") return preferences
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                                editor.putBoolean(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                assertTrue(editor.commit())
                                assertTrue(PrivacyStore.addPrivacyDirectory(app, directory))
                                return true
                            }
                        }
                    }
                }
            }
        }
        val journal = NasPublicDownloadPartJournal(context, temp.root)
        var copiedBytes = 0L
        assertThrows(NasShareArchiveDownloader.LocalDownloadPathException::class.java) {
            NasShareArchiveDownloader.writeDownload(
                destination, 6, NasDownloadCancellation(), null, false,
                onProgress = { bytes, _ -> copiedBytes = bytes }, publicParts = journal
            ) { receive -> "sample".byteInputStream().use { receive(6, it) {} } }
        }
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(app, directory))
        assertEquals(0L, copiedBytes)
        assertEquals(emptyList<File>(), directory.listFiles()?.toList())
        assertTrue(app.getSharedPreferences("nas_public_download_parts", 0).all.isEmpty())
    }

    @Test
    fun markCommittedWhileWaitingToPublishPreventsMovingThePartAndClearsItsRecord() {
        val app = RuntimeEnvironment.getApplication()
        val directory = temp.newFolder("downloads")
        val destination = File(directory, "book.cbz")
        val journal = NasPublicDownloadPartJournal(app, temp.root)
        val cancellation = NasDownloadCancellation()
        val publicationLock = ReflectionHelpers.getField<Any>(cancellation, "lock")
        val verifying = CountDownLatch(1)
        val verificationMayFinish = CountDownLatch(1)
        val pending = FutureTask {
            NasShareArchiveDownloader.writeDownload(
                destination, 6, cancellation, null, false,
                onProgress = { _, _ -> }, publicParts = journal
            ) { receive ->
                "sample".byteInputStream().use { input ->
                    receive(6, input) {
                        verifying.countDown()
                        check(verificationMayFinish.await(10, TimeUnit.SECONDS))
                    }
                }
            }
        }
        val worker = Thread(pending, "nas-publication-test").apply { start() }
        try {
            assertTrue(verifying.await(10, TimeUnit.SECONDS))
            synchronized(publicationLock) {
                verificationMayFinish.countDown()
                fun isWaitingToPublish() = worker.state == Thread.State.BLOCKED &&
                    worker.stackTrace.any { it.methodName == "beginPublication" }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!isWaitingToPublish() && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue("Download should be waiting to enter publication", isWaitingToPublish())
                assertTrue(PrivacyStore.addPrivacyDirectory(app, directory))
            }
            val failure = assertThrows(ExecutionException::class.java) { pending.get(10, TimeUnit.SECONDS) }
            assertTrue(failure.cause is NasShareArchiveDownloader.LocalDownloadPathException)
            assertEquals(emptyList<File>(), directory.listFiles()?.toList())
            assertTrue(app.getSharedPreferences("nas_public_download_parts", 0).all.isEmpty())
        } finally {
            verificationMayFinish.countDown()
            worker.join(10000)
        }
    }

    @Test
    fun publicDownloadPublishesAndClearsStagingRecordForOrdinaryDirectory() {
        val app = RuntimeEnvironment.getApplication()
        val directory = temp.newFolder("downloads")
        val destination = File(directory, "book.cbz")
        val journal = NasPublicDownloadPartJournal(app, temp.root)
        val result = NasShareArchiveDownloader.writeDownload(
            destination, 6, NasDownloadCancellation(), null, false,
            onProgress = { _, _ -> }, publicParts = journal
        ) { receive -> "sample".byteInputStream().use { receive(6, it) {} } }
        assertEquals(destination, result)
        assertEquals("sample", destination.readText())
        assertEquals(listOf(destination), directory.listFiles()?.toList())
        assertTrue(app.getSharedPreferences("nas_public_download_parts", 0).all.isEmpty())
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
        "sample".byteInputStream().use { receive(6, it) {} }
        afterPublication()
    }
}
