package com.example.comiclab

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** A small bounded queue for cover work with keyed cancellation of queued tasks. */
internal class BookcaseCoverTaskQueue(
    threadCount: Int,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : Closeable {

    private val executor: ThreadPoolExecutor
    private val tasks = ConcurrentHashMap<String, FutureTask<Unit>>()

    @Volatile
    private var closed = false

    init {
        require(threadCount > 0) { "threadCount must be positive" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        executor = ThreadPoolExecutor(
            threadCount,
            threadCount,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    fun execute(
        key: String,
        onRejected: () -> Unit = {},
        block: () -> Unit
    ): Boolean {
        if (closed) {
            onRejected()
            return false
        }

        lateinit var task: FutureTask<Unit>
        task = FutureTask(Callable {
            try {
                block()
            } finally {
                tasks.remove(key, task)
            }
        })
        if (tasks.putIfAbsent(key, task) != null) {
            onRejected()
            return false
        }

        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            tasks.remove(key, task)
            onRejected()
            false
        }
    }

    fun cancel(key: String): Boolean {
        val task = tasks.remove(key) ?: return false
        val cancelled = task.cancel(false)
        executor.remove(task)
        return cancelled
    }

    override fun close() {
        closed = true
        tasks.values.forEach { it.cancel(false) }
        tasks.clear()
        executor.shutdownNow()
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 48
    }
}
