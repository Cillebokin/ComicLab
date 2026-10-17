package com.example.comiclab.core.concurrent

import java.util.concurrent.ThreadPoolExecutor

/** Replaces queued work while allowing an already-running operation to finish. */
internal class LatestTaskDispatcher(
    private val executor: ThreadPoolExecutor
) {

    private val lock = Any()
    private var pendingTask: Runnable? = null

    fun executeLatest(block: () -> Unit) {
        lateinit var task: Runnable
        task = Runnable {
            synchronized(lock) {
                if (pendingTask === task) {
                    pendingTask = null
                }
            }
            block()
        }

        synchronized(lock) {
            pendingTask?.let(executor::remove)
            pendingTask = task
            try {
                executor.execute(task)
            } catch (throwable: Throwable) {
                if (pendingTask === task) {
                    pendingTask = null
                }
                throw throwable
            }
        }
    }
}
