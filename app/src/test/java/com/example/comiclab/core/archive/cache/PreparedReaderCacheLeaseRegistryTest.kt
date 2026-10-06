package com.example.comiclab.core.archive.cache

import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedReaderCacheLeaseRegistryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deleteWaitsForLastLeaseAndLeaseCloseIsIdempotent() {
        val deletionExecutor = QueuedExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor)
        val directory = temporaryFolder.newFolder("active-cache")
        File(directory, "page.jpg").writeText("page")
        val prepareOwner = registry.acquirePrepareOwner(directory)!!
        val writer = registry.acquireWriterLease(File(directory, "nested/.."))!!

        prepareOwner.requestDeleteAndClose()

        assertTrue(directory.isDirectory)
        assertEquals(0, deletionExecutor.pendingTaskCount)
        writer.close()
        writer.close()
        assertEquals(1, deletionExecutor.pendingTaskCount)
        assertTrue(directory.isDirectory)

        deletionExecutor.runNext()

        assertFalse(directory.exists())
    }

    @Test
    fun readerAcquisitionCancelsPendingDeleteAndConsumesHandoff() {
        val deletionExecutor = QueuedExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor)
        val directory = temporaryFolder.newFolder("handoff-cache")
        File(directory, "page.jpg").writeText("page")
        val prepareOwner = registry.acquirePrepareOwner(directory)!!
        val handoff = registry.acquireHandoffLease(directory)!!

        prepareOwner.requestDeleteAndClose()
        assertTrue(directory.isDirectory)
        assertEquals(0, deletionExecutor.pendingTaskCount)

        val readerOwner = registry.acquireReaderOwner(File(directory, "unused/.."))
        assertTrue("reader owner should atomically take over the handoff", readerOwner != null)
        handoff.close()
        assertEquals(0, deletionExecutor.pendingTaskCount)

        readerOwner!!.requestDeleteAndClose()
        assertEquals(1, deletionExecutor.pendingTaskCount)
        deletionExecutor.runNext()
        assertFalse(directory.exists())
    }

    @Test
    fun failedReaderLaunchDropsHandoffButWaitsForActiveWriterBeforeDeleting() {
        val deletionExecutor = QueuedExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor)
        val directory = temporaryFolder.newFolder("failed-launch-cache")
        File(directory, "page.jpg").writeText("page")
        val prepareOwner = registry.acquirePrepareOwner(directory)!!
        val writer = registry.acquireWriterLease(directory)!!
        val handoff = registry.acquireHandoffLease(directory)!!

        // This is the cleanup sequence used when startActivity throws.
        handoff.requestDeleteAndClose()
        prepareOwner.requestDeleteAndClose()

        assertTrue(directory.isDirectory)
        assertEquals(0, deletionExecutor.pendingTaskCount)
        writer.close()
        deletionExecutor.runNext()
        assertFalse(directory.exists())
    }

    @Test
    fun remainingReaderOwnerCanContinueAcquiringUsageLeasesAfterAnotherOwnerCloses() {
        val deletionExecutor = QueuedExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor)
        val directory = temporaryFolder.newFolder("shared-reader-cache")
        File(directory, "page.jpg").writeText("page")
        val firstReader = registry.acquireReaderOwner(directory)!!
        val secondReader = registry.acquireReaderOwner(directory)!!

        firstReader.requestDeleteAndClose()
        val laterPageRead = registry.acquireUsageLease(directory)

        assertTrue("an active reader owner must remain usable", laterPageRead != null)
        assertTrue(directory.isDirectory)
        secondReader.close()
        assertEquals(0, deletionExecutor.pendingTaskCount)
        laterPageRead!!.close()
        assertEquals(1, deletionExecutor.pendingTaskCount)
        deletionExecutor.runNext()
        assertFalse(directory.exists())
    }

    @Test
    fun staleCleanupSkipsActiveOwnerAndExpiresOnlyAnOldHandoff() {
        val deletionExecutor = QueuedExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor)
        val now = System.currentTimeMillis()
        val maxAgeMs = 24L * 60L * 60L * 1000L
        val oldDirectory = temporaryFolder.newFolder("old-active-cache")
        File(oldDirectory, "page.jpg").writeText("page")
        assertTrue(oldDirectory.setLastModified(now - maxAgeMs - 1L))
        val activeReader = registry.acquireReaderOwner(oldDirectory)!!

        assertFalse(registry.requestDeleteIfStale(oldDirectory, maxAgeMs, now))
        assertTrue(oldDirectory.isDirectory)
        assertEquals(0, deletionExecutor.pendingTaskCount)
        activeReader.close()
        assertTrue(registry.requestDeleteIfStale(oldDirectory, maxAgeMs, now))
        deletionExecutor.runNext()
        assertFalse(oldDirectory.exists())

        val orphanDirectory = temporaryFolder.newFolder("old-handoff-cache")
        File(orphanDirectory, "page.jpg").writeText("page")
        assertTrue(orphanDirectory.setLastModified(now - maxAgeMs - 1L))
        registry.acquireHandoffLease(orphanDirectory, createdAtMillis = now - maxAgeMs - 1L)!!

        assertTrue(registry.requestDeleteIfStale(orphanDirectory, maxAgeMs, now))
        deletionExecutor.runNext()
        assertFalse(orphanDirectory.exists())
    }

    @Test
    fun recursiveDeletionDoesNotHoldTheRegistryLock() {
        val deleteStarted = CountDownLatch(1)
        val finishDelete = CountDownLatch(1)
        val registry = PreparedReaderCacheLeaseRegistry(
            deletionExecutor = Executor { it.run() },
            deleteDirectory = { directory ->
                deleteStarted.countDown()
                assertTrue(finishDelete.await(5, TimeUnit.SECONDS))
                directory.deleteRecursively()
            }
        )
        val deletingDirectory = temporaryFolder.newFolder("blocking-delete")
        val unrelatedDirectory = temporaryFolder.newFolder("unrelated-cache")
        val owner = registry.acquirePrepareOwner(deletingDirectory)!!
        val deletionThread = Thread { owner.requestDeleteAndClose() }.apply { isDaemon = true; start() }
        val unrelatedLease = AtomicReference<PreparedReaderCacheLeaseRegistry.Lease?>()
        val acquired = CountDownLatch(1)

        try {
            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS))
            Thread({
                unrelatedLease.set(registry.acquireReaderOwner(unrelatedDirectory))
                acquired.countDown()
            }, "PreparedCacheUnrelatedAcquire").apply { isDaemon = true; start() }
            assertTrue("unrelated acquisition must proceed during recursive deletion", acquired.await(2, TimeUnit.SECONDS))
            assertTrue(unrelatedLease.get() != null)
        } finally {
            finishDelete.countDown()
            deletionThread.join(5_000)
            unrelatedLease.get()?.close()
        }
    }

    @Test
    fun failedDeletionKeepsPartialCacheUnavailableUntilRetrySucceeds() {
        val deletionExecutor = QueuedExecutor()
        var shouldFail = true
        var failureCount = 0
        val registry = PreparedReaderCacheLeaseRegistry(
            deletionExecutor = deletionExecutor,
            deleteDirectory = { directory ->
                if (shouldFail) {
                    File(directory, "page.jpg").delete()
                    false
                } else {
                    directory.deleteRecursively()
                }
            },
            onDeletionFailure = { _, _ -> failureCount++ }
        )
        val directory = temporaryFolder.newFolder("partial-delete")
        File(directory, "page.jpg").writeText("page")
        registry.acquirePrepareOwner(directory)!!.requestDeleteAndClose()

        deletionExecutor.runNext()

        assertEquals(1, failureCount)
        assertTrue(directory.isDirectory)
        assertNull("a partially deleted cache must not reopen", registry.acquireReaderOwner(directory))

        shouldFail = false
        registry.requestDelete(directory)
        deletionExecutor.runNext()
        assertFalse(directory.exists())
    }

    @Test
    fun writerLeasesSerializeWritesToTheSameCanonicalDirectory() {
        val registry = PreparedReaderCacheLeaseRegistry(QueuedExecutor())
        val directory = temporaryFolder.newFolder("writer-lock")
        val firstWriter = registry.acquireWriterLease(directory)!!
        val secondWriter = registry.acquireWriterLease(File(directory, "child/.."))!!
        val firstEntered = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        val first = Thread {
            firstWriter.withWriteLock {
                firstEntered.countDown()
                releaseFirst.await()
            }
        }.apply { isDaemon = true; start() }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val second = Thread {
            secondAttempting.countDown()
            secondWriter.withWriteLock { secondEntered.countDown() }
        }.apply { isDaemon = true; start() }

        try {
            assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
            assertFalse("the second writer must wait for the same-cache lock", secondEntered.await(150, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
        }

        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        first.join(5_000)
        second.join(5_000)
        firstWriter.close()
        secondWriter.close()
    }

    @Test
    fun queuedTaskCancellationReleasesLeaseButRunningTaskReleasesOnlyAfterIoStops() {
        val deletionExecutor = QueuedExecutor()
        val registry = PreparedReaderCacheLeaseRegistry(deletionExecutor)
        val queuedDirectory = temporaryFolder.newFolder("queued-cache")
        File(queuedDirectory, "page.jpg").writeText("page")
        val queuedLease = registry.acquireWriterLease(queuedDirectory)!!
        val queuedRan = AtomicInteger()
        val queuedTask = PreparedReaderCacheLeaseTask(queuedLease) { queuedRan.incrementAndGet() }
        registry.requestDelete(queuedDirectory)
        queuedTask.cancelBeforeRun()
        queuedTask.cancelBeforeRun()
        deletionExecutor.runNext()
        assertEquals(0, queuedRan.get())
        assertFalse(queuedDirectory.exists())

        val runningDirectory = temporaryFolder.newFolder("running-cache")
        File(runningDirectory, "page.jpg").writeText("page")
        val runningLease = registry.acquireWriterLease(runningDirectory)!!
        val started = CountDownLatch(1)
        val finishIo = CountDownLatch(1)
        val task = PreparedReaderCacheLeaseTask(runningLease) {
            started.countDown()
            while (true) {
                try {
                    finishIo.await()
                    break
                } catch (_: InterruptedException) {
                    // Simulate archive/native IO that does not stop when interrupted.
                }
            }
        }
        registry.requestDelete(runningDirectory)
        val worker = Thread(task).apply { isDaemon = true; start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        worker.interrupt()
        assertTrue(runningDirectory.isDirectory)
        assertEquals(0, deletionExecutor.pendingTaskCount)
        finishIo.countDown()
        worker.join(5_000)
        assertEquals(1, deletionExecutor.pendingTaskCount)
        deletionExecutor.runNext()
        assertFalse(runningDirectory.exists())
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var shutdown = false

        val pendingTaskCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            check(!shutdown)
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
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
