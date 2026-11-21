package com.example.comiclab.ui.reader.manga

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.comiclab.core.settings.ComicLabMangaProgress
import com.example.comiclab.core.store.ReaderProgressStore
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
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
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaReaderProgressControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun latestQueuedPositionIsReadableDuringCommitAndOlderWriterCannotOverwriteIt() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("reopen.cbz")
        val olderExecutor = QueuedExecutorService()
        val newerExecutor = QueuedExecutorService()
        val commitStarted = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val original = application.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        val preferences = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val editor = original.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                        editor.putInt(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        commitStarted.countDown()
                        check(finishCommit.await(5, TimeUnit.SECONDS))
                        return editor.commit()
                    }
                }
            }
        }
        val context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "reader_prefs") preferences else super.getSharedPreferences(name, mode)
        }
        val older = MangaReaderProgressController(application) { olderExecutor }
        val newer = MangaReaderProgressController(context) { newerExecutor }
        older.save(archive, 6, -12, forceCommit = true)
        newer.save(archive, 2, -35, forceCommit = true)
        val writer = FutureTask { newerExecutor.runNext() }
        Thread(writer).apply { isDaemon = true }.start()
        try {
            assertTrue(commitStarted.await(3, TimeUnit.SECONDS))
            val read = FutureTask {
                Triple(
                    newer.readPositionAndOffset(archive),
                    ReaderProgressStore.hasSavedReadingProgress(application, archive),
                    ReaderProgressStore.savedReadingPageCount(application, archive, 10)
                )
            }
            Thread(read).apply { isDaemon = true }.start()
            assertEquals(Triple(2 to -35, true, 3), read.get(2, TimeUnit.SECONDS))
        } finally {
            finishCommit.countDown()
            writer.get(5, TimeUnit.SECONDS)
            olderExecutor.runNext()
        }
        assertEquals(2 to -35, newer.readPositionAndOffset(archive))
    }

    @Test
    fun newSavesReturnAndPublishTheLatestPositionWhileAnOlderCommitIsBlocked() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("blocked-progress.cbz")
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val preferences = application.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        val blockedPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                        editor.putInt(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        commitStarted.countDown()
                        check(releaseCommit.await(5, TimeUnit.SECONDS))
                        return editor.commit()
                    }
                }
            }
        }

        val oldWrite = ReaderProgressStore.prepareSave(blockedPreferences, archive, 1, -8)
        val writer = FutureTask { oldWrite.commit() }
        Thread(writer).apply { isDaemon = true }.start()
        val queue = QueuedExecutorService()
        val reader = MangaReaderProgressController(application) { queue }
        val nextSaves = FutureTask {
            reader.save(archive, 5, -12, forceCommit = false)
            reader.save(archive, 3, -28, forceCommit = true, coveredPosition = 4)
            MangaReaderProgressController(application) { QueuedExecutorService() }
                .readPositionAndOffset(archive)
        }
        try {
            assertTrue("the background writer must be inside commit", commitStarted.await(3, TimeUnit.SECONDS))
            Thread(nextSaves).apply { isDaemon = true }.start()

            assertEquals("registration and immediate reopen must finish before commit", 3 to -28,
                nextSaves.get(2, TimeUnit.SECONDS))
            assertEquals(5, ReaderProgressStore.savedReadingPageCount(application, archive, 10))
            assertNull("the blocked commit must not already be persisted", ReaderProgressStore.savedReadingProgress(application, archive))
        } finally {
            releaseCommit.countDown()
            writer.get(5, TimeUnit.SECONDS)
            nextSaves.get(5, TimeUnit.SECONDS)
            repeat(queue.pendingTaskCount) { queue.runNext() }
        }

        assertEquals(
            ComicLabMangaProgress(archive.absolutePath, position = 3, offset = -28, coveredPosition = 4),
            ReaderProgressStore.savedReadingProgress(application, archive)
        )
    }

    @Test
    fun repeatedSavesCoalesceButExitUpgradesCheckpointAndBacktrackingSaves() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("coalesced.cbz")
        val executor = QueuedExecutorService()
        val controller = MangaReaderProgressController(application) { executor }

        repeat(3) { controller.save(archive, 4, 27, forceCommit = false) }
        assertEquals(1, executor.pendingTaskCount)
        repeat(3) { controller.save(archive, 4, 27, forceCommit = true) }
        assertEquals(2, executor.pendingTaskCount)
        executor.runNext()
        executor.runNext()
        controller.save(archive, 4, 27, forceCommit = true)
        assertEquals(0, executor.pendingTaskCount)

        controller.save(archive, 2, 16, forceCommit = true)
        executor.runNext()
        assertEquals(2 to 16, controller.readPositionAndOffset(archive))
        controller.clear(archive)
        controller.save(archive, 2, 16, forceCommit = true)
        assertEquals(1, executor.pendingTaskCount)
        executor.runNext()
        assertEquals(2 to 16, controller.readPositionAndOffset(archive))
    }

    @Test
    fun failedCommitCanRetryAndOlderFailureDoesNotInvalidateNewerRequest() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("retry.cbz")
        val executor = QueuedExecutorService()
        val original = application.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        var failNextCommit = true
        var commits = 0
        val preferences = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val editor = original.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                        editor.putInt(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        commits++
                        if (failNextCommit) {
                            failNextCommit = false
                            return false
                        }
                        return editor.commit()
                    }
                }
            }
        }
        val context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "reader_prefs") preferences else super.getSharedPreferences(name, mode)
        }
        val controller = MangaReaderProgressController(context) { executor }
        val diagnostics = application.getSharedPreferences("reader_diagnostics", Context.MODE_PRIVATE)
        val previousCheckpoint = diagnostics.getString("latest_checkpoint", null)

        controller.save(archive, 3, 12, forceCommit = true)
        executor.runNext()
        assertEquals(1, commits)
        assertNull(ReaderProgressStore.savedReadingProgress(application, archive))
        assertEquals(previousCheckpoint, diagnostics.getString("latest_checkpoint", null))
        controller.save(archive, 3, 12, forceCommit = true)
        executor.runNext()
        assertEquals(3 to 12, controller.readPositionAndOffset(archive))

        failNextCommit = true
        controller.save(archive, 4, 0, forceCommit = true)
        controller.save(archive, 5, 9, forceCommit = true)
        executor.runNext()
        controller.save(archive, 5, 9, forceCommit = true)
        assertEquals(1, executor.pendingTaskCount)
        executor.runNext()
        assertEquals(4, commits)
        assertEquals(5 to 9, controller.readPositionAndOffset(archive))
    }

    @Test
    fun rejectedSaveDoesNotMigrateButAcceptedDuplicateMigratesOnce() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("before-move.cbz")
        val moved = File(archive.parentFile, "after-move.cbz")
        val executor = QueuedExecutorService()
        val controller = MangaReaderProgressController(application) { executor }

        executor.nextEnqueueFailure = RejectedExecutionException("test")
        controller.save(archive, 6, 21, forceCommit = true)
        assertTrue(archive.renameTo(moved))
        ReaderProgressStore.moveFile(application, archive, moved)
        assertNull(ReaderProgressStore.savedReadingProgress(application, moved))
        assertTrue(moved.renameTo(archive))

        repeat(3) { controller.save(archive, 6, 21, forceCommit = true) }
        assertEquals(1, executor.pendingTaskCount)
        assertTrue(archive.renameTo(moved))
        ReaderProgressStore.moveFile(application, archive, moved)
        controller.shutdown()
        executor.runNext()
        assertEquals(6 to 21, controller.readPositionAndOffset(moved))
        assertEquals(0 to 0, controller.readPositionAndOffset(archive))
        assertTrue(executor.isTerminated)
    }

    @Test
    fun migratedProgressWinsOverAnOlderPendingPositionAtTheDestination() {
        val application = RuntimeEnvironment.getApplication()
        val source = temporaryFolder.newFile("migration-source.cbz")
        val destination = temporaryFolder.newFile("migration-destination.cbz")
        val executor = QueuedExecutorService()
        val controller = MangaReaderProgressController(application) { executor }
        ReaderProgressStore.restoreReadingProgress(
            application, source, ComicLabMangaProgress(source.absolutePath, position = 8, offset = -50)
        )
        controller.save(destination, 3, -21, forceCommit = true)

        assertEquals(3 to -21, controller.readPositionAndOffset(destination))
        ReaderProgressStore.moveFile(application, source, destination)
        assertEquals(8 to -50, controller.readPositionAndOffset(destination))
        executor.runNext()
        assertEquals(8 to -50, controller.readPositionAndOffset(destination))
    }

    @Test
    fun unexpectedEnqueueFailureIsLoggedAndRetryStillMigratesPendingProgress() {
        val application = RuntimeEnvironment.getApplication()
        val archive = temporaryFolder.newFile("unexpected-before-move.cbz")
        val moved = File(archive.parentFile, "unexpected-after-move.cbz")
        val failure = IllegalStateException("test enqueue fault")
        val executor = QueuedExecutorService().apply { nextEnqueueFailure = failure }
        val controller = MangaReaderProgressController(application) { executor }

        controller.save(archive, 6, 21, forceCommit = true)
        assertEquals(0, executor.pendingTaskCount)
        assertEquals(0 to 0, controller.readPositionAndOffset(archive))
        assertTrue(ShadowLog.getLogsForTag("ComicLabReader").any {
            it.msg == "reader progress enqueue failed" && it.throwable === failure
        })
        assertTrue(archive.renameTo(moved))
        ReaderProgressStore.moveFile(application, archive, moved)
        assertNull(ReaderProgressStore.savedReadingProgress(application, moved))
        assertTrue(moved.renameTo(archive))

        controller.save(archive, 6, 21, forceCommit = true)
        assertEquals(1, executor.pendingTaskCount)
        assertTrue(archive.renameTo(moved))
        ReaderProgressStore.moveFile(application, archive, moved)
        assertEquals(6 to 21, controller.readPositionAndOffset(moved))
        assertEquals(0 to 0, controller.readPositionAndOffset(archive))
        executor.runNext()
        assertEquals(6 to 21, controller.readPositionAndOffset(moved))
        assertNull(ReaderProgressStore.savedReadingProgress(application, archive))
    }

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
        var nextEnqueueFailure: RuntimeException? = null

        val pendingTaskCount: Int
            get() = tasks.size

        fun runNext() {
            tasks.removeAt(0).run()
        }

        override fun execute(command: Runnable) {
            check(!shutdown)
            nextEnqueueFailure?.let { failure ->
                nextEnqueueFailure = null
                throw failure
            }
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
