package com.example.comiclab

import android.content.Intent
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
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
        val executorField = MangaReaderPrepareActivity::class.java
            .getDeclaredField("prepareExecutor")
            .apply { isAccessible = true }
        executorField.set(activity, queuedExecutor)

        try {
            controller.create()

            assertTrue("cleanup must not block Activity creation", staleCache.exists())
            assertEquals(1, queuedExecutor.pendingTaskCount)

            queuedExecutor.runNext()

            assertFalse("expired cache is removed by preparation work", staleCache.exists())
            assertTrue("recent cache is preserved", recentCache.exists())
        } finally {
            controller.pause().stop().destroy()
            cacheRoot.deleteRecursively()
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
}
