package com.example.comiclab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedKeyedTaskQueueTest {

    @Test
    fun cancellingQueuedKeyPreventsItsWorkFromRunning() {
        val queue = BoundedKeyedTaskQueue(threadCount = 1, queueCapacity = 1)
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val queuedRan = AtomicBoolean(false)
        val drainFinished = CountDownLatch(1)

        try {
            assertTrue(queue.execute("running") {
                runningStarted.countDown()
                releaseRunning.await(2, TimeUnit.SECONDS)
            })
            assertTrue(runningStarted.await(1, TimeUnit.SECONDS))
            assertTrue(queue.execute("recycled-item") { queuedRan.set(true) })

            assertTrue(queue.cancel("recycled-item"))
            assertTrue(queue.execute("drain-barrier") { drainFinished.countDown() })
            releaseRunning.countDown()

            assertTrue(drainFinished.await(1, TimeUnit.SECONDS))
            assertFalse(queuedRan.get())
        } finally {
            releaseRunning.countDown()
            queue.close()
        }
    }

    @Test
    fun fullQueueRejectsWorkAndCallsRejectionHandler() {
        val queue = BoundedKeyedTaskQueue(threadCount = 1, queueCapacity = 1)
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val rejected = CountDownLatch(1)

        try {
            assertTrue(queue.execute("running") {
                runningStarted.countDown()
                releaseRunning.await(2, TimeUnit.SECONDS)
            })
            assertTrue(runningStarted.await(1, TimeUnit.SECONDS))
            assertTrue(queue.execute("queued") {})

            assertFalse(queue.execute("overflow", onRejected = { rejected.countDown() }) {})
            assertTrue(rejected.await(1, TimeUnit.SECONDS))
        } finally {
            releaseRunning.countDown()
            queue.close()
        }
    }

    @Test
    fun runningTaskCanObserveCancellationAtItsNextSafeBoundary() {
        val queue = BoundedKeyedTaskQueue(threadCount = 1, queueCapacity = 1)
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val taskFinished = CountDownLatch(1)
        val activeAfterCancellation = AtomicBoolean(true)

        try {
            assertTrue(queue.executeCancellable("running") { isActive ->
                runningStarted.countDown()
                releaseRunning.await(2, TimeUnit.SECONDS)
                activeAfterCancellation.set(isActive())
                taskFinished.countDown()
            })
            assertTrue(runningStarted.await(1, TimeUnit.SECONDS))

            assertFalse(queue.cancel("running"))
            releaseRunning.countDown()

            assertTrue(taskFinished.await(1, TimeUnit.SECONDS))
            assertFalse(activeAfterCancellation.get())
        } finally {
            releaseRunning.countDown()
            queue.close()
        }
    }
}
