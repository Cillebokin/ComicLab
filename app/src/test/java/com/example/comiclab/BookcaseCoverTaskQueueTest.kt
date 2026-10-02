package com.example.comiclab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookcaseCoverTaskQueueTest {

    @Test
    fun queuedCoverTaskCanBeRemovedWhenItsViewIsRecycled() {
        val queue = BookcaseCoverTaskQueue(threadCount = 1, queueCapacity = 1)
        val runningTaskStarted = CountDownLatch(1)
        val releaseRunningTask = CountDownLatch(1)
        val queueDrained = CountDownLatch(1)
        val recycledTaskRan = AtomicBoolean(false)

        try {
            assertTrue(queue.execute("running") {
                runningTaskStarted.countDown()
                releaseRunningTask.await(2, TimeUnit.SECONDS)
            })
            assertTrue(runningTaskStarted.await(1, TimeUnit.SECONDS))
            assertTrue(queue.execute("recycled") { recycledTaskRan.set(true) })

            assertTrue(queue.cancel("recycled"))
            assertTrue(queue.execute("drain-barrier") { queueDrained.countDown() })
            releaseRunningTask.countDown()
            assertTrue(queueDrained.await(1, TimeUnit.SECONDS))

            assertFalse(recycledTaskRan.get())
        } finally {
            releaseRunningTask.countDown()
            queue.close()
        }
    }

    @Test
    fun fullBoundedQueueRejectsNewWorkAndNotifiesCaller() {
        val queue = BookcaseCoverTaskQueue(threadCount = 1, queueCapacity = 1)
        val runningTaskStarted = CountDownLatch(1)
        val releaseRunningTask = CountDownLatch(1)
        val rejectionNotified = CountDownLatch(1)

        try {
            assertTrue(queue.execute("running") {
                runningTaskStarted.countDown()
                releaseRunningTask.await(2, TimeUnit.SECONDS)
            })
            assertTrue(runningTaskStarted.await(1, TimeUnit.SECONDS))
            assertTrue(queue.execute("queued") {})

            assertFalse(queue.execute("overflow", onRejected = { rejectionNotified.countDown() }) {})
            assertTrue(rejectionNotified.await(1, TimeUnit.SECONDS))
        } finally {
            releaseRunningTask.countDown()
            queue.close()
        }
    }
}
