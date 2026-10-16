package com.example.comiclab

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded worker queue with keyed cancellation of queued tasks. */
internal class BoundedKeyedTaskQueue(
    threadCount: Int,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : Closeable {

    private class TaskState {
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        lateinit var task: FutureTask<Unit>
    }

    private val executor: ThreadPoolExecutor
    private val tasks = ConcurrentHashMap<String, TaskState>()

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
    ): Boolean = executeCancellable(key, onRejected) { block() }

    fun executeCancellable(
        key: String,
        onRejected: () -> Unit = {},
        block: (isActive: () -> Boolean) -> Unit
    ): Boolean {
        if (closed) {
            onRejected()
            return false
        }

        val state = TaskState()
        state.task = FutureTask(Callable {
            state.started.set(true)
            try {
                block { !state.cancelled.get() && !closed }
            } finally {
                tasks.remove(key, state)
            }
        })
        if (tasks.putIfAbsent(key, state) != null) {
            onRejected()
            return false
        }

        return try {
            executor.execute(state.task)
            true
        } catch (_: RejectedExecutionException) {
            tasks.remove(key, state)
            onRejected()
            false
        }
    }

    /**
     * Cancels queued work and signals running work to stop at its next safe boundary.
     *
     * Returns `true` only when a queued task was removed before it started. Returns `false` for
     * running tasks (their block observes cancellation through `isActive`), for unknown keys and
     * for tasks that already finished. A running key stays reserved until its block returns.
     */
    fun cancel(key: String): Boolean {
        val state = tasks[key] ?: return false
        state.cancelled.set(true)
        val wasQueued = !state.started.get()
        val cancelledBeforeStart = state.task.cancel(false)
        if (wasQueued && cancelledBeforeStart) {
            tasks.remove(key, state)
            executor.remove(state.task)
            return true
        }
        return false
    }

    override fun close() {
        closed = true
        tasks.values.forEach { state ->
            state.cancelled.set(true)
            state.task.cancel(false)
        }
        tasks.clear()
        executor.shutdownNow()
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 48
    }
}
