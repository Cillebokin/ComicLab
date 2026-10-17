package com.example.comiclab

import android.content.Intent
import android.os.Looper
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseTask
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaReaderPrepareActivityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun staleCacheCleanupWaitsForPreparationExecutor() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("sample.zip")
        val cacheRoot = File(application.cacheDir, "prepared_reader").apply { mkdirs() }
        val staleCache = File(cacheRoot, "stale-test-cache").apply { mkdirs() }
        val recentCache = File(cacheRoot, "recent-test-cache").apply { mkdirs() }
        File(staleCache, "page.jpg").writeBytes(byteArrayOf(1))
        File(recentCache, "page.jpg").writeBytes(byteArrayOf(1))
        assertTrue(staleCache.setLastModified(System.currentTimeMillis() - 25L * 60L * 60L * 1000L))

        val queuedExecutor = QueuedExecutorService()
        val intent = Intent(application, MangaReaderPrepareActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
        }
        val controller = Robolectric.buildActivity(MangaReaderPrepareActivity::class.java, intent)
        val activity = controller.get()
        activity.prepareExecutorForTest = queuedExecutor

        try {
            controller.create()

            assertTrue("cleanup must not block Activity creation", staleCache.exists())
            assertEquals(1, queuedExecutor.pendingTaskCount)

            queuedExecutor.runNext()

            assertTrue(
                "expired cache deletion is scheduled off the preparation thread",
                awaitCondition { !staleCache.exists() }
            )
            assertTrue("recent cache is preserved", recentCache.exists())
        } finally {
            controller.pause().stop().destroy()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun destroyDoesNotDeletePreparedDirectoryWhileWorkerIsStillRunning() {
        val application = RuntimeEnvironment.getApplication()
        val missingArchive = File(temporaryFolder.root, "missing.cbz")
        val executor = Executors.newSingleThreadExecutor()
        val intent = Intent(application, MangaReaderPrepareActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, missingArchive.absolutePath)
        }
        val controller = Robolectric.buildActivity(MangaReaderPrepareActivity::class.java, intent)
        val activity = controller.get()
        activity.prepareExecutorForTest = executor

        val preparedDirectory = temporaryFolder.newFolder("active-prepared-cache")
        File(preparedDirectory, "page.jpg").writeBytes(byteArrayOf(1))
        val registry = PreparedReaderCacheLeaseRegistry.process
        val prepareOwner = registry.acquirePrepareOwner(preparedDirectory)!!
        val writerLease = registry.acquireWriterLease(preparedDirectory)!!
        activity.adoptPreparedDirectoryForTest(preparedDirectory, prepareOwner)

        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        try {
            controller.create()
            executor.execute(PreparedReaderCacheLeaseTask(writerLease) {
                workerStarted.countDown()
                var released = false
                while (!released) {
                    try {
                        releaseWorker.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // Model an archive/native read that does not stop on interruption.
                    }
                }
            })
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS))

            controller.destroy()

            assertTrue(
                "A prepared directory must remain until its in-flight writer has stopped",
                preparedDirectory.exists()
            )
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            awaitCondition { !preparedDirectory.exists() }
            preparedDirectory.deleteRecursively()
        }
    }

    @Test
    fun destroyedPrepareDropsQueuedMainCallbackAndDeletesItsCache() {
        val application = RuntimeEnvironment.getApplication()
        val archive = File(temporaryFolder.root, "no-images.cbz")
        writeZip(archive, "notes.txt" to "not an image".toByteArray())
        val executor = QueuedExecutorService()
        val intent = Intent(application, MangaReaderPrepareActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
        }
        val controller = Robolectric.buildActivity(MangaReaderPrepareActivity::class.java, intent)
        val activity = controller.get()
        activity.prepareExecutorForTest = executor

        try {
            controller.create()
            assertEquals(1, executor.pendingTaskCount)
            executor.runNext()
            val preparedDirectory = checkNotNull(activity.preparedDirectoryForTest)
            assertTrue(preparedDirectory.isDirectory)

            // The worker posted its empty-archive result; destroy before the main callback runs.
            controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(
                "a dropped callback must release the temporary owner and schedule cache cleanup",
                awaitCondition { !preparedDirectory.exists() }
            )
            assertTrue("cleanup must not alter the original archive", archive.isFile)
        } finally {
            if (!activity.isDestroyed) {
                runCatching { controller.destroy() }
            }
            executor.shutdownNow()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun rejectedPreparationEnqueueReleasesBothOwnersAndDeletesOnlyItsCache() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("rejected.cbz")
        archive.writeText("source archive fixture")
        val intent = Intent(application, MangaReaderPrepareActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
        }
        val controller = Robolectric.buildActivity(MangaReaderPrepareActivity::class.java, intent)
        val activity = controller.get()
        activity.prepareExecutorForTest = RejectingExecutorService()

        try {
            controller.create()
            val preparedDirectory = checkNotNull(activity.preparedDirectoryForTest)

            assertTrue(
                "a rejected worker enqueue must release the prepare and writer leases",
                awaitCondition { !preparedDirectory.exists() }
            )
            assertEquals("source archive must remain unchanged", "source archive fixture", archive.readText())
        } finally {
            if (!activity.isDestroyed) {
                runCatching { controller.destroy() }
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun awaitCondition(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }
            Thread.yield()
        }
        return condition()
    }

    private fun writeZip(file: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private class QueuedExecutorService : AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var shutdown = false

        val pendingTaskCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeAt(0).run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }

    private class RejectingExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("test rejection")
        }

        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
    }
}
