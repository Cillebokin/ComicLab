package com.example.comiclab

import com.example.comiclab.core.concurrent.BoundedKeyedTaskQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
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

    @Test
    fun runningKeyStaysReservedUntilItFinishesEvenAfterCancellation() {
        val queue = BoundedKeyedTaskQueue(threadCount = 1, queueCapacity = 1)
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val drainFinished = CountDownLatch(1)
        val rejected = AtomicBoolean(false)

        try {
            assertTrue(queue.execute("running") {
                runningStarted.countDown()
                releaseRunning.await(2, TimeUnit.SECONDS)
            })
            assertTrue(runningStarted.await(1, TimeUnit.SECONDS))
            assertFalse(queue.cancel("running"))

            // 运行中取消后，键仍被占用，同键提交应被拒绝并回调 onRejected。
            assertFalse(queue.execute("running", onRejected = { rejected.set(true) }) {})
            assertTrue(rejected.get())

            releaseRunning.countDown()
            // 单线程执行器：屏障任务结束时，前一个任务的 finally（移除键）已完成。
            assertTrue(queue.execute("drain-barrier") { drainFinished.countDown() })
            assertTrue(drainFinished.await(1, TimeUnit.SECONDS))

            assertTrue(queue.execute("running") {})
        } finally {
            releaseRunning.countDown()
            queue.close()
        }
    }

    @Test
    fun cancelledQueuedKeyCanBeResubmittedAndOnlyTheReplacementRuns() {
        val queue = BoundedKeyedTaskQueue(threadCount = 1, queueCapacity = 2)
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val drainFinished = CountDownLatch(1)
        val staleRan = AtomicBoolean(false)
        val replacementRuns = AtomicInteger(0)

        try {
            assertTrue(queue.execute("running") {
                runningStarted.countDown()
                releaseRunning.await(2, TimeUnit.SECONDS)
            })
            assertTrue(runningStarted.await(1, TimeUnit.SECONDS))

            assertTrue(queue.execute("item") { staleRan.set(true) })
            assertTrue(queue.cancel("item"))
            assertTrue(queue.execute("item") { replacementRuns.incrementAndGet() })

            releaseRunning.countDown()
            assertTrue(queue.execute("drain-barrier") { drainFinished.countDown() })
            assertTrue(drainFinished.await(1, TimeUnit.SECONDS))

            assertFalse(staleRan.get())
            assertEquals(1, replacementRuns.get())
        } finally {
            releaseRunning.countDown()
            queue.close()
        }
    }

    @Test
    fun cancelReturnsFalseForUnknownOrAlreadyFinishedKey() {
        val queue = BoundedKeyedTaskQueue(threadCount = 1, queueCapacity = 1)
        val finished = CountDownLatch(1)
        val drainFinished = CountDownLatch(1)

        try {
            assertFalse(queue.cancel("never-submitted"))

            assertTrue(queue.execute("finished") { finished.countDown() })
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(queue.execute("drain-barrier") { drainFinished.countDown() })
            assertTrue(drainFinished.await(1, TimeUnit.SECONDS))

            assertFalse(queue.cancel("finished"))
        } finally {
            queue.close()
        }
    }

    @Test
    fun closingQueueMakesRunningTaskInactive() {
        val queue = BoundedKeyedTaskQueue(threadCount = 1, queueCapacity = 1)
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val taskFinished = CountDownLatch(1)
        val activeAfterClose = AtomicBoolean(true)

        try {
            assertTrue(queue.executeCancellable("running") { isActive ->
                runningStarted.countDown()
                try {
                    // close() 会中断工作线程，这里忽略中断，只关心 close 之后 isActive 的结果。
                    releaseRunning.await(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
                activeAfterClose.set(isActive())
                taskFinished.countDown()
            })
            assertTrue(runningStarted.await(1, TimeUnit.SECONDS))

            queue.close()
            releaseRunning.countDown()

            assertTrue(taskFinished.await(3, TimeUnit.SECONDS))
            assertFalse(activeAfterClose.get())
        } finally {
            releaseRunning.countDown()
            queue.close()
        }
    }
}
