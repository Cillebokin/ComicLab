package com.example.comiclab.ui.reader.manga

import com.example.comiclab.core.settings.ComicLabMangaProgress
import com.example.comiclab.core.store.ReaderProgressStore
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaReaderProgressControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun queuedWritesRemainOrderedAndDrainAfterShutdown() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("ordered-progress.cbz")
        ReaderProgressStore.clearSavedReadingProgress(application, archive)
        val executor = QueuedExecutorService()
        val controller = MangaReaderProgressController(application) { executor }

        controller.save(archive, position = 2, offset = 13, forceCommit = false)
        controller.save(archive, position = 4, offset = 27, forceCommit = true)

        assertNull(ReaderProgressStore.savedReadingProgress(application, archive))
        assertEquals(2, executor.pendingTaskCount)

        controller.shutdown()
        assertTrue(executor.isShutdown)
        executor.runNext()
        assertEquals(
            ComicLabMangaProgress(archive.absolutePath, position = 2, offset = 13),
            ReaderProgressStore.savedReadingProgress(application, archive)
        )
        assertFalse(executor.isTerminated)
        executor.runNext()
        assertEquals(
            ComicLabMangaProgress(archive.absolutePath, position = 4, offset = 27),
            ReaderProgressStore.savedReadingProgress(application, archive)
        )

        assertTrue(executor.isTerminated)
    }

    @Test
    fun readAndClearUseTheExistingReaderProgressStoreKeys() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("read-clear-progress.cbz")
        val executor = QueuedExecutorService()
        val controller = MangaReaderProgressController(application) { executor }

        controller.save(archive, position = 5, offset = 24, forceCommit = true)
        executor.runNext()

        assertEquals(5 to 24, controller.readPositionAndOffset(archive))
        controller.clear(archive)
        assertNull(ReaderProgressStore.savedReadingProgress(application, archive))

        controller.shutdown()
        assertFalse(ReaderProgressStore.hasSavedReadingProgress(application, archive))
    }

    private class QueuedExecutorService : AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var shutdown = false

        val pendingTaskCount: Int
            get() = tasks.size

        fun runNext() {
            tasks.removeAt(0).run()
        }

        override fun execute(command: Runnable) {
            check(!shutdown)
            tasks += command
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
