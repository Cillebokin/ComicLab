package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArchiveReaderSessionLifecycleTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sharedSessionClosesDelegateOnceAfterEveryLeaseIsReleased() {
        val delegate = RecordingReaderSession()
        val shared = ComicArchive.SharedImageReaderSession.wrap(delegate)
        val firstLease = shared.acquire()
        val secondLease = shared.acquire()

        firstLease.close()
        firstLease.close()
        assertEquals(0, delegate.closeCount.get())

        secondLease.close()
        secondLease.close()
        assertEquals(1, delegate.closeCount.get())

        val failure = runCatching { shared.acquire() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Shared reader session is already closed", failure?.message)
    }

    @Test
    fun zipSessionReadsUntilCloseAndDoesNotReturnBoundsAfterClose() {
        val archive = File(temporaryFolder.root, "session.cbz")
        writeZip(archive, "page.png", createPng())
        val session = ComicArchive.openReaderSession(
            archive,
            File(temporaryFolder.root, "cache")
        )

        assertEquals(ComicArchive.ImageBounds(120, 240), session.readBounds("page.png"))
        val bitmap = session.decodeImageForWidth("page.png", 60)
        assertNotNull(bitmap)
        assertEquals(60, bitmap!!.width)
        assertEquals(120, bitmap.height)
        bitmap.recycle()

        session.close()
        val afterClose = runCatching { session.readBounds("page.png") }
        assertTrue(
            "A closed ZIP reader session must not return image bounds",
            afterClose.isFailure || afterClose.getOrNull() == null
        )
        assertTrue(
            "The JDK ZIP close path currently reports IllegalStateException",
            afterClose.exceptionOrNull() == null || afterClose.exceptionOrNull() is IllegalStateException
        )
        session.close()
    }

    @Test
    fun closingOneLeaseDoesNotCloseDelegateWhileAnotherLeaseIsHeld() {
        val delegate = BlockingReaderSession()
        val shared = ComicArchive.SharedImageReaderSession.wrap(delegate)
        val leaseA = shared.acquire()
        val leaseB = shared.acquire()
        val readFailure = AtomicReference<Throwable?>()
        val readThread = Thread {
            try {
                leaseA.readBounds("blocked")
            } catch (failure: Throwable) {
                readFailure.set(failure)
            }
        }
        readThread.start()

        try {
            assertTrue(delegate.readStarted.await(5, TimeUnit.SECONDS))
            leaseA.close()
            assertFalse(delegate.closeCalled.get())
            assertEquals(0, delegate.closeCount.get())
        } finally {
            delegate.finishRead.countDown()
            readThread.join(5_000)
        }

        assertNull(readFailure.get())
        assertFalse(readThread.isAlive)
        leaseB.close()
        assertEquals(1, delegate.closeCount.get())
    }

    private fun createPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun writeZip(file: File, entryName: String, bytes: ByteArray) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    private open class RecordingReaderSession : ComicArchive.ImageReaderSession {
        val closeCount = AtomicInteger()

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? = null

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? = null

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? = null

        override fun decodeImageForPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? = null

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? = null

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class BlockingReaderSession : RecordingReaderSession() {
        val readStarted = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val closeCalled = AtomicBoolean(false)

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? {
            readStarted.countDown()
            check(finishRead.await(5, TimeUnit.SECONDS)) { "Timed out waiting to finish controlled read" }
            return null
        }

        override fun close() {
            closeCalled.set(true)
            super.close()
        }
    }
}
