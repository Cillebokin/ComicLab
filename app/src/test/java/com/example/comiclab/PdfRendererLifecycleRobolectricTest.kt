package com.example.comiclab

import android.os.ParcelFileDescriptor
import com.example.comiclab.core.archive.PdfRendererLifecycleGate
import com.example.comiclab.core.archive.createPdfRendererOrCloseDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfRendererLifecycleRobolectricTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failedRendererCreationClosesAndroidDescriptorAndRethrowsOriginalFailure() {
        val source = temporaryFolder.newFile("broken.pdf")
        val descriptor = ParcelFileDescriptor.open(
            source,
            ParcelFileDescriptor.MODE_READ_ONLY
        )
        val fileDescriptor = descriptor.fileDescriptor
        val failure = IllegalStateException("renderer construction failed")

        val thrown = runCatching {
            createPdfRendererOrCloseDescriptor(
                createRenderer = { throw failure },
                closeDescriptor = { descriptor.close() }
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertFalse(fileDescriptor.valid())
    }

    @Test
    fun closeWaitsForActiveRendererOperationBeforeClosingAndroidDescriptor() {
        val source: File = temporaryFolder.newFile("active.pdf")
        val descriptor = ParcelFileDescriptor.open(
            source,
            ParcelFileDescriptor.MODE_READ_ONLY
        )
        val fileDescriptor = descriptor.fileDescriptor
        val gate = PdfRendererLifecycleGate()
        val renderStarted = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeCalls = AtomicBoolean(false)

        Thread {
            gate.withOpen {
                renderStarted.countDown()
                releaseRender.await(2, TimeUnit.SECONDS)
            }
        }.start()

        assertTrue(renderStarted.await(2, TimeUnit.SECONDS))

        Thread {
            gate.close {
                closeCalls.set(true)
                descriptor.close()
            }
            closeFinished.countDown()
        }.start()

        assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
        assertTrue(fileDescriptor.valid())

        releaseRender.countDown()

        assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
        assertTrue(closeCalls.get())
        assertFalse(fileDescriptor.valid())
        gate.close { error("renderer close action must run only once") }
    }
}
