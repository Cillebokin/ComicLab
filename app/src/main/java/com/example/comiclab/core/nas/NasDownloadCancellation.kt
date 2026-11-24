package com.example.comiclab.core.nas

import java.io.Closeable

internal class NasDownloadCancellation {
    private enum class State { ACTIVE, CANCELLED, FINALIZING, FINISHED }

    private val lock = Any()
    @Volatile private var state = State.ACTIVE
    private var worker: Thread? = null

    val isCancelled: Boolean
        get() = state == State.CANCELLED

    fun cancel(): Boolean = synchronized(lock) {
        if (state != State.ACTIVE) return@synchronized false
        state = State.CANCELLED
        worker?.interrupt()
        true
    }

    fun checkActive() {
        if (isCancelled || Thread.currentThread().isInterrupted) {
            throw NasShareArchiveDownloader.DownloadCancelledException()
        }
    }

    fun beginPublication() = synchronized(lock) {
        checkActive()
        state = State.FINALIZING
    }

    fun <T> run(block: () -> T): T {
        synchronized(lock) { worker = Thread.currentThread() }
        val result = runCatching {
            checkActive()
            block()
        }
        val wasCancelled = synchronized(lock) {
            worker = null
            if (isCancelled) true else {
                state = State.FINISHED
                false
            }
        }
        if (wasCancelled) {
            Thread.interrupted()
            throw NasShareArchiveDownloader.DownloadCancelledException()
        }
        return result.getOrThrow()
    }
}

/** Failed or cancelled downloads let the owning SMB client force-close the connection. */
internal inline fun <R : AutoCloseable, T> R.useNasResource(
    cancellation: NasDownloadCancellation?,
    action: (R) -> T
): T {
    if (cancellation == null) return use(action)
    var completed = false
    return Closeable {
        if (completed && !cancellation.isCancelled) close()
    }.use {
        action(this).also { completed = true }
    }
}
