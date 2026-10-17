package com.example.comiclab.core.concurrent

import com.example.comiclab.core.concurrent.LatestTaskDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestTaskDispatcherTest {

    @Test
    fun replacesQueuedLoadWithMostRecentRequestWhileCurrentIoFinishes() {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        )
        val runningIoStarted = CountDownLatch(1)
        val releaseRunningIo = CountDownLatch(1)
        val latestRequestFinished = CountDownLatch(1)
        val completed = mutableListOf<String>()
        val dispatcher = LatestTaskDispatcher(executor)

        try {
            executor.execute {
                runningIoStarted.countDown()
                releaseRunningIo.await(2, TimeUnit.SECONDS)
                completed += "running-io"
            }
            assertTrue(runningIoStarted.await(2, TimeUnit.SECONDS))

            dispatcher.executeLatest {
                completed += "stale-load"
            }
            dispatcher.executeLatest {
                completed += "latest-load"
                latestRequestFinished.countDown()
            }
            releaseRunningIo.countDown()

            assertTrue(latestRequestFinished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("running-io", "latest-load"), completed)
        } finally {
            releaseRunningIo.countDown()
            executor.shutdownNow()
        }
    }
}
